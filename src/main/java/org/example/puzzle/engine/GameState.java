package org.example.puzzle.engine;

import org.example.puzzle.model.ClosedPuzzle;
import org.example.puzzle.model.ObjectInstance;
import org.example.puzzle.model.Placement;
import org.example.puzzle.model.Position;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable run-time state of a puzzle: current properties of every object
 * and position, the object → position mapping and the result counter used by
 * {@code incrementResult}/{@code decrementResult}.
 *
 * <p>Objects and positions share one id namespace for lookups; when an id
 * exists in both sets, the object wins.
 */
public final class GameState {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> PROPS = new TypeReference<>() {
    };

    private final Map<String, Map<String, Object>> objects;
    private final Map<String, Map<String, Object>> positions;
    private final Map<String, String> location;
    private double counter;

    private GameState(Map<String, Map<String, Object>> objects,
                      Map<String, Map<String, Object>> positions,
                      Map<String, String> location,
                      double counter) {
        this.objects = objects;
        this.positions = positions;
        this.location = location;
        this.counter = counter;
    }

    /** Builds the initial state from O, P and InitDisp (with overrides). */
    static GameState initial(ClosedPuzzle model) {
        Map<String, Map<String, Object>> objects = new LinkedHashMap<>();
        for (ObjectInstance o : model.O) objects.put(o.id, toProps(o));
        Map<String, Map<String, Object>> positions = new LinkedHashMap<>();
        for (Position p : model.P) positions.put(p.id, toProps(p));

        Map<String, String> location = new LinkedHashMap<>();
        for (Placement pl : model.InitDisp) {
            if (!objects.containsKey(pl.object)) throw new EngineException("InitDisp: unknown object '" + pl.object + "'.");
            if (!positions.containsKey(pl.position)) throw new EngineException("InitDisp: unknown position '" + pl.position + "'.");
            location.put(pl.object, pl.position);
            if (pl.overrides != null) {
                pl.overrides.forEach((k, v) -> objects.get(pl.object).put(k, Values.normalize(v)));
            }
        }
        return new GameState(objects, positions, location, 0);
    }

