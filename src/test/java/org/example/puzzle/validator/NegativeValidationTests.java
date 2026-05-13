package org.example.puzzle.validator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Negative tests — make sure the validator detects the most common
 * specification mistakes.
 */
class NegativeValidationTests {

    private static final String BASE_OK = """
            {
              "id":"x","name":"x",
              "PT":[{"id":"T"}],
              "P": [{"id":"P1","type":"T"}],
              "OT":[{"id":"X"}],
              "O": [{"id":"O1","type":"X"}],
              "InitDisp":[{"object":"O1","position":"P1"}],
              "A": [{"id":"a","kind":"click"}],
              "R": [{"id":"r","action":"a"}],
              "FC": true
            }
            """;

    @Test
    void baselineIsValid() throws Exception {
        ValidationReport r = new PuzzleValidator().validate(BASE_OK);
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.issues());
    }

    @Test
    void detectsMissingFinalCondition() throws Exception {
        String bad = BASE_OK.replace("\"FC\": true", "\"FC\": null");
        ValidationReport r = new PuzzleValidator().validate(bad);
        assertFalse(r.isValid());
    }

    @Test
    void detectsUnknownPositionTypeReference() throws Exception {
        String bad = BASE_OK.replace("\"type\":\"T\"}]", "\"type\":\"BOGUS\"}]");
        ValidationReport r = new PuzzleValidator().validate(bad);
        assertFalse(r.isValid());
        assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("REF-PT")));
    }

    @Test
    void detectsCapacityOverflow() throws Exception {
        String bad = """
                {
                  "id":"x","name":"x",
                  "PT":[{"id":"T"}],
                  "P": [{"id":"P1","type":"T","capacity":1}],
                  "OT":[{"id":"X"}],
                  "O": [{"id":"O1","type":"X"},{"id":"O2","type":"X"}],
                  "InitDisp":[
                    {"object":"O1","position":"P1"},
                    {"object":"O2","position":"P1"}
                  ],
                  "A":[{"id":"a","kind":"click"}],
                  "R":[{"id":"r","action":"a"}],
                  "FC": true
                }
                """;
        ValidationReport r = new PuzzleValidator().validate(bad);
        assertFalse(r.isValid());
        assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("CAP-001")));
    }

    @Test
    void detectsDuplicateIds() throws Exception {
        String bad = BASE_OK.replace(
                "\"P\": [{\"id\":\"P1\",\"type\":\"T\"}]",
                "\"P\": [{\"id\":\"P1\",\"type\":\"T\"},{\"id\":\"P1\",\"type\":\"T\"}]");
        ValidationReport r = new PuzzleValidator().validate(bad);
        assertFalse(r.isValid());
        assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("ID-002")));
    }
}

