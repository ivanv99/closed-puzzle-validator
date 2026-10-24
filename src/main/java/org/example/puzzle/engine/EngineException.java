package org.example.puzzle.engine;

/**
 * Raised when an action or effect cannot be executed against the current
 * state (unknown id, violated capacity, undefined function, ...). The engine
 * catches it, rolls the step back and reports the action as rejected.
 */
public class EngineException extends RuntimeException {

    public EngineException(String message) {
        super(message);
    }
}
