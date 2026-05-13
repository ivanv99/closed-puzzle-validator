package org.example.puzzle.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Root document of a close-ended puzzle game description.
 *
 * <p>Implements the formal model:
 * <pre>
 *     ClosedPuzzle = ⟨ CB, PT, P, OT, O, InitDisp, A, R, E, F, FC, Res ⟩
 * </pre>
 * Field names mirror the model so the JSON serialisation stays close to the
 * mathematical notation used in the report.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClosedPuzzle {
    public String id;
    public String name;
    public String description;

    public List<Background> CB = new ArrayList<>();
    public List<PositionType> PT = new ArrayList<>();
    public List<Position> P = new ArrayList<>();
    public List<ObjectType> OT = new ArrayList<>();
    public List<ObjectInstance> O = new ArrayList<>();
    public List<Placement> InitDisp = new ArrayList<>();
    public List<Action> A = new ArrayList<>();
    public List<Rule> R = new ArrayList<>();
    public List<Event> E = new ArrayList<>();
    public List<Function> F = new ArrayList<>();

    /**
     * Final condition – AST expression (boolean).
     */
    public JsonNode FC;

    /**
     * Result expression.
     */
    public JsonNode Res;
}

