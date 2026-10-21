package ai.ravenroot.api.correlation;

import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.application.ExecutionTerminationReason;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable relation; a business history never rewrites its original traversal or process identities. */
public record CorrelatedTraversal(BusinessScope scope, ApplicationCorrelationContext context,
                                   UUID processInstanceId, UUID traversalId, String entryNodeId,
                                   Cause cause, TraversalStatus status,
                                   ExecutionTerminationReason terminationReason,
                                   Instant startedAt, Instant updatedAt, Instant endedAt) {
    /** Entry cause, independent from transport message IDs and retry attempts. */
    public enum Cause { INITIAL_START, SOURCE_EVENT, JOIN_CONTINUATION, DERIVED_CONTEXT }
    /** Validates stable coordinates and lifecycle timestamps. */
    public CorrelatedTraversal {
        Objects.requireNonNull(scope, "scope"); Objects.requireNonNull(context, "context");
        Objects.requireNonNull(processInstanceId, "processInstanceId"); Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(cause, "cause"); Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt"); Objects.requireNonNull(updatedAt, "updatedAt");
        if (entryNodeId == null || entryNodeId.isBlank() || entryNodeId.length() > 4096)
            throw new IllegalArgumentException("invalid entry node");
        if (status.terminal() != (endedAt != null)) throw new IllegalArgumentException("terminal traversal requires end time");
    }
}