    private static Map<String, Object> toProps(Object pojo) {
        Map<String, Object> raw = MAPPER.convertValue(pojo, PROPS);
        Map<String, Object> props = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v != null) props.put(k, Values.normalize(v));
        });
        return props;
    }

    GameState copy() {
        Map<String, Map<String, Object>> o = new LinkedHashMap<>();
        objects.forEach((k, v) -> o.put(k, new LinkedHashMap<>(v)));
        Map<String, Map<String, Object>> p = new LinkedHashMap<>();
        positions.forEach((k, v) -> p.put(k, new LinkedHashMap<>(v)));
        return new GameState(o, p, new LinkedHashMap<>(location), counter);
    }

    /* ----- queries ------------------------------------------------------- */

    public boolean isObject(String id) {
        return objects.containsKey(id);
    }

    public boolean isPosition(String id) {
        return positions.containsKey(id) && !objects.containsKey(id);
    }

    public boolean exists(String id) {
        return objects.containsKey(id) || positions.containsKey(id);
    }

    /** Position currently holding the object, or {@code null} if it is off the board. */
    public String location(String objectId) {
        requireObject(objectId);
        return location.get(objectId);
    }

    /** For a position: itself; for an object: its current position. */
    public String positionOf(String id) {
        return isObject(id) ? location.get(id) : requirePosition(id);
    }

    public List<String> objectsAt(String positionId) {
        requirePosition(positionId);
        List<String> result = new ArrayList<>();
        location.forEach((obj, pos) -> {
            if (pos.equals(positionId)) result.add(obj);
        });
        return result;
    }

    /** The visually topmost object at a position (highest zOrder, last declared on ties). */
    public String topObjectAt(String positionId) {
        String top = null;
        long best = Long.MIN_VALUE;
        for (String obj : objectsAt(positionId)) {
            Object z = objects.get(obj).get("zOrder");
            long zo = z instanceof Number n ? n.longValue() : 0;
            if (zo >= best) {
                best = zo;
                top = obj;
            }
        }
        return top;
    }

    public int capacity(String positionId) {
        Object c = positions.get(requirePosition(positionId)).get("capacity");
        return c instanceof Number n ? n.intValue() : 1;
    }

    /**
     * Property of an object or position. For a position that does not define
     * the property itself, the property of the single object standing on it
     * is returned (so {@code checkProperty(C00, title)} reads the tile title).
     */
    public Object property(String id, String name) {
        if (isObject(id)) return objects.get(id).get(name);
        Map<String, Object> props = positions.get(requirePosition(id));
        if (props.get(name) != null) return props.get(name);
        List<String> occupants = objectsAt(id);
        return occupants.size() == 1 ? objects.get(occupants.getFirst()).get(name) : null;
    }

    public String type(String id) {
        return (String) props(id).get("type");
    }

    /**
     * Position next to {@code positionId} in direction N, S, E or W using the
     * x/y coordinates of P (screen convention: y grows downwards), or
     * {@code null} if there is none.
     */
    public String neighbourPosition(String positionId, String direction) {
        double[] xy = coordinates(positionId);
        double dx = 0, dy = 0;
        switch (direction) {
            case "N" -> dy = -1;
            case "S" -> dy = 1;
            case "E" -> dx = 1;
            case "W" -> dx = -1;
            default -> throw new EngineException("Unknown direction '" + direction + "' (expected N, S, E or W).");
        }
        for (Map.Entry<String, Map<String, Object>> e : positions.entrySet()) {
            Object x = e.getValue().get("x"), y = e.getValue().get("y");
            if (x instanceof Number nx && y instanceof Number ny
                    && nx.doubleValue() == xy[0] + dx && ny.doubleValue() == xy[1] + dy) {
                return e.getKey();
            }
        }
        return null;
    }

    public double[] coordinates(String positionId) {
        Map<String, Object> p = positions.get(requirePosition(positionId));
        if (!(p.get("x") instanceof Number x) || !(p.get("y") instanceof Number y)) {
            throw new EngineException("Position '" + positionId + "' has no x/y coordinates.");
        }
        return new double[]{x.doubleValue(), y.doubleValue()};
    }

    public double counter() {
        return counter;
    }

    /* ----- mutations ----------------------------------------------------- */

    void setProperty(String id, String name, Object value) {
        props(id).put(name, Values.normalize(value));
    }

    /** {@code get(src, obj)}: lifts the object off its position. */
    void take(String src, String obj) {
        if (!src.equals(location(obj))) {
            throw new EngineException("Object '" + obj + "' is not on position '" + src + "'.");
        }
        location.remove(obj);
    }

    /** {@code put(dst, obj)}: places a lifted (or off-board) object. */
    void put(String dst, String obj) {
        if (location(obj) != null) {
            throw new EngineException("Object '" + obj + "' is still on '" + location.get(obj) + "'; take it first.");
        }
        if (objectsAt(dst).size() >= capacity(dst)) {
            throw new EngineException("Position '" + dst + "' is full (capacity " + capacity(dst) + ").");
        }
        location.put(obj, dst);
    }

    void move(String obj, String dst) {
        String src = location(obj);
        if (src != null) take(src, obj);
        put(dst, obj);
    }

    void addToCounter(double delta) {
        counter += delta;
    }

    /* ----- snapshot for clients ------------------------------------------ */

    /** Plain-map snapshot (JSON-friendly) of the whole state. */
    public Map<String, Object> snapshot() {
        Map<String, Object> s = new LinkedHashMap<>();
        Map<String, Object> objs = new LinkedHashMap<>();
        objects.forEach((id, props) -> {
            Map<String, Object> o = new LinkedHashMap<>(props);
            o.put("position", location.get(id));
            objs.put(id, o);
        });
        Map<String, Object> poss = new LinkedHashMap<>();
        positions.forEach((id, props) -> poss.put(id, new LinkedHashMap<>(props)));
        s.put("objects", objs);
        s.put("positions", poss);
        s.put("result", number(counter));
        return s;
    }

    static Object number(double d) {
        return d == Math.rint(d) && !Double.isInfinite(d) ? (Object) (long) d : (Object) d;
    }

    /* ----- helpers ------------------------------------------------------- */

    private Map<String, Object> props(String id) {
        if (objects.containsKey(id)) return objects.get(id);
        if (positions.containsKey(id)) return positions.get(id);
        throw new EngineException("Unknown object or position '" + id + "'.");
    }

    private void requireObject(String id) {
        if (!objects.containsKey(id)) throw new EngineException("Unknown object '" + id + "'.");
    }

    private String requirePosition(String id) {
        if (!positions.containsKey(id)) throw new EngineException("Unknown position '" + id + "'.");
        return id;
    }
}
