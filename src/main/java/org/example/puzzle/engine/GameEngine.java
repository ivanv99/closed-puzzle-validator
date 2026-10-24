package org.example.puzzle.engine;

import org.example.puzzle.model.Action;
import org.example.puzzle.model.ClosedPuzzle;
import org.example.puzzle.model.Event;
import org.example.puzzle.model.ObjectType;
import org.example.puzzle.model.Rule;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a {@link ClosedPuzzle} model. Execution semantics:
 *
 * <ol>
 *   <li><b>Binding.</b> An action is performed with parameters that become
 *       bindings: {@code target} for click-like actions; {@code obj},
 *       {@code src}, {@code dst} for moves ({@code src} is derived from the
 *       object's location, {@code obj} from the top object at {@code src}).</li>
 *   <li><b>Affordances.</b> Object types that are not {@code clickable},
 *       {@code movable} or {@code rotatable} reject the corresponding actions.</li>
 *   <li><b>Rules.</b> The guards ({@code when}) of all rules for the action are
 *       evaluated on the state <i>before</i> the action. If none holds, the
 *       action is rejected. Otherwise the {@code fire} lists of the applicable
 *       rules are executed in declaration order.</li>
 *   <li><b>Triggered events.</b> Events with a {@code trigger} fire when their
 *       trigger changes from false to true (edge-triggered), checked after
 *       every step in declaration order.</li>
 *   <li><b>End.</b> After every step FC is evaluated; once it holds, the game
 *       is finished, Res is evaluated and further actions are rejected.</li>
 *   <li><b>Atomicity.</b> If anything fails while executing a step, the whole
 *       step is rolled back.</li>
 *   <li><b>Time.</b> Effects with {@code delayMs} are queued on a logical
 *       clock that the client advances with {@link #advanceTime(long)}.</li>
 * </ol>
 */
public final class GameEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Timer(long dueAt, long seq, EffectExecutor.Deferred effect) {
    }

    private final ClosedPuzzle model;
    private final ExpressionEvaluator eval;
    private final EffectExecutor effects;
    private final Map<String, Action> actions = new LinkedHashMap<>();
    private final Map<String, Event> events = new LinkedHashMap<>();
    private final Map<String, ObjectType> objectTypes = new HashMap<>();

    private GameState state;
    private Map<String, Boolean> triggerMemory = new HashMap<>();
    private List<Timer> timers = new ArrayList<>();
    private long clock;
    private long timerSeq;
    private boolean finished;
    private Object result;

    public GameEngine(ClosedPuzzle model) {
        this.model = model;
        this.eval = new ExpressionEvaluator(model.F);
        this.effects = new EffectExecutor(eval);
        model.A.forEach(a -> actions.put(a.id, a));
        model.E.forEach(e -> events.put(e.id, e));
        model.OT.forEach(t -> objectTypes.put(t.id, t));

        this.state = GameState.initial(model);
        for (Event e : model.E) {
            if (e.trigger != null) triggerMemory.put(e.id, eval.test(e.trigger, state, Map.of()));
        }
        checkFinal();
    }

    public static GameEngine fromJson(String json) {
        return new GameEngine(MAPPER.readValue(json, ClosedPuzzle.class));
    }

    /* ----- public API ---------------------------------------------------- */

    public ClosedPuzzle model() {
        return model;
    }

    public GameState state() {
        return state;
    }

    public boolean isFinished() {
        return finished;
    }

    public Object result() {
        return result;
    }

    public long clock() {
        return clock;
    }

    public Long nextTimerInMs() {
        return timers.isEmpty() ? null : timers.getFirst().dueAt() - clock;
    }

    /** Evaluates a function from F (or the built-in {@code result}) in the current state. */
    public Object function(String name) {
        return eval.eval(Shorthand.op("var", List.of(JsonNodeFactory.instance.stringNode(name))),
                state, Map.of());
    }

    public StepResult perform(String actionId, Map<String, String> params) {
        if (finished) return rejected("The game is already finished.");
        Action action = actions.get(actionId);
        if (action == null) return rejected("Unknown action '" + actionId + "'.");

        return atomically(log -> {
            Map<String, Object> b = bind(action, params);
            checkAffordances(action, b);

            List<Rule> applicable = new ArrayList<>();
            for (Rule r : model.R) {
                if (actionId.equals(r.action) && (r.when == null || eval.test(r.when, state, b))) applicable.add(r);
            }
            if (applicable.isEmpty()) throw new EngineException("No rule allows '" + actionId + "' here.");

            for (Rule r : applicable) {
                log.rules.add(r.id);
                for (String evId : r.fire) {
                    Event ev = events.get(evId);
                    if (ev == null) throw new EngineException("Rule '" + r.id + "' fires unknown event '" + evId + "'.");
                    effects.fire(ev, b, state, log);
                }
            }
            afterStep(b, log);
        });
    }

    /** Advances the logical clock, applying deferred effects that become due. */
    public StepResult advanceTime(long ms) {
        if (ms < 0) throw new IllegalArgumentException("ms must be >= 0");
        if (finished) return accepted(new EffectExecutor.StepLog());
        long until = clock + ms;
        return atomically(log -> {
            while (!timers.isEmpty() && timers.getFirst().dueAt() <= until && !finished) {
                Timer t = timers.removeFirst();
                clock = t.dueAt();
                effects.applyDeferred(t.effect(), state, log);
                afterStep(t.effect().bindings(), log);
            }
            clock = until;
        });
    }

    /* ----- step machinery ------------------------------------------------ */

    private interface Step {
        void run(EffectExecutor.StepLog log);
    }

    private StepResult atomically(Step step) {
        GameState savedState = state.copy();
        Map<String, Boolean> savedTriggers = new HashMap<>(triggerMemory);
        List<Timer> savedTimers = new ArrayList<>(timers);
        long savedClock = clock, savedSeq = timerSeq;
        boolean savedFinished = finished;
        Object savedResult = result;

        EffectExecutor.StepLog log = new EffectExecutor.StepLog();
        try {
            step.run(log);
            return accepted(log);
        } catch (EngineException ex) {
            state = savedState;
            triggerMemory = savedTriggers;
            timers = savedTimers;
            clock = savedClock;
            timerSeq = savedSeq;
            finished = savedFinished;
            result = savedResult;
            return rejected(ex.getMessage());
        }
    }

    private void afterStep(Map<String, Object> bindings, EffectExecutor.StepLog log) {
        schedule(log);
        for (Event e : model.E) {
            if (e.trigger == null) continue;
            boolean now = eval.test(e.trigger, state, bindings);
            if (now && !triggerMemory.getOrDefault(e.id, false)) {
                effects.fire(e, bindings, state, log);
                schedule(log);
            }
        }
        for (Event e : model.E) {
            if (e.trigger != null) triggerMemory.put(e.id, eval.test(e.trigger, state, bindings));
        }
        checkFinal();
    }

    private void schedule(EffectExecutor.StepLog log) {
        for (EffectExecutor.Deferred d : log.deferred) {
            timers.add(new Timer(clock + d.delayMs(), timerSeq++, d));
        }
        log.deferred.clear();
        timers.sort(Comparator.comparingLong(Timer::dueAt).thenComparingLong(Timer::seq));
    }

    private void checkFinal() {
        if (model.FC != null && eval.test(model.FC, state, Map.of())) {
            finished = true;
            result = model.Res == null || model.Res.isNull() ? null : eval.eval(model.Res, state, Map.of());
            timers.clear();
        }
    }

    /* ----- binding & affordances ----------------------------------------- */

    private Map<String, Object> bind(Action action, Map<String, String> params) {
        Map<String, Object> b = new HashMap<>(params == null ? Map.of() : params);
        if (action.kind.equals("move")) {
            String obj = (String) b.get("obj"), src = (String) b.get("src"), dst = (String) b.get("dst");
            if (obj == null && src != null) obj = state.topObjectAt(src);
            if (obj == null) throw new EngineException("Move needs 'obj' or a non-empty 'src'.");
            if (!state.isObject(obj)) throw new EngineException("Unknown object '" + obj + "'.");
            if (src == null) src = state.location(obj);
            if (src == null || !src.equals(state.location(obj))) {
                throw new EngineException("Object '" + obj + "' is not on position '" + src + "'.");
            }
            if (dst == null || !state.isPosition(dst)) throw new EngineException("Move needs a valid 'dst' position.");
            b.put("obj", obj);
            b.put("src", src);
            b.put("dst", dst);
            b.putIfAbsent("target", obj);
        } else if (b.get("target") != null && !state.exists((String) b.get("target"))) {
            throw new EngineException("Unknown target '" + b.get("target") + "'.");
        }
        return b;
    }

    private void checkAffordances(Action action, Map<String, Object> b) {
        Object target = action.kind.equals("move") ? b.get("obj") : b.get("target");
        if (!(target instanceof String id) || !state.isObject(id)) return;
        ObjectType type = objectTypes.get(state.type(id));
        if (type == null) return;
        boolean allowed = switch (action.kind) {
            case "click", "doubleClick", "flip", "selectSequence" -> type.clickable;
            case "move" -> type.movable;
            case "rotate" -> type.rotatable;
            default -> true;
        };
        if (!allowed) {
            throw new EngineException("Objects of type '" + type.id + "' do not support '" + action.kind + "'.");
        }
    }

    /* ----- results ------------------------------------------------------- */

    private StepResult accepted(EffectExecutor.StepLog log) {
        return new StepResult(true, null, List.copyOf(log.rules), List.copyOf(log.events), List.copyOf(log.sounds),
                finished, result, nextTimerInMs());
    }

    private StepResult rejected(String message) {
        return new StepResult(false, message, List.of(), List.of(), List.of(), finished, result, nextTimerInMs());
    }
}
