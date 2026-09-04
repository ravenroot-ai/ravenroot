package ai.ravenroot.api.persistence;

import ai.ravenroot.api.security.SecurityContext;

import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable request for approval of one exact model-proposed tool effect.
 *
 * <p>Every authority-bearing component is structural and server supplied. Canonical arguments and
 * the continuation are bounded here before an adapter is asked to retain them; neither may be
 * projected into public audit payloads.</p>
 */
public record ToolApprovalRegistration(
        UUID approvalId,
        UUID traversalId,
        UUID invocationId,
        UUID attemptId,
        UUID callId,
        String nodeId,
        String tool,
        byte[] canonicalArguments,
        String argumentsDigest,
        SecurityContext requester,
        GraphVersionPin graphVersionPin,
        String policyVersion,
        Instant expiresAt,
        HandlerAuthorization approverRequirements,
        boolean requesterMayApprove,
        int continuationVersion,
        byte[] continuation,
        String continuationDigest) {

    public static final int MAX_ARGUMENT_BYTES = 64 * 1024;
    public static final int MAX_CONTINUATION_BYTES = 1024 * 1024;

    /** Validates and snapshots all mutable inputs. */
    public ToolApprovalRegistration {
        Objects.requireNonNull(approvalId, "approvalId");
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(callId, "callId");
        nodeId = HandlerRegistration.requireBoundedKey(nodeId, "nodeId");
        tool = HandlerRegistration.requireBoundedKey(tool, "tool");
        canonicalArguments = boundedCopy(canonicalArguments, MAX_ARGUMENT_BYTES, "canonicalArguments");
        argumentsDigest = HandlerRegistration.requireBoundedKey(argumentsDigest, "argumentsDigest");
        if (!argumentsDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("argumentsDigest must be a lower-case SHA-256 digest");
        }
        if (!argumentsDigest.equals(digest(canonicalArguments))) {
            throw new IllegalArgumentException("argumentsDigest does not match canonicalArguments");
        }
        requester = Objects.requireNonNull(requester, "requester");
        graphVersionPin = Objects.requireNonNull(graphVersionPin, "graphVersionPin");
        policyVersion = HandlerRegistration.requireBoundedKey(policyVersion, "policyVersion");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(approverRequirements, "approverRequirements");
        if (continuationVersion < 1) {
            throw new IllegalArgumentException("continuationVersion must be positive");
        }
        continuation = boundedCopy(continuation, MAX_CONTINUATION_BYTES, "continuation");
        continuationDigest = HandlerRegistration.requireBoundedKey(continuationDigest,
                "continuationDigest");
        if (!continuationDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("continuationDigest must be a lower-case SHA-256 digest");
        }
        if (!continuationDigest.equals(digest(continuation))) {
            throw new IllegalArgumentException("continuationDigest does not match continuation");
        }
    }

    @Override public byte[] canonicalArguments() { return canonicalArguments.clone(); }
    @Override public byte[] continuation() { return continuation.clone(); }

    /** Whether another registration is an exact replay of this request. */
    public boolean sameRequest(ToolApprovalRegistration other) {
        return other != null
                && approvalId.equals(other.approvalId)
                && traversalId.equals(other.traversalId)
                && invocationId.equals(other.invocationId)
                && attemptId.equals(other.attemptId)
                && callId.equals(other.callId)
                && nodeId.equals(other.nodeId)
                && tool.equals(other.tool)
                && Arrays.equals(canonicalArguments, other.canonicalArguments)
                && argumentsDigest.equals(other.argumentsDigest)
                && requester.equals(other.requester)
                && graphVersionPin.equals(other.graphVersionPin)
                && policyVersion.equals(other.policyVersion)
                && expiresAt.equals(other.expiresAt)
                && approverRequirements.equals(other.approverRequirements)
                && requesterMayApprove == other.requesterMayApprove
                && continuationVersion == other.continuationVersion
                && Arrays.equals(continuation, other.continuation)
                && continuationDigest.equals(other.continuationDigest);
    }

    private static byte[] boundedCopy(byte[] value, int limit, String name) {
        byte[] copied = Objects.requireNonNull(value, name).clone();
        if (copied.length > limit) {
            throw new IllegalArgumentException(name + " exceeds " + limit + " bytes");
        }
        return copied;
    }

    /** Returns the stable SHA-256 content binding used for arguments and continuations. */
    public static String digest(byte[] value) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }
}
