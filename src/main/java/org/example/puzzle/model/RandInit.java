package org.example.puzzle.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Extension of InitDisp (outside the original model tuple): optional
 * randomisation of the initial disposition. Without it (or when the
 * engine is started without a seed) the game starts exactly as in InitDisp.
 *
 * <ul>
 *   <li>{@code shuffle} — groups of objects whose InitDisp positions are
 *       permuted at random (e.g. the cards of Memory). Capacities are kept,
 *       because the multiset of positions does not change.</li>
 *   <li>{@code scramble} — a number of random <i>accepted</i> player actions
 *       applied to InitDisp (e.g. slides in the 15-puzzle). Since every step is
 *       a legal move, a solvable InitDisp stays solvable.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RandInit {
    public List<List<String>> shuffle = new ArrayList<>();
    public Scramble scramble;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Scramble {
        public int steps;
        /** Actions to draw from; empty means all actions of A. */
        public List<String> actions = new ArrayList<>();
    }
}
