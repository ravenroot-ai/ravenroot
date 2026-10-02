package ai.ravenroot.api.persistence;

import java.time.Instant;

/** Positive post-quiescence proof that a retained source has no invocation still in flight.
 * @param source exact retained source
 * @param sourceRevision revision fenced by this proof
 * @param fencingToken source owner fence
 * @param manifestDigest exact source execution manifest
 * @param settledAt store-clock settlement time
 * @param retainedUntil store-authoritative expiry
 */
public record ReplaySourceSettlement(ExecutionKey source, long sourceRevision, long fencingToken,
                                     ExecutionManifestDigest manifestDigest, Instant settledAt,
                                     Instant retainedUntil) {
    /** Validates identities, fencing values, and retention ordering. */
    public ReplaySourceSettlement {
        if (source == null || manifestDigest == null || settledAt == null || retainedUntil == null) {
            throw new IllegalArgumentException("source settlement fields cannot be null");
        }
        if (sourceRevision < 1 || fencingToken < 1) {
            throw new IllegalArgumentException("source settlement requires positive revision and fence");
        }
        if (!retainedUntil.isAfter(settledAt)) {
            throw new IllegalArgumentException("source settlement retention must follow settlement");
        }
    }
}
