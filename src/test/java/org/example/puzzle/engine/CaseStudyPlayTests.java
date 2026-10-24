package org.example.puzzle.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plays each of the four case-study puzzles through {@link GameEngine} with
 * concrete moves: a winning line, plus moves the rules must reject.
 */
class CaseStudyPlayTests {

    /* ----- Concept Matching: Polygons & Stars ----------------------------- */

    @Test
    void matchingStarsCorrectSolution() throws IOException {
        GameEngine g = load("/examples/matching-stars.json");
        assertFalse(g.isFinished());

        assertAccepted(move(g, "O1", "P1"));
        assertAccepted(move(g, "O2", "P2"));
        StepResult last = move(g, "O3", "P3");

        assertAccepted(last);
        assertTrue(last.finished());
        assertEquals(true, last.result());
        assertEquals(List.of("R-move"), last.rules());
        assertEquals(List.of("E-take", "E-place"), last.events());
    }

    @Test
    void matchingStarsWrongSolutionEndsWithFalseResult() throws IOException {
        GameEngine g = load("/examples/matching-stars.json");
        move(g, "O1", "P2");
        move(g, "O2", "P1");
        StepResult last = move(g, "O3", "P3");

        assertTrue(last.finished());
        assertEquals(false, last.result());
    }

    @Test
    void matchingStarsRejectsMoveOntoOccupiedPosition() throws IOException {
        GameEngine g = load("/examples/matching-stars.json");
        assertAccepted(move(g, "O1", "P1"));

        StepResult r = move(g, "O2", "P1");

        assertFalse(r.accepted());
        assertEquals("P5", g.state().location("O2"), "rejected move must not change the state");
    }

    /* ----- Memory game ---------------------------------------------------- */

    @Test
    void memoryNonMatchingPairIsHiddenAfterDelay() throws IOException {
        GameEngine g = load("/examples/memory-game.json");

        assertAccepted(click(g, "ClickCard", "O01"));
        StepResult second = click(g, "ClickCard", "O03");   // Triangle vs Square
        assertAccepted(second);
        assertEquals(List.of("E-flip", "E-checkPair"), second.events());
        assertEquals(1500L, second.nextTimerInMs());
        assertTrue(visible(g, "O01") && visible(g, "O03"));

        assertFalse(click(g, "ClickCard", "O05").accepted(), "a third card cannot be opened");

        g.advanceTime(1000);
        assertTrue(visible(g, "O01"), "still open before the delay elapses");
        StepResult tick = g.advanceTime(500);
        assertNull(tick.nextTimerInMs());
        assertFalse(visible(g, "O01") || visible(g, "O03"));
        assertEquals(0L, g.function("openCount"));
    }

    @Test
    void memoryAllPairsWin() throws IOException {
        GameEngine g = load("/examples/memory-game.json");

        StepResult last = null;
        for (int i = 1; i <= 12; i += 2) {
            assertAccepted(click(g, "ClickCard", String.format("O%02d", i)));
            last = click(g, "ClickCard", String.format("O%02d", i + 1));
            assertAccepted(last);
            assertEquals(List.of("WellDone.wav"), last.sounds());
            assertNull(last.nextTimerInMs(), "a matched pair stays open");
        }

        assertTrue(last.finished());
        assertEquals(6L, last.result());
        assertFalse(click(g, "ClickCard", "O01").accepted(), "no actions after the end");
    }

    @Test
    void memoryRejectsClickOnOpenCard() throws IOException {
        GameEngine g = load("/examples/memory-game.json");
        assertAccepted(click(g, "ClickCard", "O01"));

        assertFalse(click(g, "ClickCard", "O01").accepted());
    }

    /* ----- 15-puzzle ------------------------------------------------------ */

    @Test
    void fifteenPuzzleSolves() throws IOException {
        GameEngine g = load("/examples/fifteen-puzzle.json");
        // Shortest solution for the initial disposition (found by BFS); tile titles in order.
        int[] tiles = {12, 15, 14, 13, 9, 5, 1, 2, 3, 4, 8, 12};

        StepResult last = null;
        for (int t : tiles) {
            last = slide(g, t);
            assertAccepted(last);
        }

        assertTrue(last.finished());
        assertEquals(true, last.result());
    }

    @Test
    void fifteenPuzzleRejectsNonAdjacentSlide() throws IOException {
        GameEngine g = load("/examples/fifteen-puzzle.json");

        StepResult r = g.perform("Slide", Map.of("obj", "T05", "dst", "C33"));

        assertFalse(r.accepted());
        assertEquals("C00", g.state().location("T05"));
    }

    /* ----- Lights Out ----------------------------------------------------- */

    @Test
    void lightsOutSolves() throws IOException {
        GameEngine g = load("/examples/lights-out.json");
        // For this X-shaped start the unique solution is to press every light once.
        String[] lights = {"L00", "L01", "L02", "L10", "L11", "L12", "L20", "L21", "L22"};

        StepResult last = null;
        for (String l : lights) {
            assertFalse(g.isFinished());
            last = click(g, "Press", l);
            assertAccepted(last);
        }

        assertTrue(last.finished());
        assertEquals(true, last.result());
    }

    @Test
    void lightsOutCornerTogglesOnlyExistingNeighbours() throws IOException {
        GameEngine g = load("/examples/lights-out.json");

        assertAccepted(click(g, "Press", "L00"));

        assertEquals(false, g.state().property("L00", "focused"));
        assertEquals(true, g.state().property("L01", "focused"));
        assertEquals(true, g.state().property("L10", "focused"));
        assertEquals(true, g.state().property("L11", "focused"), "diagonal light is untouched");
    }

    /* ----- helpers ------------------------------------------------------- */

    private static StepResult move(GameEngine g, String obj, String dst) {
        return g.perform("Move", Map.of("obj", obj, "dst", dst));
    }

    private static StepResult click(GameEngine g, String action, String target) {
        return g.perform(action, Map.of("target", target));
    }

    /** Slides the tile with the given title into the empty cell. */
    private static StepResult slide(GameEngine g, int title) {
        String tile = String.format("T%02d", title);
        String empty = g.model().P.stream()
                .map(p -> p.id)
                .filter(p -> g.state().objectsAt(p).isEmpty())
                .findFirst().orElseThrow();
        return g.perform("Slide", Map.of("obj", tile, "dst", empty));
    }

    private static boolean visible(GameEngine g, String id) {
        return Values.truthy(g.state().property(id, "visible"));
    }

    private static void assertAccepted(StepResult r) {
        assertTrue(r.accepted(), () -> "action rejected: " + r.message());
    }

    private GameEngine load(String resource) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, "resource not found: " + resource);
            return GameEngine.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
