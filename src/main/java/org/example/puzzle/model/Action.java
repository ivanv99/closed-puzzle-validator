package org.example.puzzle.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Action {
    public String id;
    /**
     * click | doubleClick | move | rotate | flip | input | selectSequence | timeout | custom
     */
    public String kind;
    /**
     * player | environment | npc
     */
    public String origin = "player";
    public Map<String, Object> params;
}

