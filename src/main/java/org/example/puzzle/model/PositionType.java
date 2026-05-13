package org.example.puzzle.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PositionType {
    public String id;
    public String description;
    public Integer defaultCapacity = 1;
}

