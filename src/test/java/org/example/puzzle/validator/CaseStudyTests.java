package org.example.puzzle.validator;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates each of the four case-study puzzle descriptions used in the
 * internship report. A passing test means the file is structurally and
 * semantically conformant to the formal model and its JSON Schema.
 */
class CaseStudyTests {

    @Test
    void memoryGame() throws Exception {
        assertValid("/examples/memory-game.json");
    }

    @Test
    void matchingStars() throws Exception {
        assertValid("/examples/matching-stars.json");
    }

    @Test
    void fifteenPuzzle() throws Exception {
        assertValid("/examples/fifteen-puzzle.json");
    }

    @Test
    void lightsOut() throws Exception {
        assertValid("/examples/lights-out.json");
    }

    /* ----- helpers ------------------------------------------------------- */

    private void assertValid(String classpathResource) throws IOException {
        String json = readResource(classpathResource);
        PuzzleValidator validator = new PuzzleValidator();
        ValidationReport report = validator.validate(json);
        if (!report.isValid()) {
            StringBuilder sb = new StringBuilder("Unexpected errors in ").append(classpathResource).append(":\n");
            for (ValidationReport.Issue i : report.issues()) {
                sb.append("  ").append(i).append('\n');
            }
            fail(sb.toString());
        }
        assertTrue(report.isValid(), "case study must be valid: " + classpathResource);
    }

    private String readResource(String resource) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, "resource not found: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

