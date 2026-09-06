package ai.ravenroot.api.persistence;

import java.util.List;
import java.util.Set;

/**
 * Minimum caller authority required by the persistence port to project Human Task attention.
 *
 * <p>The actor is the same qualified identity used by decision settlement. It permits requester
 * cancellation without exposing the stored requester or returning a task that has no action this
 * caller can take.</p>
 *
 * @param actor audit-stable qualified caller identity.
 * @param roles opaque role tokens currently held by the caller.
 * @param scopes opaque scope tokens currently held by the caller.
 */
public record HumanTaskAttentionAuthorization(String actor, Set<String> roles, Set<String> scopes) {
    /** Validates the actor and snapshots authority tokens. */
    public HumanTaskAttentionAuthorization {
        actor = HandlerRegistration.requireBoundedKey(actor, "actor");
        HandlerAuthorization validated = new HandlerAuthorization(roles, scopes);
        roles = validated.requiredRoles();
        scopes = validated.requiredScopes();
    }

    /**
     * Computes pinned actions this caller may currently perform.
     *
     * @param registration immutable task registration.
     * @return immutable actions, empty when the task must not be projected.
     */
    public List<HumanTaskConfirmationAction> permittedActions(HumanTaskRegistration registration) {
        if (registration == null || !registration.confirmationPresentation().embedded()) return List.of();
        return permittedActions(registration.responderRequirements(),
                registration.requester().qualifiedIdentity(), registration.confirmationPresentation());
    }

    /**
     * Computes permitted actions from the bounded columns needed by a safe store projection.
     *
     * @param requirements responder role and scope requirements.
     * @param requesterActor qualified identity of the task requester.
     * @param presentation immutable pinned presentation.
     * @return immutable actions in authored order.
     */
    public List<HumanTaskConfirmationAction> permittedActions(
            HandlerAuthorization requirements, String requesterActor,
            HumanTaskConfirmationPresentation presentation) {
        if (requirements == null || requesterActor == null || presentation == null
                || !presentation.embedded()) return List.of();
        return permittedActions(requirements, requesterActor, presentation.actions());
    }

    /**
     * Computes authorization from the minimal stored fields before validating display projection.
     *
     * @param requirements responder role and scope requirements.
     * @param requesterActor qualified identity of the task requester.
     * @param pinnedActions ordered actions pinned by the task.
     * @return immutable actions in authored order.
     */
    public List<HumanTaskConfirmationAction> permittedActions(
            HandlerAuthorization requirements, String requesterActor,
            List<HumanTaskConfirmationAction> pinnedActions) {
        if (requirements == null || requesterActor == null || pinnedActions == null
                || pinnedActions.isEmpty()) return List.of();
        boolean responder = requirements.satisfiedBy(roles, scopes);
        boolean requester = actor.equals(requesterActor);
        return pinnedActions.stream()
                .filter(action -> responder
                        || action == HumanTaskConfirmationAction.CANCEL && requester)
                .toList();
    }
}
