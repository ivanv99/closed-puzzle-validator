package org.example.puzzle.engine;

import org.example.puzzle.model.Event;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies event effects to a {@link GameState}.
 *
 * <p>Every effect step may carry these optional attributes:
 * <ul>
 *   <li>{@code if}: condition (expression, or the name of a function in F);
 *       the step is skipped when it is false;</li>
 *   <li>{@code delayMs}: the step is not applied now but handed back to the
 *       engine as a deferred effect (its {@code if} is decided now);</li>
 *   <li>{@code target}: reference or list of references; defaults to the
 *       event's {@code target} list, then to the action's {@code $target};</li>
 *   <li>{@code where}: filter evaluated per target with {@code $it} bound.</li>
 * </ul>
 */
final class EffectExecutor {

    /** Effect postponed by {@code delayMs}. */
    record Deferred(long delayMs, JsonNode effect, Event event, Map<String, Object> bindings) {
    }

    /** What happened during one engine step; filled in by the executor and the engine. */
    static final class StepLog {
        final List<String> rules = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<String> sounds = new ArrayList<>();
        final List<Deferred> deferred = new ArrayList<>();
    }

    private final ExpressionEvaluator eval;

    EffectExecutor(ExpressionEvaluator eval) {
        this.eval = eval;
    }

    void fire(Event event, Map<String, Object> bindings, GameState state, StepLog log) {
        log.events.add(event.id);
        apply(event.effect, event, bindings, state, log, false);
    }

    /** Applies a deferred effect whose {@code if} and delay were already handled. */
    void applyDeferred(Deferred d, GameState state, StepLog log) {
        apply(d.effect(), d.event(), d.bindings(), state, log, true);
    }

    private void apply(JsonNode effect, Event event, Map<String, Object> b, GameState s, StepLog log, boolean deferred) {
        if (effect == null || effect.isNull()) return;
        if (!deferred) {
            if (effect.has("if") && !condition(effect.get("if"), s, b)) return;
            if (effect.has("delayMs")) {
                long delay = effect.get("delayMs").asLong();
                if (delay > 0) {
                    log.deferred.add(new Deferred(delay, effect, event, new HashMap<>(b)));
                    return;
                }
            }
        }
        String op = effect.path("op").asString();
        switch (op) {
            case "compose" -> {
                for (JsonNode step : effect.path("steps")) apply(step, event, b, s, log, false);
            }
            case "get" -> s.take(id(effect, 0, s, b), id(effect, 1, s, b));
            case "put" -> s.put(id(effect, 0, s, b), id(effect, 1, s, b));
            case "move" -> s.move(id(effect, 0, s, b), id(effect, 1, s, b));
            case "setProperty" -> {
                String property = effect.path("property").asString();
                if (property.isEmpty()) throw new EngineException("setProperty without 'property' in event '" + event.id + "'.");
                for (String t : targets(effect, event, s, b)) {
                    s.setProperty(t, property, eval.eval(effect.get("value"), s, with(b, "it", t)));
                }
            }
            case "setVisible" -> {
                for (String t : targets(effect, event, s, b)) {
                    Object v = effect.has("value") ? eval.eval(effect.get("value"), s, with(b, "it", t)) : Boolean.TRUE;
                    s.setProperty(t, "visible", Values.truthy(v));
                }
            }
            case "incrementResult", "decrementResult" -> {
                Object by = effect.has("by") ? eval.eval(effect.get("by"), s, b) : 1L;
                if (!(by instanceof Number n)) throw new EngineException("'by' must be a number, got " + by + ".");
                s.addToCounter(op.equals("incrementResult") ? n.doubleValue() : -n.doubleValue());
            }
            case "playSound" -> log.sounds.add(effect.path("sound").asString());
            default -> throw new EngineException("Unknown effect '" + op + "' in event '" + event.id + "'.");
        }
    }

    private boolean condition(JsonNode cond, GameState s, Map<String, Object> b) {
        if (cond.isString() && !cond.asString().startsWith("$")) {
            String name = cond.asString();
            if (!eval.hasFunction(name)) throw new EngineException("Condition '" + name + "' is not a function in F.");
            return eval.test(Shorthand.op("var", List.of(cond)), s, b);
        }
        return eval.test(cond, s, b);
    }

    private List<String> targets(JsonNode effect, Event event, GameState s, Map<String, Object> b) {
        List<Object> raw = new ArrayList<>();
        JsonNode t = effect.get("target");
        if (t != null && t.isArray()) {
            for (JsonNode x : t) raw.add(eval.eval(x, s, b));
        } else if (t != null) {
            raw.add(eval.eval(t, s, b));
        } else if (event.target != null && !event.target.isEmpty()) {
            raw.addAll(event.target);
        } else if (b.containsKey("target")) {
            raw.add(b.get("target"));
        } else {
            throw new EngineException("Effect '" + effect.path("op").asString() + "' in event '" + event.id + "' has no target.");
        }

        List<String> result = new ArrayList<>();
        for (Object v : raw) {
            if (v == null) continue;                        // e.g. neighbour outside the board
            String id = Values.asId(v, "target");
            if (effect.has("where") && !eval.test(effect.get("where"), s, with(b, "it", id))) continue;
            result.add(id);
        }
        return result;
    }

    private String id(JsonNode effect, int index, GameState s, Map<String, Object> b) {
        JsonNode arg = effect.path("args").get(index);
        if (arg == null) throw new EngineException("Effect '" + effect.path("op").asString() + "' expects argument " + (index + 1) + ".");
        return Values.asId(eval.eval(arg, s, b), "object or position");
    }

    private static Map<String, Object> with(Map<String, Object> b, String key, Object value) {
        Map<String, Object> m = new HashMap<>(b);
        m.put(key, value);
        return m;
    }
}
