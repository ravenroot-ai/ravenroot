package ai.ravenroot.api.application;

/** API-02. Mirrors {@code ai.ravenroot.api.programming.ArtifactLifecycleAuditSink}'s shape. */
@FunctionalInterface
public interface ExecutionControlAuditSink {
/**
 * Records an execution-control audit event without changing execution state.
 * @param event control-plane audit event to persist or forward.
 */
    void record(ExecutionControlAuditEvent event);
}
