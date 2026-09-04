package ai.ravenroot.api.persistence;

import ai.ravenroot.api.security.SecurityContext;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable, bounded registration for one durable human decision.
 *
 * @param taskId deterministic task identity.
 * @param traversalId suspended traversal identity.
 * @param invocationId suspended node invocation identity.
 * @param attemptId suspended node attempt identity.
 * @param nodeId graph node awaiting the decision.
 * @param correlationKey generic handler correlation key.
 * @param deduplicationKey generic handler deduplication key.
 * @param metadata bounded graph-authored display copy.
 * @param responseSchema exact bounded response contract.
 * @param responderRequirements authorization required from a responder.
 * @param requester security context that created the task.
 * @param graphVersionPin immutable graph version used for re-entry.
 * @param escalateAt optional durable escalation deadline.
 * @param expiresAt required durable expiry deadline.
 * @param reentryMapping terminal status to graph-outcome mapping.
 * @param continuationVersion version of the trusted graph continuation envelope.
 * @param continuation bounded opaque continuation bytes; never projected to responders.
 * @param continuationDigest content binding for the continuation bytes.
 */
public record HumanTaskRegistration(
        UUID taskId,
        UUID traversalId,
        UUID invocationId,
        UUID attemptId,
        String nodeId,
        String correlationKey,
        String deduplicationKey,
        HumanTaskMetadata metadata,
        HumanTaskResponseSchema responseSchema,
        HandlerAuthorization responderRequirements,
        SecurityContext requester,
        GraphVersionPin graphVersionPin,
        Optional<Instant> escalateAt,
        Instant expiresAt,
        HumanTaskReentryMapping reentryMapping,
        int continuationVersion,
        byte[] continuation,
        String continuationDigest) {

    /** Source-compatible legacy registration; durable graph re-entry refuses its absent budget. */
    public HumanTaskRegistration(UUID taskId, UUID traversalId, UUID invocationId, UUID attemptId,
                                 String nodeId, String correlationKey, String deduplicationKey,
                                 HumanTaskMetadata metadata, HumanTaskResponseSchema responseSchema,
                                 HandlerAuthorization responderRequirements, SecurityContext requester,
                                 GraphVersionPin graphVersionPin, Optional<Instant> escalateAt,
                                 Instant expiresAt, HumanTaskReentryMapping reentryMapping) {
        this(taskId, traversalId, invocationId, attemptId, nodeId, correlationKey, deduplicationKey,
                metadata, responseSchema, responderRequirements, requester, graphVersionPin,
                escalateAt, expiresAt, reentryMapping, 1, new byte[0],
                ToolApprovalRegistration.digest(new byte[0]));
    }

    /** Validates identity, bounds, deadlines, authorization, and re-entry state. */
    public HumanTaskRegistration {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(attemptId, "attemptId");
        nodeId = HandlerRegistration.requireBoundedKey(nodeId, "nodeId");
        correlationKey = HandlerRegistration.requireBoundedKey(correlationKey, "correlationKey");
        deduplicationKey = HandlerRegistration.requireBoundedKey(deduplicationKey, "deduplicationKey");
        metadata = Objects.requireNonNull(metadata, "metadata");
        responseSchema = Objects.requireNonNull(responseSchema, "responseSchema");
        responderRequirements = Objects.requireNonNull(responderRequirements, "responderRequirements");
        requester = Objects.requireNonNull(requester, "requester");
        graphVersionPin = Objects.requireNonNull(graphVersionPin, "graphVersionPin");
        escalateAt = escalateAt == null ? Optional.empty() : escalateAt;
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (escalateAt.isPresent() && !escalateAt.orElseThrow().isBefore(expiresAt)) {
            throw new IllegalArgumentException("escalateAt must be before expiresAt");
        }
        reentryMapping = Objects.requireNonNull(reentryMapping, "reentryMapping");
        if (continuationVersion < 1) {
            throw new IllegalArgumentException("continuationVersion must be positive");
        }
        continuation = Objects.requireNonNull(continuation, "continuation").clone();
        if (continuation.length > ToolApprovalRegistration.MAX_CONTINUATION_BYTES) {
            throw new IllegalArgumentException("continuation exceeds "
                    + ToolApprovalRegistration.MAX_CONTINUATION_BYTES + " bytes");
        }
        continuationDigest = HandlerRegistration.requireBoundedKey(continuationDigest,
                "continuationDigest");
        if (!continuationDigest.matches("sha256:[0-9a-f]{64}")
                || !continuationDigest.equals(ToolApprovalRegistration.digest(continuation))) {
            throw new IllegalArgumentException("continuationDigest does not match continuation");
        }
    }

    @Override public byte[] continuation() { return continuation.clone(); }

    @Override public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof HumanTaskRegistration other)) return false;
        return taskId.equals(other.taskId) && traversalId.equals(other.traversalId)
                && invocationId.equals(other.invocationId) && attemptId.equals(other.attemptId)
                && nodeId.equals(other.nodeId) && correlationKey.equals(other.correlationKey)
                && deduplicationKey.equals(other.deduplicationKey) && metadata.equals(other.metadata)
                && responseSchema.equals(other.responseSchema)
                && responderRequirements.equals(other.responderRequirements)
                && requester.equals(other.requester) && graphVersionPin.equals(other.graphVersionPin)
                && escalateAt.equals(other.escalateAt) && expiresAt.equals(other.expiresAt)
                && reentryMapping.equals(other.reentryMapping)
                && continuationVersion == other.continuationVersion
                && Arrays.equals(continuation, other.continuation)
                && continuationDigest.equals(other.continuationDigest);
    }

    @Override public int hashCode() {
        int result = Objects.hash(taskId, traversalId, invocationId, attemptId, nodeId,
                correlationKey, deduplicationKey, metadata, responseSchema, responderRequirements,
                requester, graphVersionPin, escalateAt, expiresAt, reentryMapping,
                continuationVersion, continuationDigest);
        return 31 * result + Arrays.hashCode(continuation);
    }

    /**
     * Tests whether another registration is a safe redelivery of the same logical request.
     *
     * <p>Derived absolute deadlines are deliberately excluded so a later retry cannot conflict
     * with or extend the deadlines already committed by the first delivery.</p>
     *
     * @param other registration to compare.
     * @return {@code true} when the registration describes the same logical task request.
     */
    public boolean sameRequest(HumanTaskRegistration other) {
        if (other == null) return false;
        return taskId.equals(other.taskId)
                && traversalId.equals(other.traversalId)
                && invocationId.equals(other.invocationId)
                && attemptId.equals(other.attemptId)
                && nodeId.equals(other.nodeId)
                && correlationKey.equals(other.correlationKey)
                && deduplicationKey.equals(other.deduplicationKey)
                && metadata.equals(other.metadata)
                && responseSchema.equals(other.responseSchema)
                && responderRequirements.equals(other.responderRequirements)
                && requester.equals(other.requester)
                && graphVersionPin.equals(other.graphVersionPin)
                && reentryMapping.equals(other.reentryMapping)
                && continuationVersion == other.continuationVersion
                && Arrays.equals(continuation, other.continuation)
                && continuationDigest.equals(other.continuationDigest)
                && escalateAt.isPresent() == other.escalateAt.isPresent();
    }
}
