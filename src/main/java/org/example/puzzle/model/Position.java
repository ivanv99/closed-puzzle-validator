package org.example.puzzle.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Position {
    public String id;
    public String type;
    public String title;
    public String content;
    public boolean visible = true;
    public boolean focused = false;
    public Integer capacity = 1;
    public Double x;
    public Double y;
    public Double z;
}

