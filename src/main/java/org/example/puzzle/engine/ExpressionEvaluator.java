package org.example.puzzle.engine;

import org.example.puzzle.model.Function;
import tools.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates expression ASTs ({@code {"op": ..., "args": [...]}}) against a
 * {@link GameState}.
 *
 * <p>Leaves: booleans and numbers are literals; a string starting with
 * {@code $} is a binding ({@code $target}, {@code $src}, ...) or a shorthand
 * reference (see {@link Shorthand}); any other string is a literal, usually an
 * object, position or type id.
 */
public final class ExpressionEvaluator {

    private final Map<String, JsonNode> functions = new HashMap<>();
    private final Map<String, JsonNode> shorthandCache = new ConcurrentHashMap<>();

    public ExpressionEvaluator(List<Function> functions) {
        for (Function f : functions) this.functions.put(f.id, f.expression);
    }

    public boolean hasFunction(String name) {
        return functions.containsKey(name);
    }

    public Object eval(JsonNode expr, GameState state, Map<String, Object> bindings) {
        return eval(expr, new Ctx(state, bindings, new ArrayDeque<>()));
    }

    public boolean test(JsonNode expr, GameState state, Map<String, Object> bindings) {
        return Values.truthy(eval(expr, state, bindings));
    }

    private record Ctx(GameState state, Map<String, Object> bindings, Deque<String> callStack) {
    }

    private Object eval(JsonNode e, Ctx c) {
        if (e == null || e.isNull() || e.isMissingNode()) return null;
        if (e.isString()) return evalString(e.asString(), c);
        if (e.isObject()) return evalOp(e, c);
        return Values.literal(e);
    }

    private Object evalString(String s, Ctx c) {
        if (!s.startsWith("$")) return s;
        if (Shorthand.isBinding(s)) {
            String name = s.substring(1);
            if (!c.bindings.containsKey(name)) {
                throw new EngineException("Binding '" + s + "' is not available in this context.");
            }
            return c.bindings.get(name);
        }
        return eval(shorthandCache.computeIfAbsent(s, Shorthand::parse), c);
    }

    private Object evalOp(JsonNode e, Ctx c) {
        JsonNode opNode = e.get("op");
        if (opNode == null || !opNode.isString()) throw new EngineException("Expression without 'op': " + e);
        String op = opNode.asString();
        List<JsonNode> a = new ArrayList<>();
        JsonNode args = e.get("args");
        if (args != null) args.forEach(a::add);
        GameState s = c.state;

        return switch (op) {
            case "and" -> {
                for (JsonNode x : a) if (!Values.truthy(eval(x, c))) yield false;
                yield true;
            }
            case "or" -> {
                for (JsonNode x : a) if (Values.truthy(eval(x, c))) yield true;
                yield false;
            }
            case "not" -> !Values.truthy(eval(arg(a, 0, op), c));
            case "eq" -> Values.equal(eval(arg(a, 0, op), c), eval(arg(a, 1, op), c));
            case "neq" -> !Values.equal(eval(arg(a, 0, op), c), eval(arg(a, 1, op), c));
            case "lt" -> Values.compare(eval(arg(a, 0, op), c), eval(arg(a, 1, op), c)) < 0;
            case "leq" -> Values.compare(eval(arg(a, 0, op), c), eval(arg(a, 1, op), c)) <= 0;
            case "gt" -> Values.compare(eval(arg(a, 0, op), c), eval(arg(a, 1, op), c)) > 0;
            case "geq" -> Values.compare(eval(arg(a, 0, op), c), eval(arg(a, 1, op), c)) >= 0;
            case "add", "sub", "mul", "div", "mod" -> {
                Object acc = eval(arg(a, 0, op), c);
                for (int i = 1; i < Math.max(a.size(), 2); i++) acc = Values.arithmetic(op, acc, eval(arg(a, i, op), c));
                yield acc;
            }
            case "containsAny" -> (long) s.objectsAt(position(arg(a, 0, op), c)).size();
            case "containsTyped" -> {
                String pos = position(arg(a, 0, op), c);
                String type = Values.asId(eval(arg(a, 1, op), c), "type");
                yield s.objectsAt(pos).stream().filter(o -> type.equals(s.type(o))).count();
            }
            case "isEmpty" -> s.objectsAt(position(arg(a, 0, op), c)).isEmpty();
            case "checkProperty" -> {
                String id = optionalId(arg(a, 0, op), c);
                String prop = Values.asId(eval(arg(a, 1, op), c), "property");
                yield id == null ? null : s.property(id, prop);
            }
            case "checkType" -> {
                String id = optionalId(arg(a, 0, op), c);
                yield id != null && Values.equal(s.type(id), eval(arg(a, 1, op), c));
            }
            case "checkLocation" -> {
                String obj = id(arg(a, 0, op), c);
                String pos = id(arg(a, 1, op), c);
                yield pos.equals(s.location(obj));
            }
            case "isVisible" -> {
                String id = optionalId(arg(a, 0, op), c);
                yield id != null && Values.truthy(s.property(id, "visible"));
            }
            case "manhattan" -> {
                double[] p = s.coordinates(position(arg(a, 0, op), c));
                double[] q = s.coordinates(position(arg(a, 1, op), c));
                yield GameState.number(Math.abs(p[0] - q[0]) + Math.abs(p[1] - q[1]));
            }
            case "neighbour" -> neighbour(arg(a, 0, op), arg(a, 1, op), c);
            case "count" -> {
                long n = 0;
                for (JsonNode x : a) if (Values.truthy(eval(x, c))) n++;
                yield n;
            }
            case "constant" -> Values.literal(arg(a, 0, op));
            case "var" -> var(Values.asId(Values.literal(arg(a, 0, op)), "function"), c);
            default -> throw new EngineException("Unknown operator '" + op + "'.");
        };
    }

