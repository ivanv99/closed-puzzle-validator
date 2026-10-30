package org.example.puzzle.validator;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void acceptsKnownShapesAndRejectsUnknownOnes() throws Exception {
        String shaped = BASE_OK
                .replace("\"PT\":[{\"id\":\"T\"}]", "\"PT\":[{\"id\":\"T\",\"shape\":\"polygon-6\"}]")
                .replace("\"OT\":[{\"id\":\"X\"}]", "\"OT\":[{\"id\":\"X\",\"shape\":\"star-5\"}]");
        assertTrue(new PuzzleValidator().validate(shaped).isValid());

        ValidationReport r = new PuzzleValidator().validate(shaped.replace("star-5", "star-42"));
        assertFalse(r.isValid());
    }

    @Test
    void detectsBadRandInitReferences() throws Exception {
        String randomised = BASE_OK.replace("\"FC\": true",
                "\"FC\": true, \"RandInit\": {\"shuffle\": [[\"O1\", \"O9\"]], \"scramble\": {\"steps\": 3, \"actions\": [\"zz\"]}}");
        ValidationReport r = new PuzzleValidator().validate(randomised);

        assertFalse(r.isValid());
        assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("RAND-OBJ")));
        assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("RAND-ACT")));
    }

    @Test
    void detectsUndefinedFunctionReferences() throws Exception {
        String undefined = BASE_OK.replace("\"FC\": true",
                "\"FC\": {\"op\":\"var\",\"args\":[\"solved\"]}, \"Res\": {\"op\":\"var\",\"args\":[\"result\"]}");
        ValidationReport r = new PuzzleValidator().validate(undefined);

        assertFalse(r.isValid());
        assertEquals(1, r.issues().stream().filter(i -> i.code().equals("VAR-UNDEF")).count(),
                () -> "only 'solved' is undefined ('result' is built in): " + r.issues());
    }

    @Test
    void detectsUndefinedConditionsAndMalformedReferencesInEffects() throws Exception {
        String bad = BASE_OK
                .replace("\"R\": [{\"id\":\"r\",\"action\":\"a\"}]", "\"R\": [{\"id\":\"r\",\"action\":\"a\",\"fire\":[\"e\"]}]")
                .replace("\"FC\": true", "\"FC\": true, \"E\": [{\"id\":\"e\",\"effect\":{\"op\":\"setProperty\","
                        + "\"property\":\"on\",\"value\":\"$not($target.on\",\"if\":\"matched\"}}]");
        ValidationReport r = new PuzzleValidator().validate(bad);

        assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("VAR-UNDEF") && i.message().contains("matched")));
        assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("EXPR-REF")));
    }

    @Test
    void detectsCyclicFunctions() throws Exception {
        String cyclic = BASE_OK.replace("\"FC\": true", """
                "F": [
                  {"id":"a","expression":{"op":"var","args":["b"]}},
                  {"id":"b","expression":{"op":"not","args":[{"op":"var","args":["a"]}]}},
                  {"id":"c","expression":{"op":"var","args":["c"]}}
                ],
                "FC": {"op":"var","args":["a"]}""");
        ValidationReport r = new PuzzleValidator().validate(cyclic);

        List<String> cycles = r.issues().stream().filter(i -> i.code().equals("F-CYCLE")).map(i -> i.message()).toList();
        assertEquals(2, cycles.size(), () -> "expected a → b → a and c → c: " + r.issues());
        assertTrue(cycles.stream().anyMatch(m -> m.contains("a → b → a")));
        assertTrue(cycles.stream().anyMatch(m -> m.contains("c → c")));
    }
}

