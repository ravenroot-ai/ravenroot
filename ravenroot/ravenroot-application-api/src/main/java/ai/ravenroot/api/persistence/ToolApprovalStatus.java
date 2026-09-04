package ai.ravenroot.api.persistence;

/** Durable lifecycle of one exact tool-call approval request. */
public enum ToolApprovalStatus {
    PENDING,
    APPROVED,
    DENIED,
    EXPIRED,
    CANCELLED,
    CONSUMED,
    SUCCEEDED,
    FAILED,
    INDETERMINATE;

    /** Returns whether no further transition is legal. */
    public boolean terminal() {
        return switch (this) {
            case DENIED, EXPIRED, CANCELLED, SUCCEEDED, FAILED, INDETERMINATE -> true;
            case PENDING, APPROVED, CONSUMED -> false;
        };
    }

    /** Returns whether this state can move directly to {@code next}. */
    public boolean canTransitionTo(ToolApprovalStatus next) {
        return switch (this) {
            case PENDING -> next == APPROVED || next == DENIED || next == EXPIRED || next == CANCELLED;
            case APPROVED -> next == CONSUMED || next == EXPIRED || next == CANCELLED;
            case CONSUMED -> next == SUCCEEDED || next == FAILED || next == INDETERMINATE;
            case DENIED, EXPIRED, CANCELLED, SUCCEEDED, FAILED, INDETERMINATE -> false;
        };
    }
}
