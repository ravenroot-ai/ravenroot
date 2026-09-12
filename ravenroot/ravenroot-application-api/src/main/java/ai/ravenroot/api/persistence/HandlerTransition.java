package ai.ravenroot.api.persistence;

import java.util.UUID;

/**
 * The unit of change to a durable handler: a <em>legal handler transition</em>, never a field patch
 * (PERS-05).
 *
 * <p>This mirrors {@link ExecutionTransition}'s discipline one level down. Nothing expressible here
 * can move a handler into a state {@link HandlerStatus#canTransitionTo(HandlerStatus)} rejects, so
 * an adapter that appended these and folded them cannot reconstruct a handler the domain considers
 * illegal — which is the property that makes duplicate, late and out-of-order triggers refusable
 * from stored state alone, without a clock and without a deduplication window.</p>
 *
 * <h2>Every outcome-bearing transition names the traversal that resumes</h2>
 * <p>{@link Expired}, {@link Denied} and {@link Resolved} all carry a {@code resumeTraversalId}, and
 * the batch applying one must also contain the {@link ExecutionTransition.TraversalAdded} that
 * creates it. The store rejects the batch otherwise. That is what makes re-entry a property of
 * durable state rather than of a live process: the new traversal is committed with the handler
 * outcome, and the {@link PendingWork.HandlerTrigger} the store enqueues for it is claimable by
 * whichever worker happens to be alive — including one in a process that did not exist when the wait
 * began. No actor is revived, because none is named anywhere in this contract.</p>
 *
 * <h2>Escalation is the one transition that resumes nothing</h2>
 * <p>{@link Escalated} raises a waiting handler's visibility and leaves it resolvable. It produces no
 * traversal and no trigger, so a handler emits at most one trigger over its whole life and its
 * identity can therefore be the trigger's {@link PendingWork#workItemId()}. An escalation that ran
 * its own graph branch would let one handler emit unboundedly many re-entry traversals, each needing
 * a separate identity and a separate acknowledgement; the escalation is journalled instead, and
 * notifying is the runtime's job.</p>
 */
public sealed interface HandlerTransition {

    /**
     * The handler this transition acts on.
     * @return stable handler identity.
     */
    UUID handlerId();

    /**
     * The state this transition moves the handler into.
     * @return requested successor state.
     */
    HandlerStatus next();

    /**
     * The re-entry traversal committed with this transition, or {@code null} when it resumes nothing.
     *
     * <p>Present exactly when {@link #next()} reports {@link HandlerStatus#resumesProcess()}.</p>
     * @return traversal that must exist in the post-fold aggregate, or {@code null}.
     */
    UUID resumeTraversalId();

    /**
     * The audit-stable identity that caused this transition, or an empty string when nobody did.
     *
     * <p>Empty for {@link Expired} and {@link Escalated}: a deadline is not an actor, and recording
     * a system placeholder there would put a fabricated principal into a durable audit record.</p>
     * @return principal identity, or the empty string.
     */
    String actor();

    /**
     * The outcome body carried into the resumed traversal.
     * @return trigger payload, or an empty payload when the transition carries none.
     */
    OpaquePayload outcomePayload();

    /** Media type of the empty payload used by transitions that carry no body. */
    String EMPTY_CONTENT_TYPE = "application/octet-stream";

    /**
     * The handler exceeded an attention threshold and stays resolvable.
     *
     * <p>Applying this to a handler that is already {@link HandlerStatus#ESCALATED} is a no-op
     * success rather than a rejection: re-escalation is the ordinary consequence of a retried timer,
     * and turning it into a failure would make an at-least-once timer delivery unsafe.</p>
     * @param handlerId the stable handler id used to identify the requested resource.
     * @param reason bounded, operator-safe reason recorded with the escalation.
     */
    record Escalated(UUID handlerId, String reason) implements HandlerTransition {
        /** Validates the escalation and its operator-safe reason. */
        public Escalated {
            requireHandlerId(handlerId);
            reason = HandlerRegistration.requireBoundedKey(reason, "reason");
        }

        @Override
        public HandlerStatus next() {
            return HandlerStatus.ESCALATED;
        }

        @Override
        public UUID resumeTraversalId() {
            return null;
        }

        @Override
        public String actor() {
            return "";
        }

        @Override
        public OpaquePayload outcomePayload() {
            return OpaquePayload.empty(EMPTY_CONTENT_TYPE);
        }
    }

    /**
     * The wait ended without a trigger.
     * @param handlerId the stable handler id used to identify the requested resource.
     * @param resumeTraversalId the re-entry traversal committed with this transition.
     */
    record Expired(UUID handlerId, UUID resumeTraversalId) implements HandlerTransition {
        /** Validates the expiry and the traversal that resumes the process. */
        public Expired {
            requireHandlerId(handlerId);
            requireResumeTraversalId(resumeTraversalId);
        }

        @Override
        public HandlerStatus next() {
            return HandlerStatus.EXPIRED;
        }

        @Override
        public String actor() {
            return "";
        }

        @Override
        public OpaquePayload outcomePayload() {
            return OpaquePayload.empty(EMPTY_CONTENT_TYPE);
        }
    }

    /**
     * An authorized principal refused the task.
     *
     * <p>A denial is an <em>outcome</em>, not a failure: the process continues down whatever route
     * its author declared for a refusal. Modelling it as a failed traversal would make "the approver
     * said no" indistinguishable from "the approval step broke".</p>
     * @param handlerId the stable handler id used to identify the requested resource.
     * @param actor audit-stable identity of the refusing principal.
     * @param resumeTraversalId the re-entry traversal committed with this transition.
     * @param outcomePayload bounded body carried into the resumed traversal.
     */
    record Denied(UUID handlerId, String actor, UUID resumeTraversalId, OpaquePayload outcomePayload)
            implements HandlerTransition {
        /** Validates the denial, its principal and the traversal that resumes the process. */
        public Denied {
            requireHandlerId(handlerId);
            actor = HandlerRegistration.requireBoundedKey(actor, "actor");
            requireResumeTraversalId(resumeTraversalId);
            if (outcomePayload == null) throw new IllegalArgumentException("outcomePayload cannot be null");
        }

        @Override
        public HandlerStatus next() {
            return HandlerStatus.DENIED;
        }
    }

    /**
     * An authorized trigger supplied an outcome.
     * @param handlerId the stable handler id used to identify the requested resource.
     * @param actor audit-stable identity of the resolving principal.
     * @param resumeTraversalId the re-entry traversal committed with this transition.
     * @param outcomePayload bounded body carried into the resumed traversal.
     */
    record Resolved(UUID handlerId, String actor, UUID resumeTraversalId, OpaquePayload outcomePayload)
            implements HandlerTransition {
        /** Validates the resolution, its principal and the traversal that resumes the process. */
        public Resolved {
            requireHandlerId(handlerId);
            actor = HandlerRegistration.requireBoundedKey(actor, "actor");
            requireResumeTraversalId(resumeTraversalId);
            if (outcomePayload == null) throw new IllegalArgumentException("outcomePayload cannot be null");
        }

        @Override
        public HandlerStatus next() {
            return HandlerStatus.RESOLVED;
        }
    }

    private static void requireHandlerId(UUID handlerId) {
        if (handlerId == null) {
            throw new IllegalArgumentException("handlerId cannot be null");
        }
    }

    private static void requireResumeTraversalId(UUID resumeTraversalId) {
        if (resumeTraversalId == null) {
            throw new IllegalArgumentException("resumeTraversalId cannot be null");
        }
    }
}
