package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.nio.charset.StandardCharsets;

/**
 * Stored state of one first-class durable human task.
 *
 * @param key owning execution identity.
 * @param request immutable task registration.
 * @param status current lifecycle status.
 * @param actor bounded identity of the terminal responder, or an empty string before resolution.
 * @param decisionComment normalized terminal comment, kept separate from the execution payload.
 * @param generation optimistic decision fence, starting at one.
 * @param revision execution-store revision containing this state.
 * @param createdAt immutable store-clock time at which this task was registered.
 */
public record DurableHumanTask(ExecutionKey key, HumanTaskRegistration request,
                               HumanTaskStatus status, String actor,
                               String decisionComment, long generation, long revision, Instant createdAt) {
    /** Validates and normalizes stored human-task state. */
    public DurableHumanTask {
        if (key == null) throw new IllegalArgumentException("key cannot be null");
        if (request == null) throw new IllegalArgumentException("request cannot be null");
        if (status == null) throw new IllegalArgumentException("status cannot be null");
        actor = actor == null ? "" : actor;
        decisionComment = decisionComment == null ? "" : decisionComment;
        if (createdAt == null) throw new IllegalArgumentException("createdAt cannot be null");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        if ((status == HumanTaskStatus.RESOLVED || status == HumanTaskStatus.DENIED
                || status == HumanTaskStatus.CANCELLED) && actor.isBlank()) {
            throw new IllegalArgumentException(status + " human task requires an actor");
        }
    }

    /**
     * Compatibility constructor for rows and callers before decision comments were durable.
     * @param key owning execution identity
     * @param request immutable task registration
     * @param status current lifecycle status
     * @param actor bounded terminal responder identity
     * @param generation optimistic decision fence
     * @param revision execution-store revision containing this state
     */
    public DurableHumanTask(ExecutionKey key, HumanTaskRegistration request, HumanTaskStatus status,
                            String actor, long generation, long revision) {
        this(key, request, status, actor, "", generation, revision, Instant.EPOCH);
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
        return waiting(key, request, revision, Instant.EPOCH);
    }

    /**
     * Creates the initial waiting state using the store's authoritative registration time.
     * @param key owning execution identity
     * @param request immutable task registration
     * @param revision execution-store revision containing the registration
     * @param createdAt immutable store-clock registration time
     * @return waiting task at generation one
     */
    public static DurableHumanTask waiting(ExecutionKey key, HumanTaskRegistration request,
                                           long revision, Instant createdAt) {
        return new DurableHumanTask(key, request, HumanTaskStatus.WAITING, "", "", 1L, revision,
                createdAt);
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
        String normalizedComment = decisionTransition(transition)
                ? normalizePinnedComment(transition.comment()) : "";
        if (!normalizedComment.equals(transition.comment())) {
            throw new IllegalArgumentException("decision comment must already be normalized");
        }
        String nextComment = normalizedComment.isEmpty() ? decisionComment : normalizedComment;
        return new DurableHumanTask(key, request, transition.next(), nextActor, nextComment,
                generation + 1L, nextRevision, createdAt);
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
                && (transition.actor().isBlank() || actor.equals(transition.actor()))
                && decisionComment.equals(transition.comment());
    }

    private String normalizePinnedComment(String comment) {
        comment = comment == null ? "" : comment.strip();
        HumanTaskCommentRequirement requirement = request.confirmationPresentation()
                .commentRequirement();
        if (requirement == HumanTaskCommentRequirement.DISALLOWED && !comment.isEmpty()) {
            throw new IllegalArgumentException("decision comment is not allowed by this presentation");
        }
        if (requirement == HumanTaskCommentRequirement.REQUIRED && comment.isEmpty()) {
            throw new IllegalArgumentException("decision comment is required by this presentation");
        }
        for (int index = 0; index < comment.length(); index++) {
            char unit = comment.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 == comment.length() || !Character.isLowSurrogate(comment.charAt(index + 1))) {
                    throw new IllegalArgumentException("decision comment contains malformed Unicode");
                }
                index++;
            } else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException("decision comment contains malformed Unicode");
            } else if (Character.isISOControl(unit) && unit != '\n' && unit != '\t') {
                throw new IllegalArgumentException("decision comment contains a control character");
            }
        }
        if (comment.getBytes(StandardCharsets.UTF_8).length
                > request.confirmationLimits().maxCommentUtf8Bytes()) {
            throw new IllegalArgumentException("decision comment exceeds pinned byte limit");
        }
        return comment;
    }

    private static boolean decisionTransition(HumanTaskTransition transition) {
        return transition instanceof HumanTaskTransition.Resolved
                || transition instanceof HumanTaskTransition.Denied
                || transition instanceof HumanTaskTransition.Cancelled;
    }
}
