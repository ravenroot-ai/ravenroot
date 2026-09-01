package ai.ravenroot.api.embed;

import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.AuthorizationService;
import ai.ravenroot.api.security.ProtectedResource;
import ai.ravenroot.api.security.RequestContext;

import java.util.Objects;

/**
 * Reference monitor for resolving an out-of-band embed registration.
 *
 * <p>It now returns the whole {@link EmbedRegistrationAggregate} rather than a session grant alone.
 * The caller keeps the captured aggregate for the life of the browser
 * session, and the projection it is later served is derived from that same instance instead of being
 * looked up again by graph coordinates.</p>
 */
public final class AuthorizedEmbedSessionCreation {

    private final AuthorizationService authorization;
    private final EmbedRegistrationAuthority authority;

    /**
     * Creates the session-resolution reference monitor.
     * @param authorization policy service that authenticates the workload read request
     * @param authority registration authority holding current session grants
     */
    public AuthorizedEmbedSessionCreation(AuthorizationService authorization,
                                          EmbedRegistrationAuthority authority) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    /**
     * Resolves a registration for an authorized workload without disclosing unknown registrations.
     * @param context authenticated workload request context
     * @param registrationId opaque operator-created registration identifier
     * @return current captured registration, or an unavailable/temporary result
     */
    public EmbedRegistrationResolution resolve(RequestContext context, String registrationId) {
        Objects.requireNonNull(context, "context");
        if (registrationId == null || registrationId.isBlank()) {
            return EmbedRegistrationResolution.Unavailable.INSTANCE;
        }
        // DefaultAuthorizationService audits the principal-type, role, scope and tenant decision.
        authorization.requireAllowed(context, AuthorizationAction.EMBED_SESSION_CREATE,
                ProtectedResource.owned("embed-session", registrationId, context.tenantId()));
        try {
            EmbedRegistrationResolution resolution = authority.resolveCurrent(context, registrationId);
            return resolution == null ? EmbedRegistrationResolution.Temporary.INSTANCE : resolution;
        } catch (RuntimeException unavailable) {
            return EmbedRegistrationResolution.Temporary.INSTANCE;
        }
    }
}
