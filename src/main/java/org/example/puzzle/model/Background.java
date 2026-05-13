package org.example.puzzle.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Background {
    public String id;
    public String image;
    public boolean visible = true;
    public double opacity = 1.0;
}

