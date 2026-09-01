package ai.ravenroot.api.application;

/** Legal lifecycle states of one visit to a logical graph node. */
public enum NodeInvocationStatus {
    /** Invocation is ready to be dispatched. */
    SCHEDULED,
    /** Invocation has begun node execution. */
    RUNNING,
    /** Invocation awaits a continuation before it can finish. */
    WAITING,
    /** Invocation completed successfully. */
    COMPLETED,
    /** Invocation ended with a failure. */
    FAILED;

/**
 * Returns whether this invocation may move directly to {@code next}.
 * @param next candidate successor status
 * @return whether this status can move directly to {@code next}
 */
    public boolean canTransitionTo(NodeInvocationStatus next) {
        return switch (this) {
            case SCHEDULED -> next == RUNNING || next == FAILED;
            case RUNNING -> next == WAITING || next == COMPLETED || next == FAILED;
            case WAITING -> next == RUNNING || next == FAILED;
            case COMPLETED, FAILED -> false;
        };
    }

/**
 * Tests whether the invocation is finished.
 * @return {@code true} for completed and failed invocations
 */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED;
    }
}
