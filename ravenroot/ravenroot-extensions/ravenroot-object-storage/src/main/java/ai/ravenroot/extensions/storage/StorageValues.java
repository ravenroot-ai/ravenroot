package ai.ravenroot.extensions.storage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class StorageValues {
    static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> raw) || raw.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw invalid(field);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, entry) -> out.put((String) key, entry));
        return Map.copyOf(out);
    }

    static List<Object> list(Object value, String field) {
        if (!(value instanceof List<?> list)) throw invalid(field);
        return List.copyOf(list);
    }

    static String string(Object value, String field, int max) {
        if (!(value instanceof String text) || text.isEmpty() || text.length() > max) throw invalid(field);
        return text;
    }

    static boolean bool(Object value, String field) {
        if (!(value instanceof Boolean result)) throw invalid(field);
        return result;
    }

    static int integer(Object value, String field, int min, int max) {
        if (!(value instanceof Long number) || number < min || number > max) throw invalid(field);
        return Math.toIntExact(number);
    }

    static void exactKeys(Map<String, Object> value, Set<String> keys, String field) {
        if (!value.keySet().equals(keys)) throw invalid(field);
    }

    static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("invalid " + field);
    }

    private StorageValues() { }
}
