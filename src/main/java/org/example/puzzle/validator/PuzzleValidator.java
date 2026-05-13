package org.example.puzzle.validator;

import tools.jackson.databind.JsonNode;
import org.example.puzzle.model.ClosedPuzzle;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Two-stage façade combining {@link SchemaValidator} (structural) and
 * {@link SemanticValidator} (cross-references, capacity, close-ended
 * properties).
 */
public final class PuzzleValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SchemaValidator schema;
    private final SemanticValidator semantic = new SemanticValidator();

    public PuzzleValidator() throws IOException {
        this.schema = new SchemaValidator();
    }

    public ValidationReport validate(JsonNode tree) {
        ValidationReport report = new ValidationReport();
        schema.validate(tree, report);                       // stage 1: structural
        if (report.isValid()) {
            ClosedPuzzle puzzle = MAPPER.treeToValue(tree, ClosedPuzzle.class);
            semantic.validate(puzzle, report);               // stage 2: semantic
        }
        return report;
    }

    public ValidationReport validate(Path file) throws IOException {
        return validate(MAPPER.readTree(Files.readAllBytes(file)));
    }

    public ValidationReport validate(String json) {
        return validate(MAPPER.readTree(json));
    }
}

