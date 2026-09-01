package ai.ravenroot.api.audit;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The report {@link AuditTrail#verify} returns: what an independent verifier examined and what it
 * found, never a bare boolean. The contract requires tampering or a gap to be detected
 * <strong>and reported</strong>, so a caller must be able to say what was wrong and where, not only
 * that something was.
 * @param tenantId tenant whose chain was inspected
 * @param checkedThroughSequence highest sequence observed during verification
 * @param anomalies immutable anomalies found in chain order
 * @param verifiedAt instant at which verification completed
 */
public record ChainVerificationResult(String tenantId, long checkedThroughSequence,
                                      List<ChainAnomaly> anomalies, Instant verifiedAt) {

/**
 * Snapshots the anomaly list and rejects invalid tenant or verification metadata.
 */
    public ChainVerificationResult {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be blank");
        }
        anomalies = List.copyOf(Objects.requireNonNull(anomalies, "anomalies"));
        Objects.requireNonNull(verifiedAt, "verifiedAt");
    }

/**
 * {@code true} when verification found nothing wrong. An empty tenant chain is trivially intact.
 * @return {@code true} when no anomaly was found, including for an empty chain
 */
    public boolean intact() {
        return anomalies.isEmpty();
    }
}
