package org.example.puzzle.validator;

import tools.jackson.databind.JsonNode;
import org.example.puzzle.model.*;

import java.util.HashMap;
import java.util.HashSet;
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

