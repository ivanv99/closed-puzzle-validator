package org.example.puzzle.engine;

import tools.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * Runtime values of the expression language: {@link Boolean}, {@link Long},
 * {@link Double}, {@link String} (literals and entity ids) and {@code null}
 * (e.g. a neighbour outside the board).
 */
final class Values {

    private Values() {
    }

    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        if (v instanceof String s) return !s.isEmpty();
        return true;
    }

    static Object literal(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        if (n.isBoolean()) return n.booleanValue();
        if (n.isIntegralNumber()) return n.longValue();
        if (n.isNumber()) return n.doubleValue();
        if (n.isString()) return n.asString();
        throw new EngineException("Not a literal value: " + n);
    }

    /** Integer → Long, Float → Double; everything else unchanged. */
    static Object normalize(Object v) {
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) return ((Number) v).longValue();
        if (v instanceof Float f) return f.doubleValue();
        return v;
    }

    static boolean equal(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return x.doubleValue() == y.doubleValue();
        return Objects.equals(a, b);
    }

    static int compare(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return Double.compare(x.doubleValue(), y.doubleValue());
        if (a instanceof String x && b instanceof String y) return x.compareTo(y);
        throw new EngineException("Cannot compare " + a + " and " + b + ".");
    }

    static Object arithmetic(String op, Object a, Object b) {
        if (!(a instanceof Number x) || !(b instanceof Number y)) {
            throw new EngineException("Operator '" + op + "' expects numbers, got " + a + " and " + b + ".");
        }
        if (x instanceof Long l && y instanceof Long r && !op.equals("div")) {
            return switch (op) {
                case "add" -> l + r;
                case "sub" -> l - r;
                case "mul" -> l * r;
                case "mod" -> {
                    if (r == 0) throw new EngineException("Modulo by zero.");
                    yield Math.floorMod(l, r);
                }
                default -> throw new EngineException("Unknown arithmetic operator '" + op + "'.");
            };
        }
        double l = x.doubleValue(), r = y.doubleValue();
        return switch (op) {
            case "add" -> l + r;
            case "sub" -> l - r;
            case "mul" -> l * r;
            case "div" -> {
                if (r == 0) throw new EngineException("Division by zero.");
                yield l / r;
            }
            case "mod" -> l % r;
            default -> throw new EngineException("Unknown arithmetic operator '" + op + "'.");
        };
    }

    static String asId(Object v, String what) {
        if (v instanceof String s) return s;
        throw new EngineException("Expected " + what + " id, got " + v + ".");
    }
}
