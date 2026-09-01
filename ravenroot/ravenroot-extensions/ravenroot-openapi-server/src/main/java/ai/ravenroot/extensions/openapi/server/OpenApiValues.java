package ai.ravenroot.extensions.openapi.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class OpenApiValues {
    private OpenApiValues() { }

    static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> source)) throw invalid();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, entry) -> {
            if (!(key instanceof String name) || result.putIfAbsent(name, entry) != null) throw invalid();
        });
        return java.util.Collections.unmodifiableMap(result);
    }

    static Map<String, Object> optionalObject(Object value, String field) {
        return value == null ? Map.of() : object(value, field);
    }

    static List<Object> list(Object value, String field) {
        if (!(value instanceof List<?> values)) throw invalid();
        return List.copyOf(values);
    }

    static String string(Object value, String field, int maximum) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > maximum) throw invalid();
        return text;
    }

    static String optionalString(Object value, String field, int maximum) {
        return value == null ? null : string(value, field, maximum);
    }

    static int integer(Object value, String field, int minimum, int maximum) {
        if (!(value instanceof Number number)) throw invalid();
        long raw = number.longValue();
        if (number.doubleValue() != raw || raw < minimum || raw > maximum) throw invalid();
        return (int) raw;
    }

    static boolean bool(Object value, String field, boolean fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Boolean flag)) throw invalid();
        return flag;
    }

    static void exactKeys(Map<String, ?> value, Set<String> accepted, String field) {
        if (!accepted.containsAll(value.keySet())) throw invalid();
    }

    static OpenApiServerException invalid() {
        return new OpenApiServerException(OpenApiServerException.Code.CONFIGURATION_INVALID);
    }
}
