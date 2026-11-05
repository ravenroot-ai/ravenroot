package ai.ravenroot.core.graph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Narrow repair for the confirmed default-Log inspector signature, never arbitrary saga declarations. */
public final class LegacySagaDefaults {
    private static final Set<String> KEYS = Set.of("saga.role", "saga.irreversible",
            "saga.businessCompletionRequired");
    private LegacySagaDefaults() { }

    public static Map<String, Object> normalize(String behavior, Map<String, Object> properties) {
        if (!"log".equals(behavior)
                || !properties.keySet().stream().filter(key -> key.startsWith("saga.")).collect(
                        java.util.stream.Collectors.toSet()).equals(KEYS)
                || !"forward".equals(properties.get("saga.role"))
                || !isFalse(properties.get("saga.irreversible"))
                || !isFalse(properties.get("saga.businessCompletionRequired"))) return Map.copyOf(properties);
        var repaired = new LinkedHashMap<>(properties);
        KEYS.forEach(repaired::remove);
        return Map.copyOf(repaired);
    }

    private static boolean isFalse(Object value) {
        return Boolean.FALSE.equals(value) || "false".equals(value);
    }
}
