package org.example.puzzle.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Execution-semantics corner cases on a tiny two-cell model.
 */
class EngineSemanticsTests {

    private static final String MODEL = """
            {
              "id": "tiny", "name": "Tiny",
              "PT": [{"id": "Cell"}],
              "P": [{"id": "A", "type": "Cell", "x": 0, "y": 0}, {"id": "B", "type": "Cell", "x": 1, "y": 0}],
              "OT": [{"id": "Box"}, {"id": "Wall", "movable": false}],
              "O": [{"id": "box", "type": "Box"}, {"id": "wall", "type": "Wall"}],
              "InitDisp": [{"object": "box", "position": "A", "overrides": {"title": "first"}},
                           {"object": "wall", "position": "B"}],
              "A": [{"id": "Push", "kind": "move"}, {"id": "Poke", "kind": "click"}],
              "R": [{"id": "R-push", "action": "Push", "fire": ["E-move"]},
                    {"id": "R-poke", "action": "Poke", "fire": ["E-poke"]}],
              "E": [{"id": "E-move", "effect": {"op": "move", "args": ["$obj", "$dst"]}},
                    {"id": "E-poke", "effect": {"op": "compose", "steps": [
                        {"op": "incrementResult", "by": 2},
                        {"op": "setProperty", "property": "title", "value": "$neighbour(E).type"}]}}],
              "F": [{"id": "loopA", "expression": {"op": "var", "args": ["loopB"]}},
                    {"id": "loopB", "expression": {"op": "var", "args": ["loopA"]}}],
              "FC": false
            }
            """;

    @Test
    void initDispOverridesAreApplied() {
        GameEngine g = GameEngine.fromJson(MODEL);
        assertEquals("first", g.state().property("box", "title"));
    }

    @Test
    void shorthandReferenceReadsNeighbourProperty() {
        GameEngine g = GameEngine.fromJson(MODEL);

        assertTrue(g.perform("Poke", Map.of("target", "box")).accepted());

        assertEquals("Wall", g.state().property("box", "title"));
        assertEquals(2L, g.function("result"));
    }

    @Test
    void failingEffectRollsBackTheWholeStep() {
        GameEngine g = GameEngine.fromJson(MODEL);

        // The rule has no guard, so the move effect runs: it lifts the box off A,
        // then fails to put it on B (capacity 1, occupied by the wall).
        StepResult r = g.perform("Push", Map.of("obj", "box", "dst", "B"));

        assertFalse(r.accepted());
        assertTrue(r.message().contains("full"), r.message());
        assertEquals("A", g.state().location("box"), "object must be back on its original position");
    }

    @Test
    void immovableTypeRejectsMove() {
        GameEngine g = GameEngine.fromJson(MODEL);

        StepResult r = g.perform("Push", Map.of("obj", "wall", "dst", "A"));

        assertFalse(r.accepted());
        assertTrue(r.message().contains("do not support"), r.message());
    }

    @Test
    void cyclicFunctionsAreDetected() {
        GameEngine g = GameEngine.fromJson(MODEL);

        EngineException ex = assertThrows(EngineException.class, () -> g.function("loopA"));
        assertTrue(ex.getMessage().contains("loopA → loopB → loopA"), ex.getMessage());
    }

    @Test
    void shorthandDesugarsToAst() {
        assertEquals("""
                {"op":"not","args":[{"op":"checkProperty","args":[{"op":"neighbour","args":["$target","N"]},"focused"]}]}""",
                Shorthand.parse("$not($neighbour(N).focused)").toString());
    }
}
