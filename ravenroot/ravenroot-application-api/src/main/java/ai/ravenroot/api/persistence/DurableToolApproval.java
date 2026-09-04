package ai.ravenroot.api.persistence;

/**
 * Stored state of one exact tool approval request.
 *
 * @param key owning execution identity
 * @param request immutable approval request
 * @param status current durable approval state
 * @param actor bounded identity from the latest actor-bearing decision; it is retained through
 *              actorless consumption and effect-outcome transitions, may identify a system
 *              cancellation, and is empty before any actor-bearing transition
 * @param revision revision of the enclosing process snapshot in the execution store
 */
public record DurableToolApproval(ExecutionKey key, ToolApprovalRegistration request,
                                  ToolApprovalStatus status, String actor, long revision) {
    /** Validates the durable approval projection. */
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
     * Creates a pending approval.
     *
     * @param key owning execution identity
     * @param request immutable request
     * @param revision revision of the enclosing process snapshot in the execution store
     * @return pending approval snapshot
     */
    public static DurableToolApproval pending(ExecutionKey key, ToolApprovalRegistration request,
                                              long revision) {
        return new DurableToolApproval(key, request, ToolApprovalStatus.PENDING, "", revision);
    }

    /**
     * Applies one legal state transition.
     *
     * @param transition requested transition
     * @param nextRevision revision of the enclosing process snapshot that contains the result
     * @return transitioned approval snapshot
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
     * Tests whether a transition is an exact duplicate of the state already stored.
     *
     * @param transition transition to compare
     * @return {@code true} when replay is an idempotent no-op
     */
    public boolean alreadyApplied(ToolApprovalTransition transition) {
        return status == transition.next()
                && status != ToolApprovalStatus.CONSUMED
                && (transition.actor().isBlank() || actor.equals(transition.actor()));
    }
}
