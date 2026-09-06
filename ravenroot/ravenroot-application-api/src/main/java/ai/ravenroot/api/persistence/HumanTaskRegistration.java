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
 * @param executionLimits recovery-sensitive response and store-retry limits.
 * @param continuationVersion version of the trusted graph continuation envelope.
 * @param continuation bounded opaque continuation bytes; never projected to responders.
 * @param continuationDigest content binding for the continuation bytes.
 * @param confirmationPresentation immutable embedded presentation; classic when absent.
 * @param confirmationLimits effective presentation and comment bounds pinned at registration.
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
        HumanTaskExecutionLimits executionLimits,
        int continuationVersion,
        byte[] continuation,
        String continuationDigest,
        HumanTaskConfirmationPresentation confirmationPresentation,
        HumanTaskConfirmationLimits confirmationLimits) {

    /** Compatibility constructor retaining the registration shape before embedded confirmations. */
    public HumanTaskRegistration(UUID taskId, UUID traversalId, UUID invocationId, UUID attemptId,
                                 String nodeId, String correlationKey, String deduplicationKey,
                                 HumanTaskMetadata metadata, HumanTaskResponseSchema responseSchema,
                                 HandlerAuthorization responderRequirements, SecurityContext requester,
                                 GraphVersionPin graphVersionPin, Optional<Instant> escalateAt,
                                 Instant expiresAt, HumanTaskReentryMapping reentryMapping,
                                 HumanTaskExecutionLimits executionLimits, int continuationVersion,
                                 byte[] continuation, String continuationDigest) {
        this(taskId, traversalId, invocationId, attemptId, nodeId, correlationKey, deduplicationKey,
                metadata, responseSchema, responderRequirements, requester, graphVersionPin,
                escalateAt, expiresAt, reentryMapping, executionLimits, continuationVersion,
                continuation, continuationDigest, HumanTaskConfirmationPresentation.none(),
                HumanTaskConfirmationLimits.CLASSIC);
    }

    /**
     * Creates a source-compatible legacy registration without a trusted continuation budget.
     * Durable graph re-entry refuses the absent budget.
     *
     * @param taskId deterministic task identity
     * @param traversalId suspended traversal identity
     * @param invocationId suspended node invocation identity
     * @param attemptId suspended node attempt identity
     * @param nodeId graph node awaiting the decision
     * @param correlationKey generic handler correlation key
     * @param deduplicationKey generic handler deduplication key
     * @param metadata bounded graph-authored display copy
     * @param responseSchema exact bounded response contract
     * @param responderRequirements authorization required from a responder
     * @param requester security context that created the task
     * @param graphVersionPin immutable graph version used for re-entry
     * @param escalateAt optional durable escalation deadline
     * @param expiresAt required durable expiry deadline
     * @param reentryMapping terminal status to graph-outcome mapping
     */
    public HumanTaskRegistration(UUID taskId, UUID traversalId, UUID invocationId, UUID attemptId,
                                 String nodeId, String correlationKey, String deduplicationKey,
                                 HumanTaskMetadata metadata, HumanTaskResponseSchema responseSchema,
                                 HandlerAuthorization responderRequirements, SecurityContext requester,
                                 GraphVersionPin graphVersionPin, Optional<Instant> escalateAt,
                                 Instant expiresAt, HumanTaskReentryMapping reentryMapping) {
        this(taskId, traversalId, invocationId, attemptId, nodeId, correlationKey, deduplicationKey,
                metadata, responseSchema, responderRequirements, requester, graphVersionPin,
                escalateAt, expiresAt, reentryMapping,
                HumanTaskExecutionLimits.legacy(responseSchema.maxBytes()), 1, new byte[0],
                ToolApprovalRegistration.digest(new byte[0]));
    }

    /**
     * Compatibility constructor preserving the canonical registration shape before Human Task
     * execution limits were persisted explicitly. It derives the historical parser, raw-body, and
     * write-attempt contract from {@code responseSchema} while preserving the supplied trusted
     * continuation envelope.
     *
     * @param taskId deterministic task identity
     * @param traversalId suspended traversal identity
     * @param invocationId suspended node invocation identity
     * @param attemptId suspended node attempt identity
     * @param nodeId graph node awaiting the decision
     * @param correlationKey generic handler correlation key
     * @param deduplicationKey generic handler deduplication key
     * @param metadata bounded graph-authored display copy
     * @param responseSchema exact bounded response contract
     * @param responderRequirements authorization required from a responder
     * @param requester security context that created the task
     * @param graphVersionPin immutable graph version used for re-entry
     * @param escalateAt optional durable escalation deadline
     * @param expiresAt required durable expiry deadline
     * @param reentryMapping terminal status to graph-outcome mapping
     * @param continuationVersion positive version of the trusted graph continuation envelope
     * @param continuation bounded opaque continuation bytes, copied on construction
     * @param continuationDigest SHA-256 content binding for {@code continuation}
     */
    public HumanTaskRegistration(UUID taskId, UUID traversalId, UUID invocationId, UUID attemptId,
                                 String nodeId, String correlationKey, String deduplicationKey,
                                 HumanTaskMetadata metadata, HumanTaskResponseSchema responseSchema,
                                 HandlerAuthorization responderRequirements, SecurityContext requester,
                                 GraphVersionPin graphVersionPin, Optional<Instant> escalateAt,
                                 Instant expiresAt, HumanTaskReentryMapping reentryMapping,
                                 int continuationVersion, byte[] continuation,
                                 String continuationDigest) {
        this(taskId, traversalId, invocationId, attemptId, nodeId, correlationKey, deduplicationKey,
                metadata, responseSchema, responderRequirements, requester, graphVersionPin,
                escalateAt, expiresAt, reentryMapping,
                HumanTaskExecutionLimits.legacy(responseSchema.maxBytes()),
                continuationVersion, continuation, continuationDigest);
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
        executionLimits = Objects.requireNonNull(executionLimits, "executionLimits");
        if (executionLimits.responsePayload().maxEncodedBytes() != responseSchema.maxBytes()) {
            throw new IllegalArgumentException(
                    "response payload encoded-byte limit must match response schema maxBytes");
        }
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
        confirmationPresentation = Objects.requireNonNull(confirmationPresentation,
                "confirmationPresentation");
        confirmationLimits = Objects.requireNonNull(confirmationLimits, "confirmationLimits");
        if (!confirmationPresentation.embedded()
                && !HumanTaskConfirmationLimits.CLASSIC.equals(confirmationLimits)) {
            throw new IllegalArgumentException("classic human task cannot carry confirmation limits");
        }
    }

    /**
     * Returns an isolated copy of the trusted continuation envelope.
     *
     * @return copied continuation bytes
     */
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
                && executionLimits.equals(other.executionLimits)
                && continuationVersion == other.continuationVersion
                && Arrays.equals(continuation, other.continuation)
                && continuationDigest.equals(other.continuationDigest)
                && confirmationPresentation.equals(other.confirmationPresentation)
                && confirmationLimits.equals(other.confirmationLimits);
    }

    @Override public int hashCode() {
        int result = Objects.hash(taskId, traversalId, invocationId, attemptId, nodeId,
                correlationKey, deduplicationKey, metadata, responseSchema, responderRequirements,
                requester, graphVersionPin, escalateAt, expiresAt, reentryMapping,
                executionLimits, continuationVersion, continuationDigest, confirmationPresentation,
                confirmationLimits);
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
                && executionLimits.equals(other.executionLimits)
                && continuationVersion == other.continuationVersion
                && Arrays.equals(continuation, other.continuation)
                && continuationDigest.equals(other.continuationDigest)
                && confirmationPresentation.equals(other.confirmationPresentation)
                && confirmationLimits.equals(other.confirmationLimits)
                && escalateAt.isPresent() == other.escalateAt.isPresent();
    }
}
