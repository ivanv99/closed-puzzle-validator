package org.example.puzzle.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Rule {
    public String id;
    public String action;
    public JsonNode when;
    public List<String> fire = new ArrayList<>();
}

