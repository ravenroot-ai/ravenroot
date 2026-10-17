package ai.ravenroot.api.flow;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.security.PrincipalType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable relation between one caller node invocation and one fresh child execution.
 *
 * <p>Payloads are canonical bounded JSON bytes. A continuation claimant is written once with CAS;
 * it means the runtime accepted one continuation, not that arbitrary external effects are exactly
 * once.</p>
 *
 * @param tenantId tenant that owns both executions
 * @param handle opaque relation reference
 * @param callerProcessInstanceId caller process identity
 * @param callerTraversalId caller traversal identity
 * @param callerInvocationId caller node invocation identity
 * @param callerSubject authenticated caller subject
 * @param callerPrincipalType authenticated caller principal type
 * @param callerIssuer authenticated caller issuer
 * @param targetDeploymentId registered target deployment
 * @param targetVersion exact immutable target version
 * @param targetDigest canonical target content digest
 * @param childProcessInstanceId preallocated fresh child process identity
 * @param childTraversalId preallocated fresh child traversal identity
 * @param status durable invocation lifecycle state
 * @param input bounded canonical input bytes
 * @param result bounded canonical result bytes, only for completion
 * @param failureCode bounded terminal failure code
 * @param failureMessage bounded operator-safe terminal failure message
 * @param continuationClaim caller invocation admitted to continue, or {@code null}
 * @param revision positive durable relation revision
 * @param createdAt intent creation time
 * @param updatedAt latest relation mutation time
 * @param deadlineAt child execution deadline
 * @param retainedUntil terminal evidence retention bound
 */
public record FlowInvocationRecord(
        String tenantId,
        FlowHandle handle,
        UUID callerProcessInstanceId,
        UUID callerTraversalId,
        UUID callerInvocationId,
        String callerSubject,
        PrincipalType callerPrincipalType,
        String callerIssuer,
        DeploymentId targetDeploymentId,
        long targetVersion,
        String targetDigest,
        UUID childProcessInstanceId,
        UUID childTraversalId,
        FlowInvocationStatus status,
        byte[] input,
        byte[] result,
        String failureCode,
        String failureMessage,
        UUID continuationClaim,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant deadlineAt,
        Instant retainedUntil) {

    /** Maximum encoded input or result size stored in one relation. */
    public static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    /** Validates relation invariants and defensively copies payload bytes. */
    public FlowInvocationRecord {
        tenantId = bounded(tenantId, 256, "tenantId", false);
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(callerProcessInstanceId, "callerProcessInstanceId");
        Objects.requireNonNull(callerTraversalId, "callerTraversalId");
        Objects.requireNonNull(callerInvocationId, "callerInvocationId");
        callerSubject = bounded(callerSubject, 256, "callerSubject", false);
        Objects.requireNonNull(callerPrincipalType, "callerPrincipalType");
        callerIssuer = bounded(callerIssuer, 256, "callerIssuer", false);
        Objects.requireNonNull(targetDeploymentId, "targetDeploymentId");
        if (targetVersion < 1) throw new IllegalArgumentException("targetVersion must be positive");
        targetDigest = bounded(targetDigest, 128, "targetDigest", false);
        Objects.requireNonNull(status, "status");
        input = copyPayload(input, "input", false);
        result = copyPayload(result, "result", true);
        failureCode = bounded(failureCode == null ? "" : failureCode, 64, "failureCode", true);
        failureMessage = bounded(failureMessage == null ? "" : failureMessage, 256, "failureMessage", true);
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        Objects.requireNonNull(retainedUntil, "retainedUntil");
        if (updatedAt.isBefore(createdAt) || !deadlineAt.isAfter(createdAt)
                || !retainedUntil.isAfter(createdAt)) {
            throw new IllegalArgumentException("invalid invocation timestamps");
        }
        if ((childProcessInstanceId == null) != (childTraversalId == null)) {
            throw new IllegalArgumentException("child process and traversal must be recorded together");
        }
        // Child identity is allocated before the intent is committed. Recovery can therefore replay
        // the same acceptance after a crash without accidentally starting a second child.
        if (childTraversalId == null) {
            throw new IllegalArgumentException(status + " requires planned child identity");
        }
        if (status == FlowInvocationStatus.COMPLETED) {
            if (result == null || !failureCode.isEmpty() || !failureMessage.isEmpty()) {
                throw new IllegalArgumentException("COMPLETED requires only a result");
            }
        } else if (result != null) {
            throw new IllegalArgumentException("only COMPLETED may carry a result");
        }
    }

    /**
     * Returns input bytes without exposing the record's mutable array.
     * @return a defensive copy of the required canonical input bytes
     */
    @Override public byte[] input() { return input.clone(); }
    /**
     * Returns result bytes without exposing the record's mutable array.
     * @return a defensive copy of canonical result bytes, or {@code null}
     */
    @Override public byte[] result() { return result == null ? null : result.clone(); }

    /**
     * Reports whether no further child lifecycle transition is accepted.
     * @return whether the relation is terminal
     */
    public boolean terminal() { return status.terminal(); }

    private static byte[] copyPayload(byte[] value, String name, boolean nullable) {
        if (value == null) {
            if (nullable) return null;
            throw new IllegalArgumentException(name + " is required");
        }
        if (value.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        }
        return value.clone();
    }

    private static String bounded(String value, int maximum, String name, boolean blankAllowed) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        String normalized = value.strip();
        if ((!blankAllowed && normalized.isEmpty()) || normalized.length() > maximum
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }
}
