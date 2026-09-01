package ai.ravenroot.api.embed;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One operator provision, expressed as a compare-and-set against an expected revision.
 *
 * <p>{@code expectedRevision} is {@code 0} for a registration that must not exist yet and the
 * current revision otherwise. There is deliberately no "upsert regardless" form: an operator who
 * does not know which revision they are replacing is an operator who cannot know what they are
 * replacing it with, and two such writers would silently overwrite each other's snapshot pinning.</p>
 *
 * <p>Every coordinate here is operator-supplied and out of band. Nothing on this record may be
 * derived from a browser request, a graph, a payload or a plugin — the registration id is the only
 * value that later crosses the HTTP boundary, and it is a lookup key, not a claim.</p>
 *
 * @param registrationId tenant-scoped registration identifier
 * @param expectedRevision {@code 0} for creation or the revision that must still be current
 * @param workloadIssuer verified workload token issuer to bind into the session grant
 * @param workloadSubject verified workload token subject to bind into the session grant
 * @param tenantId tenant that owns the registration
 * @param parentOrigin browser origin permitted to host the embedded session
 * @param capabilities immutable capabilities granted to the embedded workload
 * @param themeOverride optional presentation theme selected by the operator
 * @param graphGrant verified graph-read grant captured at provision time
 * @param snapshotLifecycle published/active lifecycle evidence for the captured graph
 * @param eligibility deployment-policy decision for the projection
 * @param projection graph view pinned into the registration
 */
public record EmbedProvisionCommand(String registrationId, long expectedRevision, String workloadIssuer,
                                    String workloadSubject, String tenantId, String parentOrigin,
                                    Set<EmbedCapability> capabilities, Optional<EmbedTheme> themeOverride,
                                    VerifiedEmbedGraphGrant graphGrant,
                                    EmbedSnapshotLifecycle snapshotLifecycle,
                                    EmbedProjectionEligibility eligibility,
                                    EmbedGraphProjection projection) {

    /** Rejects missing identity fields and mutable or incomplete grant inputs. */
    public EmbedProvisionCommand {
        registrationId = requireText(registrationId, "registrationId");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must not be negative");
        workloadIssuer = requireText(workloadIssuer, "workloadIssuer");
        workloadSubject = requireText(workloadSubject, "workloadSubject");
        tenantId = requireText(tenantId, "tenantId");
        parentOrigin = requireText(parentOrigin, "parentOrigin");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        themeOverride = Objects.requireNonNull(themeOverride, "themeOverride");
        Objects.requireNonNull(graphGrant, "graphGrant");
        Objects.requireNonNull(snapshotLifecycle, "snapshotLifecycle");
        Objects.requireNonNull(eligibility, "eligibility");
        Objects.requireNonNull(projection, "projection");
    }

    /** The revision this command writes when it is accepted; monotone by construction.
     * @return {@code expectedRevision + 1}
     */
    public long nextRevision() {
        return expectedRevision + 1;
    }

    /**
     * Builds the aggregate this command describes.
     *
     * <p>Call only after {@link EmbedRegistrationRules#rejectionOf} has returned {@code null}: this
     * method builds through {@link EmbedRegistrationAggregate}'s constructor, which enforces internal
     * coherence, but the policy gates (lifecycle, eligibility, budget) are the rules' responsibility
     * and are checked once, in one place, so that every adapter refuses identically.</p>
     *
     * @param provisionedAt instant at which the authority commits the aggregate
     * @return aggregate containing the command's captured identity and graph projection
     */
    public EmbedRegistrationAggregate aggregateAt(Instant provisionedAt) {
        var sessionGrant = new VerifiedEmbedSessionGrant(registrationId, nextRevision(), workloadIssuer,
                workloadSubject, tenantId, parentOrigin, capabilities, graphGrant, themeOverride);
        return new EmbedRegistrationAggregate(registrationId, nextRevision(),
                EmbedRegistrationState.ACTIVE, sessionGrant, snapshotLifecycle, eligibility, projection,
                Objects.requireNonNull(provisionedAt, "provisionedAt"));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
