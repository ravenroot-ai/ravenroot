package ai.ravenroot.api.application;

/** Why an attempt reached {@link NodeAttemptStatus#COMPLETED}. */
public enum NodeAttemptCompletion {

    /** The runtime observed the attempt succeed. */
    SUCCEEDED,

    /** The attempt yielded to an externalized continuation. */
    WAIT,

    /**
     * A human asserted, after the fact, that a {@link NodeAttemptStatus#PARKED} attempt had in fact
     * completed (ADR 0022).
     *
     * <p>Distinct from {@link #SUCCEEDED} because the <em>provenance differs</em>, and provenance is
     * the whole point of parking: nobody observed this outcome. Reusing {@code SUCCEEDED} would forge
     * an observation that never happened, and would make the one case where the system admits it does
     * not know indistinguishable from the case where it does.</p>
     *
     * <p>It counts as a successful completion for invocation-completion purposes — an operator who
     * verified the effect landed has established exactly what {@code SUCCEEDED} would have — so
     * {@link NodeAttemptCompletion#successful()} is true for both.</p>
     */
    OPERATOR_VERIFIED;

/**
 * Whether this completion means the node's work was done, as opposed to deferred.
 * @return {@code true} for completed work accepted as successful, including operator verification.
 */
    public boolean successful() {
        return this == SUCCEEDED || this == OPERATOR_VERIFIED;
    }
}
