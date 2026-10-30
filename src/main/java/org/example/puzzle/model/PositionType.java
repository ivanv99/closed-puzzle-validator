package org.example.puzzle.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PositionType {
    public String id;
    public String description;
    /** Presentation hint for clients, e.g. {@code polygon-5}; ignored by the engine. */
    public String shape;
    public Integer defaultCapacity = 1;
}

