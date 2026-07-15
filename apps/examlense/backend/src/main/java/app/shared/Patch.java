package app.shared;

import app.error.ApiException;
import app.task.TaskOption;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Coercion helpers for partial PATCH bodies received as a raw {@code Map}
 * (so "key absent" is distinguishable from "key set to null"). Malformed
 * values surface as 400s with the standard error body, never as 500s.
 */
public final class Patch {

    private Patch() {}

    public static boolean has(Map<String, Object> m, String key) {
        return m.containsKey(key);
    }

    public static String str(Object v) {
        return v == null ? null : v.toString();
    }

    public static BigDecimal bigDecimal(Object v) {
        if (v == null) return null;
        try {
            if (v instanceof Number n) return new BigDecimal(n.toString());
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            throw badValue("number", v);
        }
    }

    public static Integer intVal(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.valueOf(v.toString());
        } catch (NumberFormatException e) {
            throw badValue("integer", v);
        }
    }

    public static Long longVal(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.valueOf(v.toString());
        } catch (NumberFormatException e) {
            throw badValue("integer", v);
        }
    }

    public static List<Long> longList(Object v) {
        if (!(v instanceof List<?> list)) return null;
        List<Long> out = new ArrayList<>();
        for (Object o : list) if (o instanceof Number n) out.add(n.longValue());
        return out;
    }

    public static UUID uuid(Object v) {
        if (v == null) return null;
        try {
            return UUID.fromString(v.toString());
        } catch (IllegalArgumentException e) {
            throw badValue("id", v);
        }
    }

    public static List<TaskOption> options(Object v) {
        if (!(v instanceof List<?> list)) return null;
        List<TaskOption> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Object id = m.get("id");
                out.add(new TaskOption(
                    id == null ? UUID.randomUUID().toString() : id.toString(),
                    m.get("text") == null ? "" : m.get("text").toString(),
                    Boolean.TRUE.equals(m.get("is_correct"))
                ));
            }
        }
        return out;
    }

    private static ApiException badValue(String kind, Object v) {
        return new ApiException(HttpStatus.BAD_REQUEST, "Invalid " + kind + ": " + v);
    }
}
