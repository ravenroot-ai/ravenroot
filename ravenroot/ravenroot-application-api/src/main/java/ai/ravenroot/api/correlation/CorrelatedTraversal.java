package ai.ravenroot.api.correlation;

import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.application.ExecutionTerminationReason;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable relation; a business history never rewrites its original traversal or process identities.
 * @param scope tenant, logical deployment and exact immutable graph version
 * @param context immutable established application context for the original traversal
 * @param processInstanceId original owning process identity, independent from a business history
 * @param traversalId actual traversal identity within the owning process
 * @param entryNodeId actual admitted entry node; a source event does not pretend to enter at START
 * @param cause recorded entry cause, absent for legacy records that contain no such evidence
 * @param status authoritative recorded lifecycle status at the represented level
 * @param terminationReason recorded terminal reason, absent when none was recorded
 * @param startedAt time the original traversal was admitted under its established application context
 * @param updatedAt time of the latest recorded change; pagination does not depend on this value
 * @param endedAt recorded traversal end time, present exactly for terminal traversals
 * @param derivation original causal coordinates for an explicit context derivation, absent for other entry causes
 */
public record CorrelatedTraversal(BusinessScope scope, ApplicationCorrelationContext context,
                                   UUID processInstanceId, UUID traversalId, String entryNodeId,
                                   Cause cause, TraversalStatus status,
                                   ExecutionTerminationReason terminationReason,
                                   Instant startedAt, Instant updatedAt, Instant endedAt, CorrelationDerivation derivation) {
    /** Creates a traversal relation without explicit context derivation ancestry.
     * @param scope exact tenant, deployment and graph version
     * @param context established immutable business identity
     * @param processInstanceId original owning process
     * @param traversalId actual traversal
     * @param entryNodeId admitted entry
     * @param cause entry cause
     * @param status actual lifecycle
     * @param terminationReason optional terminal classification
     * @param startedAt original admission time
     * @param updatedAt latest update
     * @param endedAt terminal time, absent while running
     */
    public CorrelatedTraversal(BusinessScope scope, ApplicationCorrelationContext context, UUID processInstanceId,
            UUID traversalId, String entryNodeId, Cause cause, TraversalStatus status, ExecutionTerminationReason terminationReason,
            Instant startedAt, Instant updatedAt, Instant endedAt) {
        this(scope, context, processInstanceId, traversalId, entryNodeId, cause, status, terminationReason, startedAt, updatedAt, endedAt, null);
    }
    /** Entry cause, independent from transport message IDs and retry attempts. */
    public enum Cause {
        /** Original initial START admitted for the logical deployment. */
        INITIAL_START,
        /** Event admitted at its actual SOURCE node. */
        SOURCE_EVENT,
        /** Independent traversal released by a complete cross-traversal join. */
        JOIN_CONTINUATION,
        /** Traversal established by an explicitly authorized context derivation. */
        DERIVED_CONTEXT
    }
    /** Validates stable coordinates and lifecycle timestamps. */
    public CorrelatedTraversal {
        Objects.requireNonNull(scope, "scope"); Objects.requireNonNull(context, "context");
        Objects.requireNonNull(processInstanceId, "processInstanceId"); Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(cause, "cause"); Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt"); Objects.requireNonNull(updatedAt, "updatedAt");
        if (entryNodeId == null || entryNodeId.isBlank() || entryNodeId.length() > 4096)
            throw new IllegalArgumentException("invalid entry node");
        if ((cause == Cause.DERIVED_CONTEXT) != (derivation != null)) throw new IllegalArgumentException("explicit context derivation requires original ancestry");
        if (status.terminal() != (endedAt != null)) throw new IllegalArgumentException("terminal traversal requires end time");
    }
}
