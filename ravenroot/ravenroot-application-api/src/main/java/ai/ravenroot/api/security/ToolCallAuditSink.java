package ai.ravenroot.api.security;

/** Receives sanitized tool-call audit events without authorizing them. */
@FunctionalInterface
public interface ToolCallAuditSink {
    /**
     * Records one sanitized tool decision or effect outcome.
     * @param event payload-free event to record
     */
    void record(ToolCallAuditEvent event);

    /**
     * Constructs a sink for compositions that grant no tool authorization.
     * @return a sink that deliberately retains nothing
     */
    static ToolCallAuditSink discarding() {
        return ignored -> { };
    }
}
