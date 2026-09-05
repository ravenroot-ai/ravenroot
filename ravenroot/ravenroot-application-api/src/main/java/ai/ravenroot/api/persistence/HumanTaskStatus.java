package ai.ravenroot.api.persistence;

/** Durable lifecycle of a first-class human task. */
public enum HumanTaskStatus {
    /** Waiting for an authorized responder. */
    WAITING,
    /** Still waiting after the durable escalation deadline. */
    ESCALATED,
    /** Completed with a response matching the declared contract. */
    RESOLVED,
    /** Completed by an authorized denial. */
    DENIED,
    /** Completed when the durable expiry deadline elapsed. */
    EXPIRED,
    /** Completed by an authorized cancellation. */
    CANCELLED;

    /**
     * Reports whether no further lifecycle transition is allowed.
     *
     * @return {@code true} for a terminal status.
     */
    public boolean terminal() {
        return switch (this) {
            case RESOLVED, DENIED, EXPIRED, CANCELLED -> true;
            case WAITING, ESCALATED -> false;
        };
    }

    /**
     * Tests whether the lifecycle permits a requested successor.
     *
     * @param next requested successor.
     * @return {@code true} when the transition is legal.
     */
    public boolean canTransitionTo(HumanTaskStatus next) {
        if (next == null) return false;
        return switch (this) {
            case WAITING -> next == ESCALATED || next.terminal();
            case ESCALATED -> next.terminal();
            case RESOLVED, DENIED, EXPIRED, CANCELLED -> false;
        };
    }
}
