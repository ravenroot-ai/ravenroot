package ai.ravenroot.api.persistence;

import java.time.Instant;

/**
 * A previously recorded idempotency outcome (ADR 0010 section 6).
 *
 * <p>A successful replay is a <em>success</em>, not a failure: the caller receives the recorded
 * outcome and the revision at which it was recorded, and does not re-execute.</p>
 *
 * @param recordedAtRevision the process-instance revision the outcome was recorded at, so the caller
 *                           can correlate the answer with the aggregate it is holding
 * @param expiresAt          the earliest instant, on the store's clock, at which the store is
 *                           permitted to forget this record
 * @param key the stable key used to identify the requested resource.
 * @param requestFingerprint canonical request fingerprint retained for idempotency.
 * @param outcomeRef reference to the retained idempotent outcome.
 */
public record IdempotencyRecord(String key, OpaquePayload requestFingerprint, OpaquePayload outcomeRef,
                                long recordedAtRevision, Instant expiresAt) {
    /** Validates the retained idempotency outcome and its expiry boundary. */
    public IdempotencyRecord {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key cannot be blank");
        if (requestFingerprint == null) throw new IllegalArgumentException("requestFingerprint cannot be null");
        if (outcomeRef == null) throw new IllegalArgumentException("outcomeRef cannot be null");
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt cannot be null");
    }
}
