package ai.ravenroot.api.persistence;

/**
 * Stored state of one first-class durable human task.
 *
 * @param key owning execution identity.
 * @param request immutable task registration.
 * @param status current lifecycle status.
 * @param actor bounded identity of the terminal responder, or an empty string before resolution.
 * @param generation optimistic decision fence, starting at one.
 * @param revision execution-store revision containing this state.
 */
public record DurableHumanTask(ExecutionKey key, HumanTaskRegistration request,
                               HumanTaskStatus status, String actor,
                               long generation, long revision) {
    /** Validates and normalizes stored human-task state. */
    public DurableHumanTask {
        if (key == null) throw new IllegalArgumentException("key cannot be null");
        if (request == null) throw new IllegalArgumentException("request cannot be null");
        if (status == null) throw new IllegalArgumentException("status cannot be null");
        actor = actor == null ? "" : actor;
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        if ((status == HumanTaskStatus.RESOLVED || status == HumanTaskStatus.DENIED
                || status == HumanTaskStatus.CANCELLED) && actor.isBlank()) {
            throw new IllegalArgumentException(status + " human task requires an actor");
        }
    }

    /**
     * Creates the initial waiting state for a registration.
     *
     * @param key owning execution identity.
     * @param request immutable task registration.
     * @param revision execution-store revision containing the registration.
     * @return waiting task at generation one.
     */
    public static DurableHumanTask waiting(ExecutionKey key, HumanTaskRegistration request,
                                           long revision) {
        return new DurableHumanTask(key, request, HumanTaskStatus.WAITING, "", 1L, revision);
    }

    /**
     * Applies one valid generation-fenced transition.
     *
     * @param transition transition to apply.
     * @param nextRevision execution-store revision containing the result.
     * @return immutable transitioned state.
     */
    public DurableHumanTask apply(HumanTaskTransition transition, long nextRevision) {
        if (!request.taskId().equals(transition.taskId())) {
            throw new IllegalArgumentException("transition targets a different human task");
        }
        if (transition.expectedGeneration() != generation) {
            throw new IllegalStateException("stale human-task generation");
        }
        if (!status.canTransitionTo(transition.next())) {
            throw new IllegalStateException("Illegal human-task transition: " + status + " -> "
                    + transition.next());
        }
        String nextActor = transition.actor().isBlank() ? actor : transition.actor();
        return new DurableHumanTask(key, request, transition.next(), nextActor,
                generation + 1L, nextRevision);
    }

    /**
     * Tests whether a transition is an exact redelivery of the preceding successful decision.
     *
     * @param transition transition that may already have been applied.
     * @return {@code true} when the transition is an idempotent redelivery.
     */
    public boolean alreadyApplied(HumanTaskTransition transition) {
        return status == transition.next()
                && transition.expectedGeneration() + 1L == generation
                && (transition.actor().isBlank() || actor.equals(transition.actor()));
    }
}
