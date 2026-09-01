package ai.ravenroot.api.embed;

import java.time.Instant;
import java.util.Objects;

/**
 * The single revisioned authority for one embed registration.
 *
 * <h2>Why the projection payload lives inside the aggregate</h2>
 * <p>Before this type there were two registries: a grant registry resolved by registration id and a
 * projection registry resolved by graph coordinates. Every browser read joined them with two
 * independent lookups, so a concurrent re-provision could pair revision <em>n</em>'s grant with
 * revision <em>n+1</em>'s snapshot — a couple that was never authorized as a whole. No amount of
 * per-store locking fixes that, because the hazard is between the stores, not inside either.</p>
 *
 * <p>The fix is structural: the render-only {@link EmbedGraphProjection} is captured at provision
 * time, against a snapshot whose lifecycle and digest were verified in that same command, and is
 * carried <em>by</em> the aggregate from then on. {@link #projectionOf(EmbedProjectionBudget)} is a
 * pure function of {@code this}. A caller holding a captured aggregate therefore cannot obtain a
 * projection belonging to any other revision, because there is nowhere else for one to come from.
 * See {@link EmbedRegistrationAuthority#resolveProjection}, which is a {@code default} method for the
 * same reason: an adapter has no seam in which to reintroduce the second read.</p>
 *
 * <h2>Coherence is a constructor obligation, not a read-time check</h2>
 * <p>The compact constructor rejects any aggregate whose registration id, revision, tenant, snapshot
 * identity, digest or policy revision disagree across its parts. An incoherent aggregate cannot be
 * instantiated, so no read path has to defend against one — including a read path that folds rows
 * back off disk, where a rejected construction is how corruption is detected at all.</p>
 *
 * @param registrationId    operator-chosen, the only coordinate a caller ever supplies
 * @param revision          strictly positive and strictly increasing across mutations
 * @param state             {@link EmbedRegistrationState#REVOKED} is terminal
 * @param sessionGrant      the session view; its revision and registration id are this one's
 * @param snapshotLifecycle the graph-version state verified at provision, frozen here as evidence
 * @param eligibility       the deployment-policy decision consumed by the browser projection
 * @param projection        the render-only payload captured at provision, never re-resolved
 * @param provisionedAt     when this revision was written; audit evidence, never a validity input
 */
public record EmbedRegistrationAggregate(String registrationId, long revision,
                                         EmbedRegistrationState state,
                                         VerifiedEmbedSessionGrant sessionGrant,
                                         EmbedSnapshotLifecycle snapshotLifecycle,
                                         EmbedProjectionEligibility eligibility,
                                         EmbedGraphProjection projection,
                                         Instant provisionedAt) {

    /** Enforces cross-component identity, revision, digest, and policy coherence. */
    public EmbedRegistrationAggregate {
        registrationId = requireText(registrationId, "registrationId");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(sessionGrant, "sessionGrant");
        Objects.requireNonNull(snapshotLifecycle, "snapshotLifecycle");
        Objects.requireNonNull(eligibility, "eligibility");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(provisionedAt, "provisionedAt");
        if (!registrationId.equals(sessionGrant.registrationId())) {
            throw new IllegalArgumentException("aggregate and session grant registration ids must match");
        }
        if (revision != sessionGrant.revision()) {
            throw new IllegalArgumentException("aggregate and session grant revisions must match");
        }
        VerifiedEmbedGraphGrant graph = sessionGrant.graphGrant();
        if (!projection.viewerContractVersion().equals(EmbedGraphProjection.CURRENT_CONTRACT_VERSION)
                || !projection.graphId().equals(graph.graphId())
                || !projection.graphVersionId().equals(graph.graphVersionId())
                || !projection.canonicalDigest().equals(graph.canonicalDigest())) {
            throw new IllegalArgumentException("captured projection identity must match the graph grant");
        }
        if (!eligibility.policyRevision().equals(graph.projectionPolicyRevision())) {
            throw new IllegalArgumentException("eligibility and graph grant policy revisions must match");
        }
    }

    /** The pinned graph coordinates; identical to {@code sessionGrant().graphGrant()}.
     * @return graph grant captured into this aggregate revision
     */
    public VerifiedEmbedGraphGrant graphGrant() {
        return sessionGrant.graphGrant();
    }

    /** Returns the tenant encoded in the captured session grant.
     * @return owning tenant identifier
     */
    public String tenantId() {
        return sessionGrant.tenantId();
    }

    /** Tests whether this revision may authorize new browser sessions.
     * @return {@code true} only while state is active
     */
    public boolean active() {
        return state == EmbedRegistrationState.ACTIVE;
    }

    /**
     * Answers the browser read from this captured revision alone.
     *
     * <p>No store is consulted. A revoked or policy-denied aggregate resolves to
     * {@link EmbedProjectionResolution.Unavailable}, and a payload above the caller's budget to
     * {@link EmbedProjectionResolution.DataTooLarge} — the budget is re-applied here rather than
     * trusted from provision time, because the reader's budget is the reader's, and a registration
     * provisioned under a wider one must not silently widen it.</p>
     * @param budget reader-side maximum projection size
     * @return available projection, unavailable result, or data-too-large result
     */
    public EmbedProjectionResolution projectionOf(EmbedProjectionBudget budget) {
        Objects.requireNonNull(budget, "budget");
        if (state != EmbedRegistrationState.ACTIVE || !eligibility.allowsProjection()) {
            return EmbedProjectionResolution.Unavailable.INSTANCE;
        }
        if (!budget.allows(projection)) return EmbedProjectionResolution.DataTooLarge.INSTANCE;
        return new EmbedProjectionResolution.Available(projection);
    }

    /**
     * The terminal revision for this registration.
     *
     * <p>The session grant is rebuilt at the new revision so that every existing ticket, bearer and
     * acknowledgement — all of which compare the revision they captured against the current one —
     * stops matching the moment this is written.</p>
     * @param nextRevision strictly greater revision assigned to the terminal aggregate
     * @param occurredAt instant at which revocation was committed
     * @return new terminal aggregate with a rebuilt session grant
     */
    public EmbedRegistrationAggregate revokedAt(long nextRevision, Instant occurredAt) {
        if (nextRevision <= revision) {
            throw new IllegalArgumentException("a revocation must increase the revision");
        }
        var revokedGrant = new VerifiedEmbedSessionGrant(registrationId, nextRevision,
                sessionGrant.workloadIssuer(), sessionGrant.workloadSubject(), sessionGrant.tenantId(),
                sessionGrant.parentOrigin(), sessionGrant.capabilities(), sessionGrant.graphGrant(),
                sessionGrant.themeOverride());
        return new EmbedRegistrationAggregate(registrationId, nextRevision, EmbedRegistrationState.REVOKED,
                revokedGrant, snapshotLifecycle, eligibility, projection, occurredAt);
    }

    /**
     * Whether {@code other} is this exact revision of this exact registration.
     *
     * <p>Used by every currency re-check on the session path. It compares the whole aggregate, not just
     * the revision pair, so a store that returned a same-numbered but different aggregate is refused
     * rather than accepted on the strength of a matching counter.</p>
     * @param other aggregate to compare with this captured revision
     * @return {@code true} only for an equal registration and revision
     */
    public boolean sameRevisionAs(EmbedRegistrationAggregate other) {
        return other != null && registrationId.equals(other.registrationId())
                && revision == other.revision() && equals(other);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
