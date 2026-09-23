package com.waas.core.common.redis;

/**
 * Lua replies arrive as a mix of {@code Long} (integer replies) and {@code String} (bulk replies,
 * e.g. scores, which our scripts deliberately return as strings so 3.5 isn't truncated to 3).
 * These helpers accept either.
 */
public final class LuaResults {

    private LuaResults() {}

    public static long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(value.toString());
    }

    public static double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    public static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
