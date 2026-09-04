package ai.ravenroot.api.persistence;

/** Stored state of one exact tool approval request. */
public record DurableToolApproval(ExecutionKey key, ToolApprovalRegistration request,
                                  ToolApprovalStatus status, String actor, long revision) {
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

    public static DurableToolApproval pending(ExecutionKey key, ToolApprovalRegistration request,
                                              long revision) {
        return new DurableToolApproval(key, request, ToolApprovalStatus.PENDING, "", revision);
    }

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

    /** Exact duplicate transitions are no-op successes; conflicting actors are not duplicates. */
    public boolean alreadyApplied(ToolApprovalTransition transition) {
        return status == transition.next()
                && status != ToolApprovalStatus.CONSUMED
                && (transition.actor().isBlank() || actor.equals(transition.actor()));
    }
}
