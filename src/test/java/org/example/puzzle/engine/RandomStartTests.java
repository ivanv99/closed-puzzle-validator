package org.example.puzzle.engine;

import org.example.puzzle.model.ClosedPuzzle;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code RandInit}: shuffled and scrambled starts are reproducible from the
 * seed, keep the board consistent and do not leave the game solved.
 */
class RandomStartTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void withoutSeedTheGameStartsFromInitDisp() throws IOException {
        GameEngine g = new GameEngine(model("memory-game"));

        assertNull(g.seed());
        assertEquals("P11", g.state().location("O01"));
    }

    @Test
    void sameSeedGivesSameStartAndSeedsDiffer() throws IOException {
        for (String name : List.of("memory-game", "matching-stars", "fifteen-puzzle", "lights-out")) {
            ClosedPuzzle m = model(name);
            assertEquals(new GameEngine(m, 7L).state().snapshot(), new GameEngine(m, 7L).state().snapshot(), name);

            Set<Object> starts = new HashSet<>();
            for (long seed = 1; seed <= 10; seed++) starts.add(new GameEngine(m, seed).state().snapshot());
            assertTrue(starts.size() > 1, name + ": 10 seeds gave the same start");
        }
    }

    @Test
    void shuffleOnlyPermutesThePositionsOfTheGroup() throws IOException {
        ClosedPuzzle m = model("memory-game");
        GameEngine canonical = new GameEngine(m);

        for (long seed = 1; seed <= 20; seed++) {
            GameEngine g = new GameEngine(m, seed);
            assertEquals(seed, g.seed());
            assertEquals(positions(canonical), positions(g), "positions are a permutation of InitDisp");
            for (String obj : g.state().placedObjects()) {
                assertEquals(false, g.state().property(obj, "visible"), "cards start face down");
            }
            assertFalse(g.isFinished());
        }
    }

    @Test
    void scrambledFifteenPuzzleStaysSolvable() throws IOException {
        ClosedPuzzle m = model("fifteen-puzzle");
        int parity = solvabilityParity(new GameEngine(m));

        for (long seed = 1; seed <= 20; seed++) {
            GameEngine g = new GameEngine(m, seed);
            assertEquals(parity, solvabilityParity(g), "seed " + seed);
            assertFalse(g.isFinished(), "seed " + seed);
            assertEquals(0, g.clock());
            assertEquals(0.0, g.state().counter());
            assertEquals(15, new HashSet<>(positions(g)).size(), "one tile per cell");
        }
    }

    @Test
    void scrambledLightsOutIsNotSolvedAtTheStart() throws IOException {
        ClosedPuzzle m = model("lights-out");
        for (long seed = 1; seed <= 50; seed++) {
            assertFalse(new GameEngine(m, seed).isFinished(), "seed " + seed);
        }
    }

    /* ----- helpers ------------------------------------------------------- */

    /** Positions of the placed objects, sorted. */
    private static List<String> positions(GameEngine g) {
        List<String> p = new ArrayList<>();
        for (String obj : g.state().placedObjects()) p.add(g.state().location(obj));
        p.sort(null);
        return p;
    }

    /**
     * Invariant of slides on a 4×4 board: the parity of the permutation of the
     * 16 cells (blank included) plus the parity of the blank's distance from
     * the bottom-right corner. Equal parity means reachable from each other.
     */
    private static int solvabilityParity(GameEngine g) {
        List<String> cells = g.model().P.stream().map(p -> p.id).toList();
        int[] perm = new int[cells.size()];
        int blank = -1;
        for (int i = 0; i < cells.size(); i++) {
            List<String> on = g.state().objectsAt(cells.get(i));
            if (on.isEmpty()) {
                blank = i;
                perm[i] = 16;
            } else {
                perm[i] = Integer.parseInt((String) g.state().property(on.getFirst(), "title"));
            }
        }
        int inversions = 0;
        for (int i = 0; i < perm.length; i++) {
            for (int j = i + 1; j < perm.length; j++) if (perm[i] > perm[j]) inversions++;
        }
        int distance = (3 - blank / 4) + (3 - blank % 4);
        return (inversions + distance) % 2;
    }

    private ClosedPuzzle model(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/examples/" + name + ".json")) {
            assertNotNull(in, "example not found: " + name);
            return MAPPER.readValue(in, ClosedPuzzle.class);
        }
    }
}
