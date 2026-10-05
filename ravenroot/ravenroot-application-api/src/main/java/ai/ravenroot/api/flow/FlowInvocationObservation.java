package ai.ravenroot.api.flow;

import ai.ravenroot.api.deployment.DeploymentId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Payload-free runtime projection of one durable caller/child invocation relation.
 * @param handle opaque relation reference
 * @param callerProcessInstanceId caller process identity
 * @param callerTraversalId caller traversal identity
 * @param callerInvocationId caller node invocation identity
 * @param childProcessInstanceId fresh child process identity
 * @param childTraversalId fresh child traversal identity
 * @param targetDeploymentId registered target deployment
 * @param targetVersion exact immutable target version
 * @param targetDigest canonical target content digest
 * @param status current durable lifecycle state
 * @param continuationClaim caller invocation admitted to continue, or {@code null}
 * @param failureCode bounded terminal failure code, or empty
 * @param createdAt intent creation time
 * @param updatedAt latest relation mutation time
 * @param deadlineAt child deadline
 * @param retainedUntil terminal evidence retention bound
 */
public record FlowInvocationObservation(
        FlowHandle handle,
        UUID callerProcessInstanceId,
        UUID callerTraversalId,
        UUID callerInvocationId,
        UUID childProcessInstanceId,
        UUID childTraversalId,
        DeploymentId targetDeploymentId,
        long targetVersion,
        String targetDigest,
        FlowInvocationStatus status,
        UUID continuationClaim,
        String failureCode,
        Instant createdAt,
        Instant updatedAt,
        Instant deadlineAt,
        Instant retainedUntil) {
    /** Validates a payload-free observation projection. */
    public FlowInvocationObservation {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(callerProcessInstanceId, "callerProcessInstanceId");
        Objects.requireNonNull(callerTraversalId, "callerTraversalId");
        Objects.requireNonNull(callerInvocationId, "callerInvocationId");
        Objects.requireNonNull(childProcessInstanceId, "childProcessInstanceId");
        Objects.requireNonNull(childTraversalId, "childTraversalId");
        Objects.requireNonNull(targetDeploymentId, "targetDeploymentId");
        Objects.requireNonNull(targetDigest, "targetDigest");
        Objects.requireNonNull(status, "status");
        failureCode = failureCode == null ? "" : failureCode;
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        Objects.requireNonNull(retainedUntil, "retainedUntil");
    }

    /**
     * Whether the caller has durably parked or claimed the terminal result.
     * @return whether a continuation claimant has been recorded
     */
    public boolean awaited() {
        return continuationClaim != null;
    }
}
