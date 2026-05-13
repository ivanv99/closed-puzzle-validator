package org.example.puzzle.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Placement {
    public String object;
    public String position;
    public Map<String, Object> overrides;
}

