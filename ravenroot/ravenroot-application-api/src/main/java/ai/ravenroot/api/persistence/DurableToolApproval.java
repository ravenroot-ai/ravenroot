package ai.ravenroot.api.persistence;

/**
 * Stored state of one exact tool approval request.
 *
 * @param key process that owns the approval
 * @param request immutable request and continuation binding
 * @param status current durable lifecycle state
 * @param actor authenticated actor that made the decision, or an empty string for system transitions
 * @param revision monotonic approval revision used for optimistic concurrency
 */
public record DurableToolApproval(ExecutionKey key, ToolApprovalRegistration request,
                                  ToolApprovalStatus status, String actor, long revision) {
    /** Validates the stored approval state. */
    public DurableToolApproval {
        if (key == null) throw new IllegalArgumentException("key cannot be null");
        if (request == null) throw new IllegalArgumentException("request cannot be null");
        if (status == null) throw new IllegalArgumentException("status cannot be null");
        actor = actor == null ? "" : actor;
        if ((status == ToolApprovalStatus.APPROVED || status == ToolApprovalStatus.DENIED
                || status == ToolApprovalStatus.CANCELLED) && actor.isBlank()) {
            throw new IllegalArgumentException(status + " approval requires an actor");
        }
    }

    /**
     * Creates the initial pending state for a registered request.
     *
     * @param key process that owns the approval
     * @param request immutable request to approve or refuse
     * @param revision initial durable revision
     * @return pending approval state
     */
    public static DurableToolApproval pending(ExecutionKey key, ToolApprovalRegistration request,
                                              long revision) {
        return new DurableToolApproval(key, request, ToolApprovalStatus.PENDING, "", revision);
    }

    /**
     * Applies a legal transition and returns the next immutable state.
     *
     * @param transition lifecycle change targeting this approval
     * @param nextRevision revision assigned to the returned state
     * @return transitioned approval state
     */
    public DurableToolApproval apply(ToolApprovalTransition transition, long nextRevision) {
        if (!request.approvalId().equals(transition.approvalId())) {
            throw new IllegalArgumentException("transition targets a different approval");
        }
        if (!status.canTransitionTo(transition.next())) {
            throw new IllegalStateException("Illegal tool approval transition: " + status + " -> "
                    + transition.next());
        }
        String nextActor = transition.actor().isBlank() ? actor : transition.actor();
        return new DurableToolApproval(key, request, transition.next(), nextActor, nextRevision);
    }

    /**
     * Tests whether a transition exactly repeats the committed outcome.
     *
     * <p>Conflicting actors are not duplicates, and consumption is deliberately never replayable.</p>
     *
     * @param transition transition to compare with the stored outcome
     * @return {@code true} when the transition is an idempotent duplicate
     */
    public boolean alreadyApplied(ToolApprovalTransition transition) {
        return status == transition.next()
                && status != ToolApprovalStatus.CONSUMED
                && (transition.actor().isBlank() || actor.equals(transition.actor()));
    }
}
