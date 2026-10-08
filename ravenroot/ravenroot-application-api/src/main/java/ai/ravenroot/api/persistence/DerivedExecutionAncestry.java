package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.util.List;

/** Immutable ancestry of a newly admitted selective derived execution.
 * @param derived fresh derived execution
 * @param source immutable source identity
 * @param boundarySeeds exact requested boundaries
 * @param pendingWork copied first dispatch recoverable without the source
 * @param requestFingerprint deterministic idempotency fingerprint
 * @param requester authenticated requester identity
 * @param reason operator reason
 * @param repeatabilityDecision recorded external-effect decision
 * @param admittedAt admission time
 */
public record DerivedExecutionAncestry(ExecutionKey derived, ExecutionKey source,
                                       List<ReplayBoundarySeed> boundarySeeds,
                                       DerivedExecutionWork pendingWork,
                                       String requestFingerprint, String requester, String reason,
                                       String repeatabilityDecision, Instant admittedAt) {
    /** Maximum persisted ancestry text length. */
    public static final int MAX_TEXT_LENGTH = 500;

    /** Validates cross-record identity and bounded immutable values. */
    public DerivedExecutionAncestry {
        if (derived == null || source == null || pendingWork == null || admittedAt == null) {
            throw new IllegalArgumentException("derived ancestry identities and time cannot be null");
        }
        if (!derived.tenantId().equals(source.tenantId())) {
            throw new IllegalArgumentException("derived ancestry cannot cross tenants");
        }
        if (derived.equals(source)) throw new IllegalArgumentException("derived and source identities must differ");
        boundarySeeds = List.copyOf(boundarySeeds == null ? List.of() : boundarySeeds);
        if (boundarySeeds.isEmpty() || boundarySeeds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("at least one pending boundary seed is required");
        }
        if (!pendingWork.derived().equals(derived)
                || !boundarySeeds.contains(pendingWork.boundary())) {
            throw new IllegalArgumentException("pending derived work must match its ancestry");
        }
        requestFingerprint = bounded(requestFingerprint, "requestFingerprint");
        requester = bounded(requester, "requester");
        reason = bounded(reason, "reason");
        repeatabilityDecision = repeatabilityDecision == null ? "" : repeatabilityDecision.strip();
        if (repeatabilityDecision.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("repeatabilityDecision is too long");
        }
    }

    private static String bounded(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
        value = value.strip();
        if (value.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException(name + " is too long");
        return value;
    }
}
