package ai.ravenroot.api.persistence;

import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned definition pinned to every saga instance for deterministic recovery.
 * @param contractVersion supported saga contract version
 * @param scopeId authored saga scope identity
 * @param graphDigest frozen graph definition digest
 * @param participantDigest frozen participant contract digest
 * @param steps logical forward step definitions
 */
public record SagaDefinition(int contractVersion, String scopeId, String graphDigest,
                             String participantDigest, Map<String, SagaStepDefinition> steps) {
    /** Saga definition contract emitted by this build. */
    public static final int CONTRACT_VERSION = 1;

    /** Validates and defensively snapshots the durable value. */
    public SagaDefinition {
        if (contractVersion != CONTRACT_VERSION) throw new IllegalArgumentException("unsupported saga contract");
        if (scopeId == null || scopeId.isBlank() || scopeId.length() > 200) {
            throw new IllegalArgumentException("scopeId must be non-blank and bounded");
        }
        graphDigest = digest(graphDigest, "graphDigest");
        participantDigest = digest(participantDigest, "participantDigest");
        var copy = new LinkedHashMap<String, SagaStepDefinition>();
        if (steps != null) steps.forEach((key, value) -> {
            if (value == null || !key.equals(value.stepId()) || copy.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("invalid or duplicate saga step definition");
            }
        });
        if (copy.isEmpty()) throw new IllegalArgumentException("a saga scope must govern at least one step");
        for (SagaStepDefinition step : copy.values()) {
            for (String dependency : step.dependencies()) {
                if (!copy.containsKey(dependency) || dependency.equals(step.stepId())) {
                    throw new IllegalArgumentException("unknown or self saga dependency");
                }
            }
        }
        steps = Map.copyOf(copy);
    }

    private static String digest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
        }
        return value;
    }
}
