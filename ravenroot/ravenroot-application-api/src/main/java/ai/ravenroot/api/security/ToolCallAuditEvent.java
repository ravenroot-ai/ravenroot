package ai.ravenroot.api.security;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Payload-free evidence for one model-requested tool decision or effect.
 *
 * <p>No prompt, argument value, tool result, endpoint, or credential can be represented here. The
 * canonical argument digest binds the decision to content without retaining that content.</p>
 *
 * @param occurredAt server time at which the decision or effect outcome was observed
 * @param requestId trusted ingress request correlation
 * @param tenantId trusted tenant owning the invocation
 * @param principal qualified trusted principal identity
 * @param processInstanceId durable process containing the call
 * @param traversalId traversal containing the call
 * @param invocationId graph-node invocation requesting the call
 * @param attemptId execution attempt requesting the call
 * @param callId server-minted identity shared by attempt and terminal event
 * @param tool validated canonical tool identifier
 * @param argumentsDigest SHA-256 binding for canonical arguments, or empty when they were unreadable
 * @param disposition sanitized decision or effect outcome
 * @param reason stable payload-free reason token
 */
public record ToolCallAuditEvent(
        Instant occurredAt,
        String requestId,
        String tenantId,
        String principal,
        UUID processInstanceId,
        UUID traversalId,
        UUID invocationId,
        UUID attemptId,
        UUID callId,
        String tool,
        String argumentsDigest,
        Disposition disposition,
        String reason) {

    /** Validates the payload-free audit representation. */
    public ToolCallAuditEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        requestId = requireText(requestId, "requestId");
        tenantId = requireText(tenantId, "tenantId");
        principal = requireText(principal, "principal");
        Objects.requireNonNull(processInstanceId, "processInstanceId");
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(callId, "callId");
        tool = requireText(tool, "tool");
        if (!tool.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")) {
            throw new IllegalArgumentException("tool is not a canonical identifier");
        }
        argumentsDigest = argumentsDigest == null ? "" : argumentsDigest;
        if (!argumentsDigest.isEmpty() && !argumentsDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("argumentsDigest is not a SHA-256 binding");
        }
        Objects.requireNonNull(disposition, "disposition");
        reason = requireText(reason, "reason");
        if (!reason.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("reason is not a stable token");
        }
    }

    /** Lifecycle point represented by one event. */
    public enum Disposition {
        /** Policy authorized the call and the effect is about to be attempted. */
        ATTEMPT,
        /** Policy or argument validation denied the call without effect. */
        DENIED,
        /** Policy requires approval and the call performed no effect. */
        APPROVAL_REQUIRED,
        /** The authorized effect completed successfully. */
        SUCCEEDED,
        /** The authorized effect failed after it was attempted. */
        FAILED
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}
