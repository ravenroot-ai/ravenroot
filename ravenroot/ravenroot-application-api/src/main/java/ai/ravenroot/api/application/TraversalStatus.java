package ai.ravenroot.api.application;

/** Legal lifecycle states of one ingress or re-entry traversal. */
public enum TraversalStatus {
    /** Traversal was admitted but has not started node work. */
    ACCEPTED,
    /** Traversal is executing or selecting the next node. */
    RUNNING,
    /** Traversal is suspended until an asynchronous condition resumes it. */
    WAITING,
    /** Traversal reached its normal terminal outcome. */
    COMPLETED,
    /** Traversal reached an unrecoverable terminal outcome. */
    FAILED;

/**
 * Tests whether this lifecycle state permits a direct transition to another state.
 * @param next candidate successor state
 * @return {@code true} only for a legal immediate transition
 */
    public boolean canTransitionTo(TraversalStatus next) {
        return switch (this) {
            case ACCEPTED -> next == RUNNING || next == FAILED;
            case RUNNING -> next == WAITING || next == COMPLETED || next == FAILED;
            case WAITING -> next == RUNNING || next == FAILED;
            case COMPLETED, FAILED -> false;
        };
    }

/**
 * Tests whether no further lifecycle transition is legal.
 * @return {@code true} for completed or failed traversals
 */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED;
    }
}
