package org.example.puzzle.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ObjectType {
    public String id;
    public String description;
    public boolean movable = true;
    public boolean rotatable = false;
    public boolean clickable = true;
}

