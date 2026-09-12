package ai.ravenroot.api.persistence;

/**
 * Legal lifecycle states of one durable handler (PERS-05).
 *
 * <p>The state machine is the whole of the idempotency guarantee. A handler carries no deadline, no
 * retry counter and no deduplication window: whether a transition may be applied is decided by the
 * stored status alone, so "duplicate" and "late" are the same deterministic refusal and neither
 * needs a clock. That is what lets an adapter answer identically on every retry, before and after a
 * restart.</p>
 *
 * <p>{@link #WAITING} and {@link #ESCALATED} are the only states a trigger may act on.
 * {@link #ESCALATED} is deliberately <em>not</em> terminal: escalating a human task raises its
 * visibility, it does not close it, and a task that could no longer be resolved after being
 * escalated would strand exactly the work escalation exists to unstick.</p>
 */
public enum HandlerStatus {

    /** Registered and awaiting an authorized trigger. The state every handler starts in. */
    WAITING,

    /**
     * Still awaiting a trigger, and marked as having exceeded whatever attention threshold the
     * runtime declared. Non-terminal: a resolution or a denial from here is ordinary, not late.
     */
    ESCALATED,

    /** An authorized trigger supplied an outcome. Terminal, and resumes the process. */
    RESOLVED,

    /** An authorized principal refused the task. Terminal, and resumes the process. */
    DENIED,

    /** The wait ended without a trigger. Terminal, and resumes the process. */
    EXPIRED;

    /**
     * Tests whether this state permits a direct transition to another state.
     *
     * <p>Re-entering the same state is <strong>not</strong> a legal transition here even though a
     * repeated escalation is answered as a no-op success by the store. Replay tolerance is a
     * property of how the store applies a transition, not of the state machine, and folding it into
     * this predicate would also make a second resolution look legal.</p>
     * @param next candidate successor state.
     * @return {@code true} only for a legal immediate transition.
     */
    public boolean canTransitionTo(HandlerStatus next) {
        return switch (this) {
            case WAITING -> next == ESCALATED || next == RESOLVED || next == DENIED || next == EXPIRED;
            case ESCALATED -> next == RESOLVED || next == DENIED || next == EXPIRED;
            case RESOLVED, DENIED, EXPIRED -> false;
        };
    }

    /**
     * Tests whether no further transition is legal.
     * @return {@code true} for resolved, denied and expired handlers.
     */
    public boolean terminal() {
        return this == RESOLVED || this == DENIED || this == EXPIRED;
    }

    /**
     * Tests whether reaching this state enqueues a {@link PendingWork.HandlerTrigger}.
     *
     * <p>Identical to {@link #terminal()} today, and stated separately because they answer different
     * questions: one is about the state machine, the other about what the store owes the claim loop.
     * A future non-resuming terminal state would make them diverge, and a caller reading the wrong
     * one would silently strand a process.</p>
     * @return {@code true} when entering this state produces exactly one durable trigger.
     */
    public boolean resumesProcess() {
        return terminal();
    }
}
