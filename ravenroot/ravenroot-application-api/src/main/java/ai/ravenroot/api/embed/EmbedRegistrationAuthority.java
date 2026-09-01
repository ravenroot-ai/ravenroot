package ai.ravenroot.api.embed;

import ai.ravenroot.api.security.RequestContext;

import java.util.Objects;

/**
 * The single engine-neutral port for embed registrations: provision, revoke, read, project.
 *
 * <h2>One read, structurally</h2>
 * <p>{@link #resolveProjection} is a {@code default} method and is not meant to be overridden. It
 * takes the aggregate the caller already captured and derives the projection from that instance, so
 * a session grant and the snapshot it renders always come from the same revision. The alternative —
 * a second lookup keyed on graph coordinates — is what this port replaced, and it is unavailable
 * here because there is no second store to look in.</p>
 *
 * <p>The currency re-check before projecting is not a second read of the payload; it provides
 * immediate revocation visibility. If the registration moved on or was withdrawn since capture,
 * the answer is {@link EmbedProjectionResolution.Unavailable} — never revision <em>n</em>'s grant
 * paired with revision <em>n+1</em>'s content.</p>
 *
 * <h2>Failure closes</h2>
 * <p>An adapter that cannot answer returns {@link EmbedRegistrationResolution.Temporary} or an
 * {@code Unavailable} member. It never returns an aggregate it is unsure of, and it never treats an
 * absent or malformed record as an empty-but-valid one.</p>
 */
public interface EmbedRegistrationAuthority {

    /**
     * Writes {@code command} if its expected revision is still current and its content is acceptable.
     *
     * <p>Callers reach this through {@link AuthorizedEmbedRegistrationAdministration}, which is where
     * the operator-only authorization and the audit record live. Adapters do not authorize.</p>
     *
     * @param command validated operator command to persist
     * @return provision result, including expected conflicts and policy refusals
     */
    EmbedProvisionOutcome provision(EmbedProvisionCommand command);

    /** Terminally withdraws a registration. Monotone: the revision increases and never returns.
     * @param command compare-and-set revocation command
     * @return revocation result, including idempotent and conflict outcomes
     */
    EmbedRevokeOutcome revoke(EmbedRevokeCommand command);

    /**
     * The current aggregate for {@code registrationId}, if it is active and belongs to this workload.
     *
     * <p>Issuer, subject and tenant are compared against {@code workload}, which a trusted adapter
     * established from the authenticated principal. The registration id is the only caller-supplied
     * coordinate; nothing else on the request may substitute an authoritative value.</p>
     *
     * @param workload authenticated workload identity used for issuer/subject/tenant matching
     * @param registrationId untrusted lookup key supplied by the browser flow
     * @return matching current aggregate or a non-disclosing unavailable result
     */
    EmbedRegistrationResolution resolveCurrent(RequestContext workload, String registrationId);

    /**
     * Whether {@code captured} is still the current revision. Re-checked at ticket, acknowledgement,
     * exchange and bearer resolution so a revocation is visible to credentials already in flight.
     *
     * @param captured aggregate previously returned by this authority
     * @return {@code true} only while that exact registration revision remains current
     */
    boolean isCurrent(EmbedRegistrationAggregate captured);

    /**
     * The browser projection for a captured aggregate.
     *
     * <p>Deliberately final in spirit: an adapter that overrides this to re-resolve the payload from
     * a store reintroduces the two-read join this port exists to remove.</p>
     *
     * @param captured aggregate captured while creating the session grant
     * @param budget maximum document size that may be returned to the browser
     * @return projection when the capture is current and within budget; otherwise unavailable
     */
    default EmbedProjectionResolution resolveProjection(EmbedRegistrationAggregate captured,
                                                        EmbedProjectionBudget budget) {
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(budget, "budget");
        if (!isCurrent(captured)) return EmbedProjectionResolution.Unavailable.INSTANCE;
        return captured.projectionOf(budget);
    }
}
