package ai.ravenroot.api.application;

/** Legal lifecycle states of one process instance. */
public enum ProcessInstanceStatus {
    /** Instance was admitted but has not started work. */
    ACCEPTED,
    /** Instance is currently progressing through its graph. */
    RUNNING,
    /** Instance awaits an asynchronous continuation. */
    WAITING,
    /** Instance completed normally. */
    COMPLETED,
    /** Instance ended because it could not complete. */
    FAILED;

/**
 * Tests a proposed immediate instance lifecycle transition.
 * @param next candidate successor state
 * @return whether the transition is permitted
 */
    public boolean canTransitionTo(ProcessInstanceStatus next) {
        return switch (this) {
            case ACCEPTED -> next == RUNNING || next == FAILED;
            case RUNNING -> next == WAITING || next == COMPLETED || next == FAILED;
            case WAITING -> next == RUNNING || next == FAILED;
            case COMPLETED, FAILED -> false;
        };
    }

/**
 * Tests whether this state admits no successor.
 * @return {@code true} when completed or failed
 */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED;
    }
}
