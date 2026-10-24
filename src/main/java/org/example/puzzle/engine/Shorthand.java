package org.example.puzzle.engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Desugars the compact reference notation used inside effects into the
 * regular expression AST, so the evaluator only has to know one form:
 *
 * <pre>
 *   ref  ::= '$' name [ '(' [ arg { ',' arg } ] ')' ] { '.' property }
 *   arg  ::= ref | bare-literal
 *
 *   $target               → binding "target" (kept as the string "$target")
 *   $target.focused       → {"op":"checkProperty","args":["$target","focused"]}
 *   $not(x)               → {"op":"not","args":[x]}
 *   $neighbour(N)         → {"op":"neighbour","args":["$target","N"]}
 * </pre>
 */
final class Shorthand {

    private static final Pattern BINDING = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Shorthand() {
    }

    static boolean isBinding(String s) {
        return BINDING.matcher(s).matches();
    }

    static JsonNode parse(String source) {
        Parser p = new Parser(source);
        JsonNode node = p.ref();
        p.skipSpaces();
        if (p.pos != source.length()) {
            throw new EngineException("Unexpected '" + source.substring(p.pos) + "' in reference '" + source + "'.");
        }
        return node;
    }

    static ObjectNode op(String name, List<JsonNode> args) {
        ObjectNode node = NODES.objectNode();
        node.put("op", name);
        ArrayNode arr = node.putArray("args");
        args.forEach(arr::add);
        return node;
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        JsonNode ref() {
            skipSpaces();
            expect('$');
            String name = name();
            JsonNode node;
            if (peek('(')) {
                pos++;
                List<JsonNode> args = new ArrayList<>();
                skipSpaces();
                if (!peek(')')) {
                    do {
                        args.add(arg());
                        skipSpaces();
                    } while (accept(','));
                }
                expect(')');
                if (name.equals("neighbour") && args.size() == 1) {
                    args.addFirst(NODES.stringNode("$target"));
                }
                node = op(name, args);
            } else {
                node = NODES.stringNode("$" + name);
            }
            while (accept('.')) {
                node = op("checkProperty", List.of(node, NODES.stringNode(name())));
            }
            return node;
        }

        private JsonNode arg() {
            skipSpaces();
            if (peek('$')) return ref();
            int start = pos;
            while (pos < src.length() && src.charAt(pos) != ',' && src.charAt(pos) != ')') pos++;
            String bare = src.substring(start, pos).trim();
            if (bare.isEmpty()) throw new EngineException("Empty argument in reference '" + src + "'.");
            return NODES.stringNode(bare);
        }

        private String name() {
            int start = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
            if (start == pos) throw new EngineException("Expected a name at position " + pos + " in '" + src + "'.");
            return src.substring(start, pos);
        }

        void skipSpaces() {
            while (pos < src.length() && src.charAt(pos) == ' ') pos++;
        }

        private boolean peek(char c) {
            return pos < src.length() && src.charAt(pos) == c;
        }

        private boolean accept(char c) {
            if (!peek(c)) return false;
            pos++;
            return true;
        }

        private void expect(char c) {
            if (!accept(c)) throw new EngineException("Expected '" + c + "' at position " + pos + " in '" + src + "'.");
        }
    }
}
