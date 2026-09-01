package ai.ravenroot.api.application;

/**
 * Legal lifecycle states of one ordered node-delivery attempt.
 *
 * <p>Statuses are persisted by <strong>name</strong>, never by ordinal, so adding a member needs no
 * data migration. It does create a <strong>one-way rollback gate</strong>: the first row carrying
 * {@link #PARKED} is unreadable by a pre-PERS-04 binary, which surfaces it as
 * {@link ai.ravenroot.api.persistence.ExecutionStoreFailure.Corrupted} on replay rather than as a
 * silent misread. That mapping is asserted by the conformance suite, not left to be discovered.</p>
 */
public enum NodeAttemptStatus {
    /** Attempt is durable and eligible for dispatch. */
    SCHEDULED,
    /** Attempt was dispatched and its effect is in progress. */
    RUNNING,
    /** Attempt awaits an asynchronous continuation. */
    WAITING,
    /** Attempt completed with a successful node result. */
    COMPLETED,
    /** Attempt completed with a failure result. */
    FAILED,

    /**
     * Attempted, outcome unknown, not safe to repeat automatically (ADR 0022).
     *
     * <p>This is the recorded <em>disposition</em> of ambiguity, not the observation of it. The
     * observation is a redelivery ({@code PendingWork.deliveryAttempt > 1}) of an attempt already in
     * {@link #RUNNING}, which — given that the runtime persists {@code RUNNING} before the engine
     * send — means precisely "we dispatched it and never learned the outcome".</p>
     *
     * <p>Non-terminal, and deliberately <strong>not</strong> claimable: parking acknowledges the work
     * item in the same fenced batch, so a parked attempt leaves the claim loop instead of being
     * redelivered forever. It exits only through a human decision about the past — verified done,
     * verified failed, or retry as a <em>new</em> attempt.</p>
     */
    PARKED;

/**
 * Tests a proposed immediate transition without mutating the attempt.
 * @param next candidate successor status
 * @return whether the status machine allows the transition
 */
    public boolean canTransitionTo(NodeAttemptStatus next) {
        return switch (this) {
            case SCHEDULED -> next == RUNNING || next == FAILED;
            case RUNNING -> next == WAITING || next == COMPLETED || next == FAILED || next == PARKED;
            case WAITING -> next == RUNNING || next == FAILED;
            // PARKED -> RUNNING is deliberately absent (ADR 0022, documented contract 3): an effect whose
            // outcome is unknown is never resumed in place, because resuming in place would repeat an
            // effect that may already have happened. A deliberate retry is a new attempt with a new
            // ordinal and a new attemptId, and therefore a new effect identity.
            case PARKED -> next == COMPLETED || next == FAILED;
            case COMPLETED, FAILED -> false;
        };
    }

/**
 * Tests whether the attempt has a recorded final outcome.
 * @return {@code true} for completed and failed attempts
 */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED;
    }
}
