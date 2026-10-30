package org.example.puzzle.engine;

import org.example.puzzle.validator.PuzzleValidator;
import org.example.puzzle.validator.ValidationReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plays the games in {@code models/}, which are not bundled with the engine and
 * were described only after the engine was written: they are loaded from a file,
 * exactly as the Unity player's "model from file" does.
 */
class NewGamesPlayTests {

    @ParameterizedTest
    @ValueSource(strings = {"peg-solitaire", "tower-of-hanoi"})
    void modelIsValid(String name) throws Exception {
        ValidationReport r = new PuzzleValidator().validate(read(name));
        assertTrue(r.isValid() && r.issues().isEmpty(), () -> "issues: " + r.issues());
    }

    /* ----- Peg Solitaire --------------------------------------------------- */

    @Test
    void pegSolitaireWinsWithOnePegInTheCentre() throws IOException {
        GameEngine g = load("peg-solitaire");
        // A winning line found by depth-first search: {peg, hole it jumps into}.
        String[][] jumps = {{"Peg05", "H33"}, {"Peg08", "H23"}, {"Peg01", "H22"}, {"Peg03", "H02"},
                {"Peg08", "H21"}, {"Peg07", "H22"}, {"Peg11", "H04"}, {"Peg13", "H24"}, {"Peg16", "H12"},
                {"Peg03", "H22"}, {"Peg14", "H32"}, {"Peg14", "H12"}, {"Peg17", "H14"}, {"Peg11", "H24"},
                {"Peg19", "H34"}, {"Peg19", "H14"}, {"Peg27", "H32"}, {"Peg20", "H42"}, {"Peg20", "H22"},
                {"Peg14", "H32"}, {"Peg14", "H34"}, {"Peg24", "H24"}, {"Peg19", "H34"}, {"Peg26", "H44"},
                {"Peg23", "H45"}, {"Peg32", "H44"}, {"Peg19", "H54"}, {"Peg30", "H64"}, {"Peg30", "H44"},
                {"Peg23", "H43"}, {"Peg28", "H33"}};

        StepResult last = null;
        for (String[] j : jumps) {
            assertFalse(g.isFinished());
            last = jump(g, j[0], j[1]);
            assertAccepted(last);
        }

        assertTrue(last.finished());
        assertEquals(true, last.result());
        assertEquals(1L, g.function("pegsLeft"));
        assertEquals("H33", g.state().location("Peg28"));
    }

    @Test
    void pegSolitaireEndsWhenNoJumpIsLeft() throws IOException {
        GameEngine g = load("peg-solitaire");
        String[][] jumps = {{"Peg05", "H33"}, {"Peg23", "H23"}, {"Peg31", "H43"}, {"Peg18", "H33"},
                {"Peg16", "H34"}, {"Peg14", "H32"}};

        StepResult last = null;
        for (String[] j : jumps) last = jump(g, j[0], j[1]);

        assertTrue(last.finished(), "no peg can jump any more");
        assertEquals(false, last.result());
        assertEquals(26L, g.function("pegsLeft"));
    }

    @Test
    void pegSolitaireRemovesTheJumpedPegAndRejectsOtherMoves() throws IOException {
        GameEngine g = load("peg-solitaire");

        assertFalse(jump(g, "Peg01", "H33").accepted(), "not two holes away in a straight line");
        assertFalse(jump(g, "Peg02", "H23").accepted(), "the target hole is occupied");

        assertAccepted(jump(g, "Peg05", "H33"));             // H13 over H23 into H33
        assertNull(g.state().location("Peg10"), "the jumped peg is removed");

        assertFalse(jump(g, "Peg02", "H23").accepted(), "nothing to jump over (H13 is empty now)");
        assertFalse(jump(g, "Peg05", "H13").accepted(), "nothing to jump over (H23 is empty now)");
    }

    /* ----- Tower of Hanoi -------------------------------------------------- */

    @Test
    void towerOfHanoiSolvesInFifteenMoves() throws IOException {
        GameEngine g = load("tower-of-hanoi");

        StepResult last = hanoi(g, 4, 'A', 'C', 'B');

        assertTrue(last.finished());
        assertEquals(15L, last.result(), "Res is the number of moves; 2^4 - 1 is optimal");
    }

    @Test
    void towerOfHanoiRejectsIllegalMoves() throws IOException {
        GameEngine g = load("tower-of-hanoi");

        assertFalse(move(g, "D2", "B1").accepted(), "D2 is covered by D1");
        assertFalse(move(g, "D1", "B2").accepted(), "a disk cannot float above an empty cell");

        assertAccepted(move(g, "D1", "C1"));
        assertFalse(move(g, "D2", "C2").accepted(), "a larger disk cannot go on a smaller one");
        assertEquals("A3", g.state().location("D2"));

        assertAccepted(move(g, "D2", "B1"));
        assertAccepted(move(g, "D1", "B2"));
        assertEquals(3L, g.function("result"));
    }

    /* ----- helpers ------------------------------------------------------- */

    /** Moves n disks from rod {@code from} to rod {@code to}; returns the last step. */
    private static StepResult hanoi(GameEngine g, int n, char from, char to, char via) {
        if (n == 0) return null;
        hanoi(g, n - 1, from, via, to);
        StepResult r = move(g, "D" + n, lowestEmptyCell(g, to));
        assertAccepted(r);
        StepResult rest = hanoi(g, n - 1, via, to, from);
        return rest == null ? r : rest;
    }

    private static String lowestEmptyCell(GameEngine g, char rod) {
        for (int level = 1; ; level++) {
            String cell = "" + rod + level;
            if (g.state().objectsAt(cell).isEmpty()) return cell;
        }
    }

    private static StepResult jump(GameEngine g, String peg, String hole) {
        return g.perform("Jump", Map.of("obj", peg, "dst", hole));
    }

    private static StepResult move(GameEngine g, String disk, String cell) {
        return g.perform("Move", Map.of("obj", disk, "dst", cell));
    }

    private static void assertAccepted(StepResult r) {
        assertTrue(r.accepted(), () -> "action rejected: " + r.message());
    }

    private static String read(String name) throws IOException {
        return Files.readString(Path.of("models", name + ".json"));
    }

    private static GameEngine load(String name) throws IOException {
        return GameEngine.fromJson(read(name));
    }
}
