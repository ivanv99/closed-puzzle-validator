package org.example.puzzle.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Function {
    public String id;
    public JsonNode expression;
}

