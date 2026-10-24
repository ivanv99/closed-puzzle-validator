package org.example.puzzle.engine;

import java.util.List;

/**
 * Outcome of one engine step (a player action or the passing of time).
 *
 * @param accepted     false if the action was not allowed; the state is then unchanged
 * @param message      reason for rejection, or {@code null}
 * @param rules        ids of the rules that fired, in order
 * @param events       ids of the events that fired (rule-fired and triggered), in order
 * @param sounds       sounds requested by {@code playSound}, for the client to play
 * @param finished     whether FC holds after the step
 * @param result       value of Res once the game is finished, otherwise {@code null}
 * @param nextTimerInMs time until the next deferred effect, or {@code null} if none is pending
 */
public record StepResult(boolean accepted,
                         String message,
                         List<String> rules,
                         List<String> events,
                         List<String> sounds,
                         boolean finished,
                         Object result,
                         Long nextTimerInMs) {
}
