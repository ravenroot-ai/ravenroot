package ai.ravenroot.api.persistence;

/**
 * Durable lifecycle of one operator hold on a traversal.
 *
 * <p>Three states and no more, because a hold answers exactly one question: is this traversal still
 * being held, and if not, what ended the hold. {@link #HELD} is the only non-terminal state, so
 * "still paused" is decidable from the stored status alone without a clock — which is what lets a
 * process that has just restarted answer it identically to the process that wrote it.</p>
 *
 * <p>There is deliberately no {@code EXPIRED}. A hold has no deadline: {@code PauseResult} already
 * states that a traversal holds until it is resumed or cancelled, and giving a durable hold a
 * timeout would mean a restart could silently release work an operator had deliberately stopped.</p>
 */
public enum ExecutionPauseStatus {

    /** The traversal is durably held and no node after the pause boundary may run. */
    HELD,

    /** An authorized principal released the hold. Terminal, and continues the traversal. */
    RESUMED,

    /** The hold ended without continuing: the traversal was cancelled or its process gave it up. */
    CANCELLED;

    /**
     * Tests whether this state permits a direct transition to another state.
     *
     * @param next candidate successor state.
     * @return {@code true} only for a legal immediate transition.
     */
    public boolean canTransitionTo(ExecutionPauseStatus next) {
        return switch (this) {
            case HELD -> next == RESUMED || next == CANCELLED;
            case RESUMED, CANCELLED -> false;
        };
    }

    /**
     * Tests whether no further transition is legal.
     *
     * @return {@code true} for resumed and cancelled holds.
     */
    public boolean terminal() {
        return this != HELD;
    }

    /**
     * Tests whether reaching this state continues the traversal from its committed boundary.
     *
     * <p>Stated separately from {@link #terminal()} because they answer different questions, and a
     * caller reading the wrong one would either strand a traversal or re-enter a cancelled one.</p>
     *
     * @return {@code true} only for {@link #RESUMED}.
     */
    public boolean continuesTraversal() {
        return this == RESUMED;
    }
}
