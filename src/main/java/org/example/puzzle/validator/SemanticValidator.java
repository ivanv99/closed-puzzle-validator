package org.example.puzzle.validator;

import org.example.puzzle.engine.EngineException;
import org.example.puzzle.engine.Shorthand;
import tools.jackson.databind.JsonNode;
import org.example.puzzle.model.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 2 — semantic validation. Verifies that cross-references are
 * consistent, that {@code InitDisp} respects capacity constraints, that
 * the model satisfies the close-ended properties, etc.
 */
public final class SemanticValidator {

    public void validate(ClosedPuzzle puzzle, ValidationReport r) {
        if (puzzle == null) {
            r.error("DOC-001", "Document is empty.");
            return;
        }

        Map<String, PositionType> ptIx = indexById(puzzle.PT, t -> t.id, r, "/PT");
        Map<String, Position> pIx = indexById(puzzle.P, o -> o.id, r, "/P");
        Map<String, ObjectType> otIx = indexById(puzzle.OT, t -> t.id, r, "/OT");
        Map<String, ObjectInstance> oIx = indexById(puzzle.O, o -> o.id, r, "/O");
        Map<String, Action> aIx = indexById(puzzle.A, a -> a.id, r, "/A");
        Map<String, Event> eIx = indexById(puzzle.E, e -> e.id, r, "/E");

        // P → PT references
        for (Position pos : puzzle.P) {
            if (pos.type != null && !ptIx.containsKey(pos.type)) {
                r.error("REF-PT", "Position '" + pos.id + "' references unknown PositionType '" + pos.type + "'.",
                        "/P/" + pos.id);
            }
        }
        // O → OT references
        for (ObjectInstance obj : puzzle.O) {
            if (obj.type != null && !otIx.containsKey(obj.type)) {
                r.error("REF-OT", "Object '" + obj.id + "' references unknown ObjectType '" + obj.type + "'.",
                        "/O/" + obj.id);
            }
        }

        // InitDisp: existing refs + capacity constraint
        Map<String, Integer> load = new HashMap<>();
        Set<String> placedObjects = new HashSet<>();
        for (Placement pl : puzzle.InitDisp) {
            if (!oIx.containsKey(pl.object)) {
                r.error("REF-OBJ", "InitDisp references unknown object '" + pl.object + "'.",
                        "/InitDisp");
            }
            if (!pIx.containsKey(pl.position)) {
                r.error("REF-POS", "InitDisp references unknown position '" + pl.position + "'.",
                        "/InitDisp");
                continue;
            }
            if (!placedObjects.add(pl.object)) {
                r.warn("INIT-DUP",
                        "Object '" + pl.object + "' is placed more than once in InitDisp.",
                        "/InitDisp");
            }
            load.merge(pl.position, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : load.entrySet()) {
            Position pos = pIx.get(entry.getKey());
            if (pos != null && pos.capacity != null && entry.getValue() > pos.capacity) {
                r.error("CAP-001",
                        "Position '" + pos.id + "' has capacity " + pos.capacity
                                + " but InitDisp puts " + entry.getValue() + " object(s) on it.",
                        "/InitDisp");
            }
        }

        // RandInit: shuffled objects must be placed, scramble actions must exist
        if (puzzle.RandInit != null) {
            Set<String> shuffled = new HashSet<>();
            for (List<String> group : puzzle.RandInit.shuffle) {
                for (String obj : group) {
                    if (!placedObjects.contains(obj)) {
                        r.error("RAND-OBJ", "RandInit shuffles '" + obj + "', which is not placed in InitDisp.",
                                "/RandInit/shuffle");
                    } else if (!shuffled.add(obj)) {
                        r.warn("RAND-DUP", "Object '" + obj + "' is in more than one shuffle group.",
                                "/RandInit/shuffle");
                    }
                }
            }
            if (puzzle.RandInit.scramble != null) {
                for (String a : puzzle.RandInit.scramble.actions) {
                    if (!aIx.containsKey(a)) {
                        r.error("RAND-ACT", "RandInit scrambles with unknown action '" + a + "'.",
                                "/RandInit/scramble");
                    }
                }
            }
        }

        // R → A and R.fire → E references
        for (Rule rule : puzzle.R) {
            if (rule.action != null && !aIx.containsKey(rule.action)) {
                r.error("REF-ACT", "Rule '" + rule.id + "' references unknown action '" + rule.action + "'.",
                        "/R/" + rule.id);
            }
            for (String evId : rule.fire) {
                if (!eIx.containsKey(evId)) {
                    r.error("REF-EV", "Rule '" + rule.id + "' fires unknown event '" + evId + "'.",
                            "/R/" + rule.id);
                }
            }
        }

        // Event targets must be known objects or positions
        for (Event ev : puzzle.E) {
            for (String t : ev.target) {
                if (!oIx.containsKey(t) && !pIx.containsKey(t)) {
                    r.warn("REF-TGT",
                            "Event '" + ev.id + "' targets unknown id '" + t + "' (neither object nor position).",
                            "/E/" + ev.id);
                }
            }
        }

        // Close-ended property: FC must be present and decidable in form
        if (puzzle.FC == null || puzzle.FC.isNull()) {
            r.error("CE-FC", "A close-ended puzzle must define a final condition FC.");
        } else {
            checkExpression(puzzle.FC, "/FC", r);
        }

        // Res should be present (otherwise the game has no outcome)
        if (puzzle.Res == null || puzzle.Res.isNull()) {
            r.warn("CE-RES", "Puzzle has no Res expression — outcome will be undefined.", "/Res");
        } else {
            checkExpression(puzzle.Res, "/Res", r);
        }

        checkFunctionReferences(puzzle, r);

        // At least one rule should reference each declared action (otherwise the action is dead code)
        Set<String> usedActions = new HashSet<>();
        for (Rule rule : puzzle.R) usedActions.add(rule.action);
        for (Action a : puzzle.A) {
            if (!usedActions.contains(a.id)) {
                r.warn("ACT-UNUSED", "Action '" + a.id + "' has no rule using it.", "/A/" + a.id);
            }
        }
    }

    /**
     * Simple expression sanity check: known operator names and recursive arg checking.
     */
    private void checkExpression(JsonNode expr, String path, ValidationReport r) {
        if (expr == null || expr.isNull()) return;
        if (expr.isObject()) {
            JsonNode opNode = expr.get("op");
            if (opNode == null || !opNode.isString()) {
                r.error("EXPR-001", "Expression node has no 'op' field.", path);
                return;
            }
            JsonNode args = expr.get("args");
            if (args != null && args.isArray()) {
                int i = 0;
                for (JsonNode child : args) {
                    checkExpression(child, path + "/args/" + i, r);
                    i++;
                }
            }
        }
        // primitives (boolean / number / string) are always accepted
    }

    /* ----- functions: references and cycles ------------------------------ */

    /** Built-in function, always available: the result counter. */
    private static final String RESULT = "result";

    /**
     * Every {@code var} reference (and every function name used as an effect's
     * {@code if}) in F, FC, Res, rule guards and events must name a function of
     * F; and the call graph of F must be acyclic, otherwise evaluating it never
     * terminates.
     */
    private void checkFunctionReferences(ClosedPuzzle puzzle, ValidationReport r) {
        Set<String> defined = new HashSet<>();
        for (Function f : puzzle.F) if (f.id != null) defined.add(f.id);

        Map<String, Set<String>> calls = new LinkedHashMap<>();
        for (Function f : puzzle.F) {
            if (f.id == null) continue;
            Set<String> refs = references(f.expression, "/F/" + f.id, r);
            calls.put(f.id, refs);
            reportUndefined(refs, defined, "Function '" + f.id + "'", "/F/" + f.id, r);
        }
        reportUndefined(references(puzzle.FC, "/FC", r), defined, "FC", "/FC", r);
        reportUndefined(references(puzzle.Res, "/Res", r), defined, "Res", "/Res", r);
        for (Rule rule : puzzle.R) {
            reportUndefined(references(rule.when, "/R/" + rule.id, r), defined,
                    "Rule '" + rule.id + "'", "/R/" + rule.id, r);
        }
        for (Event ev : puzzle.E) {
            Set<String> refs = references(ev.trigger, "/E/" + ev.id, r);
            refs.addAll(references(ev.effect, "/E/" + ev.id, r));
            reportUndefined(refs, defined, "Event '" + ev.id + "'", "/E/" + ev.id, r);
        }

        Map<String, Integer> state = new HashMap<>(); // absent: unvisited, 1: on the path, 2: done
        for (String f : calls.keySet()) findCycles(f, calls, state, new ArrayDeque<>(), r);
    }

    private void reportUndefined(Set<String> refs, Set<String> defined, String where, String path, ValidationReport r) {
        for (String name : refs) {
            if (!defined.contains(name) && !name.equals(RESULT)) {
                r.error("VAR-UNDEF", where + " references undefined function '" + name + "'.", path);
            }
        }
    }

    /** Depth-first search over the call graph; reports each back edge as a cycle. */
    private void findCycles(String f, Map<String, Set<String>> calls, Map<String, Integer> state,
                            Deque<String> path, ValidationReport r) {
        if (state.containsKey(f)) return;
        state.put(f, 1);
        path.addLast(f);
        for (String g : calls.getOrDefault(f, Set.of())) {
            if (!calls.containsKey(g)) continue;
            if (state.get(g) == null) {
                findCycles(g, calls, state, path, r);
            } else if (state.get(g) == 1) {
                List<String> cycle = new ArrayList<>(path);
                cycle = cycle.subList(cycle.indexOf(g), cycle.size());
                r.error("F-CYCLE", "Cyclic function definition: " + String.join(" → ", cycle) + " → " + g + ".",
                        "/F/" + g);
            }
        }
        path.removeLast();
        state.put(f, 2);
    }

    /** Names of the functions an expression or effect refers to, in order of appearance. */
    private Set<String> references(JsonNode node, String path, ValidationReport r) {
        Set<String> out = new LinkedHashSet<>();
        collectReferences(node, path, out, r);
        return out;
    }

    private void collectReferences(JsonNode n, String path, Set<String> out, ValidationReport r) {
        if (n == null) return;
        if (n.isString()) {
            String s = n.asString();
            if (s.startsWith("$") && !Shorthand.isBinding(s)) {
                try {
                    collectReferences(Shorthand.parse(s), path, out, r);
                } catch (EngineException e) {
                    r.error("EXPR-REF", "Malformed reference '" + s + "': " + e.getMessage(), path);
                }
            }
        } else if (n.isArray()) {
            for (JsonNode child : n) collectReferences(child, path, out, r);
        } else if (n.isObject()) {
            JsonNode name = n.path("args").path(0);
            if (n.path("op").isString() && n.path("op").asString().equals("var") && name.isString()) {
                out.add(name.asString());
            }
            JsonNode cond = n.get("if"); // effect condition given as a function name
            if (cond != null && cond.isString() && !cond.asString().startsWith("$")) out.add(cond.asString());
            for (Map.Entry<String, JsonNode> e : n.properties()) collectReferences(e.getValue(), path, out, r);
        }
    }

    /* ----- helpers ------------------------------------------------------- */

    private interface IdGetter<T> {
        String get(T item);
    }

    private <T> Map<String, T> indexById(Iterable<T> items, IdGetter<T> getter,
                                         ValidationReport r, String path) {
        Map<String, T> ix = new HashMap<>();
        if (items == null) return ix;
        for (T item : items) {
            String id = getter.get(item);
            if (id == null || id.isBlank()) {
                r.error("ID-001", "Element with missing id.", path);
                continue;
            }
            if (ix.put(id, item) != null) {
                r.error("ID-002", "Duplicate id '" + id + "'.", path);
            }
        }
        return ix;
    }
}

