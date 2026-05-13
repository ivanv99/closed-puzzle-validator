package org.example.puzzle.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Event {
    public String id;
    public JsonNode trigger;
    public List<String> target = new ArrayList<>();
    public JsonNode effect;
}

