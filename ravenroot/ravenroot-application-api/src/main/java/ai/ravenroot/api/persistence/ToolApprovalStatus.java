package ai.ravenroot.api.persistence;

/** Durable lifecycle of one exact tool-call approval request. */
public enum ToolApprovalStatus {
    /** Awaiting an authorized decision. */
    PENDING,
    /** Approved and awaiting single-use redemption. */
    APPROVED,
    /** Explicitly denied with no effect. */
    DENIED,
    /** The store-clock deadline elapsed before effect authority was consumed, including after approval. */
    EXPIRED,
    /** Explicitly cancelled with no effect. */
    CANCELLED,
    /** The one-time grant was consumed immediately before effect. */
    CONSUMED,
    /** The consumed effect completed successfully. */
    SUCCEEDED,
    /** The consumed effect completed with known failure. */
    FAILED,
    /** The consumed effect outcome cannot be determined safely. */
    INDETERMINATE;

    /**
     * Returns whether no further transition is legal.
     * @return whether the state is terminal
     */
    public boolean terminal() {
        return switch (this) {
            case DENIED, EXPIRED, CANCELLED, SUCCEEDED, FAILED, INDETERMINATE -> true;
            case PENDING, APPROVED, CONSUMED -> false;
        };
    }

    /**
     * Tests whether this state can move directly to {@code next}.
     * @param next proposed successor
     * @return whether the transition is legal
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
