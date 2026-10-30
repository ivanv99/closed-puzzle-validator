package org.example.puzzle.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ObjectType {
    public String id;
    public String description;
    /** Presentation hint for clients, e.g. {@code star-5}; ignored by the engine. */
    public String shape;
    public boolean movable = true;
    public boolean rotatable = false;
    public boolean clickable = true;
}

