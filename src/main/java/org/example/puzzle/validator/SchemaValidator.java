package org.example.puzzle.validator;

import tools.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Stage 1 — JSON Schema (Draft 2020-12) structural validation.
 *
 * <p>Implemented on top of {@code com.networknt:json-schema-validator} 2.x, which
 * exposes the modern {@link SchemaRegistry} / {@link Schema} / {@link Error} API
 * (the legacy {@code JsonSchemaFactory} / {@code JsonSchema} / {@code ValidationMessage}
 * classes were removed in 2.0.0).</p>
 */
public final class SchemaValidator {

    private static final String SCHEMA_RESOURCE = "/schema/closed-puzzle.schema.json";

    private final Schema schema;

    public SchemaValidator() throws IOException {
        SchemaRegistry registry =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        try (InputStream in = SchemaValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IOException("Schema resource not found: " + SCHEMA_RESOURCE);
            }
            this.schema = registry.getSchema(in);
        }
    }

    public void validate(JsonNode document, ValidationReport report) {
        List<Error> errors = schema.validate(document);
        for (Error e : errors) {
            String code = e.getKeyword() == null ? "ASSERTION" : e.getKeyword().toUpperCase();
            String path = e.getInstanceLocation() == null ? null : e.getInstanceLocation().toString();
            report.error("SCHEMA-" + code, e.getMessage(), path);
        }
    }
}
