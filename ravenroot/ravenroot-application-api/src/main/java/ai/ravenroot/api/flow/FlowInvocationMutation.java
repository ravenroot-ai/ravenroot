package ai.ravenroot.api.flow;

import java.time.Instant;
import java.util.UUID;

/**
 * Compare-and-set mutation of one durable invocation relation.
 * @param handle relation to mutate
 * @param expectedRevision required current revision
 * @param status next lifecycle status
 * @param childProcessInstanceId fresh child process identity
 * @param childTraversalId fresh child traversal identity
 * @param result bounded canonical result bytes, only for completion
 * @param failureCode bounded machine-readable failure code
 * @param failureMessage bounded operator-safe failure message
 * @param continuationClaim caller invocation admitted to continue
 * @param updatedAt mutation time
 */
public record FlowInvocationMutation(
        FlowHandle handle,
        long expectedRevision,
        FlowInvocationStatus status,
        UUID childProcessInstanceId,
        UUID childTraversalId,
        byte[] result,
        String failureCode,
        String failureMessage,
        UUID continuationClaim,
        Instant updatedAt) {
    /** Validates and defensively copies a relation mutation. */
    public FlowInvocationMutation {
        if (handle == null || expectedRevision < 1 || status == null || updatedAt == null) {
            throw new IllegalArgumentException("invalid flow invocation mutation");
        }
        result = result == null ? null : result.clone();
        failureCode = failureCode == null ? "" : failureCode;
        failureMessage = failureMessage == null ? "" : failureMessage;
    }
    /**
     * Returns result bytes without exposing the record's mutable array.
     * @return a defensive copy of the bounded result bytes, or {@code null}
     */
    @Override public byte[] result() { return result == null ? null : result.clone(); }
}
