package ai.ravenroot.api.persistence;

/** Durable lifecycle of one exact tool-call approval request. */
public enum ToolApprovalStatus {
    /** Waiting for an authorized decision. */
    PENDING,
    /** Approved, with the one-time effect not yet consumed. */
    APPROVED,
    /** Refused by an authorized decision actor. */
    DENIED,
    /** Refused because its absolute decision deadline elapsed. */
    EXPIRED,
    /** Cancelled before an effect was consumed. */
    CANCELLED,
    /** One-time authority consumed immediately before attempting the effect. */
    CONSUMED,
    /** Consumed effect completed successfully. */
    SUCCEEDED,
    /** Consumed effect completed with a known failure. */
    FAILED,
    /** Effect outcome cannot be established safely. */
    INDETERMINATE;

    /**
     * Returns whether no further transition is legal.
     *
     * @return {@code true} for a terminal state
     */
    public boolean terminal() {
        return switch (this) {
            case DENIED, EXPIRED, CANCELLED, SUCCEEDED, FAILED, INDETERMINATE -> true;
            case PENDING, APPROVED, CONSUMED -> false;
        };
    }

    /**
     * Returns whether this state can move directly to {@code next}.
     *
     * @param next proposed successor state
     * @return {@code true} when the direct transition is legal
     */
    public boolean canTransitionTo(ToolApprovalStatus next) {
        return switch (this) {
            case PENDING -> next == APPROVED || next == DENIED || next == EXPIRED || next == CANCELLED;
            case APPROVED -> next == CONSUMED || next == EXPIRED || next == CANCELLED;
            case CONSUMED -> next == SUCCEEDED || next == FAILED || next == INDETERMINATE;
            case DENIED, EXPIRED, CANCELLED, SUCCEEDED, FAILED, INDETERMINATE -> false;
        };
    }
}
