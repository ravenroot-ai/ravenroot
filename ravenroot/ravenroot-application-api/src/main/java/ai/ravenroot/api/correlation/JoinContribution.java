package ai.ravenroot.api.correlation;

import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.security.SecurityContext;
import java.util.Objects;
import java.util.UUID;
import java.time.Instant;

/** Dedicated bounded join data and immutable cross-traversal causal coordinates; not history metadata. */
public record JoinContribution(String contributionId, String kind, UUID processInstanceId, UUID traversalId,
                               UUID invocationId, UUID attemptId, SecurityContext security,
                               OpaquePayload payload, String payloadDigest, Instant acceptedAt) {
    public JoinContribution {
        new CorrelationLimits(4096, 16384, 256, 10000, ai.ravenroot.api.payload.PayloadLimits.DEFAULTS).requireId(contributionId, "contributionId");
        if (kind != null) new CorrelationLimits(4096, 16384, 256, 10000, ai.ravenroot.api.payload.PayloadLimits.DEFAULTS).requireId(kind, "kind");
        Objects.requireNonNull(processInstanceId); Objects.requireNonNull(traversalId);
        Objects.requireNonNull(invocationId); Objects.requireNonNull(attemptId); Objects.requireNonNull(security);
        Objects.requireNonNull(payload); Objects.requireNonNull(acceptedAt);
        if (payloadDigest == null || !payloadDigest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid payload digest");
        if (!payloadDigest.equals(digest(payload.bytes()))) throw new IllegalArgumentException("payload digest mismatch");
    }
    /** Canonical byte identity detects redeliveries that reuse a business contribution with changed data. */
    public static String digest(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
