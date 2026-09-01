package ai.ravenroot.api.persistence;

/**
 * Coarse classification every {@link ExecutionStoreFailure} exposes (ADR 0010 section 12), so a
 * caller can decide what to do without pattern-matching every member.
 */
public enum Retryability {
    /** The request was rejected and will be rejected identically on retry. Nothing was applied. */
    DETERMINISTIC_REJECT,

    /** The write definitely did not apply. Retrying the identical request is safe. */
    RETRYABLE_NO_EFFECT,

    /**
     * Nothing was applied, but the request is <em>stale</em> and must be rebuilt from fresh state
     * before retrying.
     *
     * <p>Distinct from {@link #RETRYABLE_NO_EFFECT} for a concrete reason: that value invites a
     * blind retry of the identical request after backoff, which here would fail forever, because the
     * expectation the request carries is stale by definition. Collapsing the two turns a correct
     * backoff loop into an infinite one.</p>
     */
    RETRY_AFTER_REREAD,

    /**
     * The write may or may not have applied. The caller must resolve it by re-reading the revision
     * or replaying under its idempotency key; it must never assume either outcome.
     */
    INDETERMINATE
}
