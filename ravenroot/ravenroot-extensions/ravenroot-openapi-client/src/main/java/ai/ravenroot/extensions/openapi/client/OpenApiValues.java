package ai.ravenroot.extensions.openapi.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class OpenApiValues {
    private OpenApiValues() { }

    static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> source)) throw invalid(field);
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, entry) -> {
            if (!(key instanceof String name) || out.putIfAbsent(name, entry) != null) throw invalid(field);
        });
        return java.util.Collections.unmodifiableMap(out);
    }

    static Map<String, Object> optionalObject(Object value, String field) {
        return value == null ? Map.of() : object(value, field);
    }

    static List<Object> list(Object value, String field) {
        if (!(value instanceof List<?> list)) throw invalid(field);
        return List.copyOf(list);
    }

    static String string(Object value, String field, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > max) throw invalid(field);
        return text;
    }

    static String optionalString(Object value, String field, int max) {
        return value == null ? null : string(value, field, max);
    }

    static int integer(Object value, String field, int min, int max) {
        if (!(value instanceof Number number)) throw invalid(field);
        long raw = number.longValue();
        if (number.doubleValue() != raw || raw < min || raw > max) throw invalid(field);
        return (int) raw;
    }

    static boolean bool(Object value, String field, boolean fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Boolean flag)) throw invalid(field);
        return flag;
    }

    static void exactKeys(Map<String, ?> value, Set<String> accepted, String field) {
        if (!accepted.containsAll(value.keySet())) throw invalid(field);
    }

    static OpenApiClientException invalid(String ignored) {
        return new OpenApiClientException(OpenApiClientException.Code.CONFIGURATION);
    }
}
