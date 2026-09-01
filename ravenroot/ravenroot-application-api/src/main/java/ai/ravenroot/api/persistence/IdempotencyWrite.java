package ai.ravenroot.api.persistence;

import java.time.Duration;
import java.time.Instant;

/**
 * An idempotency record written inside a batch (ADR 0010 section 6).
 *
 * <p>The port defines no retention <em>policy</em>, but it does define retention <em>semantics</em>.
 * The window is a <strong>mandatory</strong> field here because only the caller knows its real retry
 * window: an adapter-invented policy could purge a key inside that window and silently reintroduce
 * duplicate execution. A store may forget a key only after the window elapses, and a replay of a
 * forgotten key fails with {@link ExecutionStoreFailure.IdempotencyRecordExpired} rather than being
 * answered as absent — because "absent" would read as "never applied" and re-execute.</p>
 *
 * @param key                caller-supplied opaque key, unique per {@code (tenantId, key)}
 * @param requestFingerprint distinguishes a genuine replay from a different request reusing the key;
 *                           a mismatch fails with {@link ExecutionStoreFailure.IdempotencyConflict}
 * @param outcomeRef         reference to the recorded outcome, so a replay is answerable without
 *                           re-execution
 * @param retentionWindow    minimum duration the record must be retained; strictly positive
 * @param keyIssuedAt        the instant the caller minted this key. Mandatory, because it is what
 *                           makes the <em>absence</em> of a record decidable against the store's
 *                           {@code forgottenBefore} watermark. Supplying it on the write as well as
 *                           the lookup is what lets a skewed clock be rejected while it can still be
 *                           fixed, rather than after the damage window has passed
 */
public record IdempotencyWrite(String key, OpaquePayload requestFingerprint, OpaquePayload outcomeRef,
                               Duration retentionWindow, Instant keyIssuedAt) {
    /** Validates an idempotency write that must share the enclosing transaction. */
    public IdempotencyWrite {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("idempotency key cannot be blank");
        if (requestFingerprint == null) throw new IllegalArgumentException("requestFingerprint cannot be null");
        if (outcomeRef == null) throw new IllegalArgumentException("outcomeRef cannot be null");
        if (retentionWindow == null || retentionWindow.isZero() || retentionWindow.isNegative()) {
            throw new IllegalArgumentException("retentionWindow must be positive and is mandatory");
        }
        if (keyIssuedAt == null) throw new IllegalArgumentException("keyIssuedAt is mandatory");
    }
}
