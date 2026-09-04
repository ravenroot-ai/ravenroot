package ai.ravenroot.api.persistence;

/**
 * Stored state of one durable operator hold.
 *
 * <p>It is to {@link ExecutionPauseRegistration} what {@link DurableToolApproval} is to
 * {@link ToolApprovalRegistration}: the caller-authored hold plus everything the store owns.</p>
 *
 * <p>A terminal hold is <strong>retained, not deleted</strong>, for the reason {@link DurableHandler}
 * gives: it is the evidence a redelivered resume is refused against, and the record that says who
 * released or gave up a traversal an operator had deliberately stopped. It is removed only when the
 * process instance it belongs to is removed by retention.</p>
 *
 * @param key      tenant-scoped identity of the owning process instance
 * @param request  the caller-authored hold, including its bounded continuation
 * @param status   current lifecycle state of the hold
 * @param actor    audit-stable identity that settled the hold, or the empty string while it is held
 * @param revision the process-instance revision at which this hold last changed
 */
public record DurableExecutionPause(ExecutionKey key, ExecutionPauseRegistration request,
                                    ExecutionPauseStatus status, String actor, long revision) {

    /** Validates a stored hold and the consistency of its actor with its status. */
    public DurableExecutionPause {
        if (key == null) throw new IllegalArgumentException("key cannot be null");
        if (request == null) throw new IllegalArgumentException("request cannot be null");
        if (status == null) throw new IllegalArgumentException("status cannot be null");
        actor = actor == null ? "" : actor;
        // A settled hold without an actor would be a record saying a deliberate decision was taken
        // and refusing to say by whom, in the one place an operator reads to find out.
        if (status.terminal() && actor.isBlank()) {
            throw new IllegalArgumentException(status + " hold requires an actor");
        }
    }

    /**
     * Creates the initial stored view of a freshly committed hold.
     *
     * @param key the owning process instance.
     * @param request the caller-authored hold.
     * @param revision process-instance revision at which the hold commits.
     * @return a held pause carrying no actor.
     */
    public static DurableExecutionPause held(ExecutionKey key, ExecutionPauseRegistration request,
                                             long revision) {
        return new DurableExecutionPause(key, request, ExecutionPauseStatus.HELD, "", revision);
    }

    /**
     * Applies a legal transition, returning the resulting stored view.
     *
     * @param transition legal transition to fold over this hold.
     * @param nextRevision process-instance revision at which the transition commits.
     * @return hold state after the transition.
     */
    public DurableExecutionPause apply(ExecutionPauseTransition transition, long nextRevision) {
        if (transition == null) throw new IllegalArgumentException("transition cannot be null");
        if (!request.pauseId().equals(transition.pauseId())) {
            throw new IllegalArgumentException("transition targets hold " + transition.pauseId()
                    + " but was applied to " + request.pauseId());
        }
        if (!status.canTransitionTo(transition.next())) {
            throw new IllegalStateException("Illegal execution pause transition: " + status + " -> "
                    + transition.next());
        }
        return new DurableExecutionPause(key, request, transition.next(), transition.actor(), nextRevision);
    }

    /**
     * Tests whether this transition has already been applied, so a redelivery is a no-op success.
     *
     * <p>The acting principal is part of the identity of the settlement: two different principals
     * asking for the same outcome are two decisions, and answering the second as a duplicate would
     * record the wrong one in the audit position.</p>
     *
     * @param transition transition offered again.
     * @return whether this hold already carries exactly that settlement.
     */
    public boolean alreadyApplied(ExecutionPauseTransition transition) {
        return status == transition.next() && actor.equals(transition.actor());
    }
}
