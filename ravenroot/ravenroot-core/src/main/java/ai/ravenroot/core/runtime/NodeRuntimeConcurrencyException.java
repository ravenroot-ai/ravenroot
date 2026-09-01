package ai.ravenroot.core.runtime;

import java.util.Map;

/** Sanitized refusal of an untrusted {@code runtime.maxConcurrency} declaration. */
public final class NodeRuntimeConcurrencyException extends IllegalArgumentException {
    public enum Reason {
        DECLARED_ON_NON_BEHAVIOR_NODE,
        DECLARED_BY_UNCATALOGUED_BEHAVIOR,
        INVALID_VALUE,
        EXCEEDS_TRUSTED_CEILING
    }

    private final Reason reason;
    private final transient Map<String, Object> diagnosticDetail;

    NodeRuntimeConcurrencyException(Reason reason, Map<String, Object> diagnosticDetail) {
        super("Graph property 'runtime.maxConcurrency' is invalid or is not authorized by the trusted catalog");
        this.reason = reason;
        this.diagnosticDetail = Map.copyOf(diagnosticDetail);
    }

    public Reason reason() { return reason; }
    public Map<String, Object> diagnosticDetail() { return diagnosticDetail; }
}
