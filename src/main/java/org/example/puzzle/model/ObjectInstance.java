package org.example.puzzle.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ObjectInstance {
    public String id;
    public String type;
    public String title;
    public String content;
    public boolean visible = true;
    public boolean focused = false;
    public double rotation = 0;
    public int zOrder = 0;
    public String color;
    public Double size;
}

