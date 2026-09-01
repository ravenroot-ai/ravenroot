package ai.ravenroot.api.security;

/** Central policy decision point and reference monitor contract. */
public interface AuthorizationService {
/**
 * Evaluates one application action against trusted identity and resource attributes.
 * @param context authenticated request context
 * @param action application action to authorize
 * @param resource trusted resource ownership attributes
 * @return auditable permit or denial decision
 */
    AuthorizationDecision decide(RequestContext context, AuthorizationAction action, ProtectedResource resource);

/**
 * Requires an allowed decision, throwing a sanitized exception otherwise.
 * @param context authenticated request context
 * @param action application action to authorize
 * @param resource trusted resource ownership attributes
 */
    default void requireAllowed(RequestContext context, AuthorizationAction action, ProtectedResource resource) {
        AuthorizationDecision decision = decide(context, action, resource);
        if (!decision.allowed()) {
            throw new AuthorizationDeniedException(decision.reason());
        }
    }
}