    /**
     * {@code neighbour(x, D)} with a direction D ∈ {N, S, E, W} returns the
     * neighbouring entity of the same kind as x (position → position,
     * object → object standing there), or null at the board edge.
     * {@code neighbour(x, y)} with two entities is true when they are adjacent.
     */
    private Object neighbour(JsonNode first, JsonNode second, Ctx c) {
        GameState s = c.state;
        String from = optionalId(first, c);
        Object other = eval(second, c);
        if (from == null || other == null) return null;
        String otherId = Values.asId(other, "entity or direction");
        if (!s.exists(otherId)) {
            String pos = s.positionOf(from);
            String next = pos == null ? null : s.neighbourPosition(pos, otherId);
            if (next == null) return null;
            return s.isObject(from) ? s.topObjectAt(next) : next;
        }
        double[] p = s.coordinates(s.positionOf(from));
        double[] q = s.coordinates(s.positionOf(otherId));
        return Math.abs(p[0] - q[0]) + Math.abs(p[1] - q[1]) == 1;
    }

    private Object var(String name, Ctx c) {
        JsonNode body = functions.get(name);
        if (body == null) {
            if (name.equals("result")) return GameState.number(c.state.counter());
            throw new EngineException("Undefined function '" + name + "'.");
        }
        if (c.callStack.contains(name)) {
            throw new EngineException("Cyclic function definition: " + String.join(" → ", c.callStack) + " → " + name + ".");
        }
        c.callStack.addLast(name);
        try {
            return eval(body, c);
        } finally {
            c.callStack.removeLast();
        }
    }

    private String id(JsonNode n, Ctx c) {
        String id = optionalId(n, c);
        if (id == null) throw new EngineException("Expression " + n + " does not denote an object or position.");
        return id;
    }

    private String optionalId(JsonNode n, Ctx c) {
        Object v = eval(n, c);
        return v == null ? null : Values.asId(v, "object or position");
    }

    private String position(JsonNode n, Ctx c) {
        String pos = c.state.positionOf(id(n, c));
        if (pos == null) throw new EngineException("Object " + n + " is not on the board.");
        return pos;
    }

    private static JsonNode arg(List<JsonNode> args, int i, String op) {
        if (i >= args.size()) throw new EngineException("Operator '" + op + "' expects at least " + (i + 1) + " argument(s).");
        return args.get(i);
    }
}
