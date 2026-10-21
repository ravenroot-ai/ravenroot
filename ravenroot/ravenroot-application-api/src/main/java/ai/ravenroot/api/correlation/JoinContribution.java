package ai.ravenroot.api.correlation;

import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.security.SecurityContext;
import java.util.Objects;
import java.util.UUID;
import java.time.Instant;

/** Dedicated bounded join data and immutable cross-traversal causal coordinates; not history metadata.
 * @param contributionId distinct business item identity; delivery retries retain this identity
 * @param kind optional authored contribution category
 * @param processInstanceId original owning process identity, independent from a business history
 * @param traversalId actual traversal identity within the owning process
 * @param invocationId actual node-visit identity that owns this observation
 * @param attemptId actual delivery-attempt identity within the visit
 * @param security authenticated identity retained for the original causal admission
 * @param payload bounded retained contribution data, separate from public metadata
 * @param payloadDigest digest binding duplicate delivery to the same contribution content
 * @param acceptedAt time the original admission or contribution was accepted
 */
public record JoinContribution(String contributionId, String kind, UUID processInstanceId, UUID traversalId,
                               UUID invocationId, UUID attemptId, SecurityContext security,
                               OpaquePayload payload, String payloadDigest, Instant acceptedAt) {
    /** Validates bounded immutable item identity, original causal coordinates and content digest. */
    public JoinContribution {
        new CorrelationLimits(4096, 16384, 256, 10000, ai.ravenroot.api.payload.PayloadLimits.DEFAULTS).requireId(contributionId, "contributionId");
        if (kind != null) new CorrelationLimits(4096, 16384, 256, 10000, ai.ravenroot.api.payload.PayloadLimits.DEFAULTS).requireId(kind, "kind");
        Objects.requireNonNull(processInstanceId); Objects.requireNonNull(traversalId);
        Objects.requireNonNull(invocationId); Objects.requireNonNull(attemptId); Objects.requireNonNull(security);
        Objects.requireNonNull(payload); Objects.requireNonNull(acceptedAt);
        if (payloadDigest == null || !payloadDigest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid payload digest");
        if (!payloadDigest.equals(digest(payload.bytes()))) throw new IllegalArgumentException("payload digest mismatch");
    }
    /** Canonical byte identity detects redeliveries that reuse a business contribution with changed data.
 * @param bytes exact contribution bytes whose content identity is calculated
 * @return SHA-256 hexadecimal content identity for duplicate validation
 */
    public static String digest(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
