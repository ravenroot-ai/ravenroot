package ai.ravenroot.api.embed;

import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.AuthorizationService;
import ai.ravenroot.api.security.ProtectedResource;
import ai.ravenroot.api.security.RequestContext;

import java.util.Objects;

/**
 * Reference monitor and fail-closed application use case for the browser projection read.
 *
 * <p>It takes the aggregate the session captured, not a set of graph coordinates. Passing
 * coordinates was what made a second lookup necessary, and a second lookup was what allowed a
 * grant and a snapshot from different revisions to be served together.</p>
 *
 * <h2>On this path the reference monitor cannot deny, and that is worth knowing</h2>
 * <p>{@code EmbedBrowserHttpHandler} synthesises the {@link RequestContext} for a projection read
 * <em>from the captured grant itself</em> — subject, issuer and tenant are copied off it, the
 * principal type is fixed to {@code WORKLOAD}, and the role and scope are the two constants this
 * action requires. Every input therefore agrees with the resource by construction, and
 * {@code requireAllowed} below returns "allow" for every reachable call. It is not a decision point
 * on this path; its residual value is the audit record the policy writes, and the shape check that
 * follows it.</p>
 *
 * <p>The consequence to hold on to: <strong>once a bearer is minted, the workload's live
 * authorization is never consulted again until that bearer expires</strong> (120 seconds by default).
 * Revoking the workload's token, or its role, does not stop an in-flight embed session. Revoking the
 * <em>registration</em> does, immediately, through the {@code isCurrent} re-check on every phase —
 * and that is the control an operator has. If the requirement is "cut this workload off now", the
 * lever is {@code ravenroot embed-registration revoke}, not the identity provider.</p>
 */
public final class AuthorizedEmbedGraphProjection {

    private final AuthorizationService authorization;
    private final EmbedRegistrationAuthority authority;
    private final EmbedProjectionBudget budget;

    /**
     * Creates projection authorization with the standard public-projection size budget.
     * @param authorization reference monitor that audits and checks graph-read authority
     * @param authority registration store used to resolve the captured projection
     */
    public AuthorizedEmbedGraphProjection(AuthorizationService authorization,
                                          EmbedRegistrationAuthority authority) {
        this(authorization, authority, EmbedProjectionBudget.DEFAULTS);
    }

    /**
     * Creates projection authorization with an explicit independent rendering budget.
     * @param authorization reference monitor that audits and checks graph-read authority
     * @param authority registration store used to resolve the captured projection
     * @param budget maximum projection size accepted by this reader
     */
    public AuthorizedEmbedGraphProjection(AuthorizationService authorization,
                                          EmbedRegistrationAuthority authority,
                                          EmbedProjectionBudget budget) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    /**
     * Resolves the projection only when it still matches the grant and captured registration revision.
     * @param context workload request context created from the verified session grant
     * @param captured registration aggregate captured at session creation, never caller coordinates
     * @return available projection or a deliberately non-disclosing unavailable resolution
     */
    public EmbedProjectionResolution read(RequestContext context, EmbedRegistrationAggregate captured) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(captured, "captured");
        VerifiedEmbedGraphGrant grant = captured.graphGrant();
        authorization.requireAllowed(context, AuthorizationAction.EMBED_GRAPH_READ,
                ProtectedResource.owned("embed-graph", grant.resourceId(), grant.tenantId()));
        try {
            EmbedProjectionResolution result = authority.resolveProjection(captured, budget);
            if (result == null) return EmbedProjectionResolution.TemporarilyUnavailable.INSTANCE;
            if (result instanceof EmbedProjectionResolution.Available available) {
                // Re-checked at the application boundary rather than trusted from the adapter. The
                // aggregate's own constructor already enforces this pairing, so a mismatch here means
                // the adapter returned a payload that is not the captured one -- exactly the
                // condition an override of resolveProjection would produce.
                EmbedGraphProjection projection = available.projection();
                if (!projection.viewerContractVersion().equals(EmbedGraphProjection.CURRENT_CONTRACT_VERSION)
                        || !projection.graphId().equals(grant.graphId())
                        || !projection.graphVersionId().equals(grant.graphVersionId())
                        || !projection.canonicalDigest().equals(grant.canonicalDigest())) {
                    return EmbedProjectionResolution.Unavailable.INSTANCE;
                }
                if (!budget.allows(projection)) return EmbedProjectionResolution.DataTooLarge.INSTANCE;
            }
            return result;
        } catch (RuntimeException failure) {
            return EmbedProjectionResolution.TemporarilyUnavailable.INSTANCE;
        }
    }
}
