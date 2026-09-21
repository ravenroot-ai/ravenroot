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
 * @param source mutually-exclusive snapshot or live-deployment viewer source
 */
public record EmbedProvisionCommand(String registrationId, long expectedRevision, String workloadIssuer,
                                    String workloadSubject, String tenantId, String parentOrigin,
                                    Set<EmbedCapability> capabilities, Optional<EmbedTheme> themeOverride,
                                    VerifiedEmbedGraphGrant graphGrant,
                                    EmbedSnapshotLifecycle snapshotLifecycle,
                                    EmbedProjectionEligibility eligibility,
                                    EmbedGraphProjection projection,
                                    EmbedViewerSource source) {

    /**
     * Source-compatible constructor for every pre-deployment, immutable-snapshot caller.
     * @param registrationId tenant-scoped registration identifier
     * @param expectedRevision revision that must still be current
     * @param workloadIssuer verified workload token issuer
     * @param workloadSubject verified workload token subject
     * @param tenantId tenant that owns the registration
     * @param parentOrigin permitted embedding origin
     * @param capabilities immutable embedded workload capabilities
     * @param themeOverride optional operator-selected theme
     * @param graphGrant verified graph-read grant
     * @param snapshotLifecycle captured graph lifecycle evidence
     * @param eligibility projection-policy decision
     * @param projection captured render-only graph view
     */
    public EmbedProvisionCommand(String registrationId, long expectedRevision, String workloadIssuer,
                                 String workloadSubject, String tenantId, String parentOrigin,
                                 Set<EmbedCapability> capabilities, Optional<EmbedTheme> themeOverride,
                                 VerifiedEmbedGraphGrant graphGrant,
                                 EmbedSnapshotLifecycle snapshotLifecycle,
                                 EmbedProjectionEligibility eligibility,
                                 EmbedGraphProjection projection) {
        this(registrationId, expectedRevision, workloadIssuer, workloadSubject, tenantId, parentOrigin,
                capabilities, themeOverride, graphGrant, snapshotLifecycle, eligibility, projection,
                EmbedViewerSource.snapshot());
    }

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
        Objects.requireNonNull(source, "source");
    }

    /**
     * Creates a live-deployment source without accepting or reopening GraphML.
     * @param registrationId tenant-scoped registration identifier
     * @param expectedRevision revision that must still be current
     * @param workloadIssuer verified workload token issuer
     * @param workloadSubject verified workload token subject
     * @param tenantId tenant that owns the registration
     * @param parentOrigin permitted embedding origin
     * @param themeOverride optional operator-selected theme
     * @param deploymentId tenant-scoped live deployment identifier
     * @return deployment-backed provision command
     */
    public static EmbedProvisionCommand deployment(String registrationId, long expectedRevision,
                                                    String workloadIssuer, String workloadSubject,
                                                    String tenantId, String parentOrigin,
                                                    Optional<EmbedTheme> themeOverride,
                                                    String deploymentId) {
        var source = EmbedViewerSource.deployment(deploymentId);
        // Compatibility-only inert snapshot components. They are never served, printed or treated as
        // policy evidence for a deployment source; keeping them private to this factory preserves the
        // established record accessors while the public source union remains mutually exclusive.
        var grant = new VerifiedEmbedGraphGrant(tenantId, "deployment:" + deploymentId, deploymentId,
                1, deploymentId, "deferred", "deferred", "deployment-live-v1");
        var eligibility = new EmbedProjectionEligibility("deployment-live-v1",
                false, false, false, false, false, false, false);
        var projection = new EmbedGraphProjection(EmbedGraphProjection.CURRENT_CONTRACT_VERSION,
                deploymentId, "deferred", "deferred", java.util.List.of(), java.util.List.of());
        return new EmbedProvisionCommand(registrationId, expectedRevision, workloadIssuer, workloadSubject,
                tenantId, parentOrigin, Set.of(EmbedCapability.GRAPH_READ,
                EmbedCapability.DEPLOYMENT_OBSERVE), themeOverride, grant,
                EmbedSnapshotLifecycle.ACTIVE, eligibility, projection, source);
    }

    /** Creates an additive v2 deployment registration with selectable runs. */
    public static EmbedProvisionCommand deploymentV2(String registrationId, long expectedRevision,
                                                      String workloadIssuer, String workloadSubject,
                                                      String tenantId, String parentOrigin,
                                                      Optional<EmbedTheme> themeOverride,
                                                      String deploymentId,
                                                      boolean showStartExecution) {
        return deploymentV2(registrationId, expectedRevision, workloadIssuer, workloadSubject,
                tenantId, parentOrigin, themeOverride, deploymentId, showStartExecution, false);
    }

    /**
     * Creates a v2 deployment registration with separately granted execution authority.
     *
     * <p>The presentation option remains independent: callers may grant execution while keeping the
     * affordance hidden, or show the affordance without using this authority-granting factory.</p>
     */
    public static EmbedProvisionCommand deploymentV2WithExecutionCapability(
            String registrationId, long expectedRevision,
            String workloadIssuer, String workloadSubject,
            String tenantId, String parentOrigin,
            Optional<EmbedTheme> themeOverride,
            String deploymentId,
            boolean showStartExecution) {
        return deploymentV2(registrationId, expectedRevision, workloadIssuer, workloadSubject,
                tenantId, parentOrigin, themeOverride, deploymentId, showStartExecution, true);
    }

    private static EmbedProvisionCommand deploymentV2(
            String registrationId, long expectedRevision,
            String workloadIssuer, String workloadSubject,
            String tenantId, String parentOrigin,
            Optional<EmbedTheme> themeOverride,
            String deploymentId,
            boolean showStartExecution,
            boolean grantExecution) {
        var source = EmbedViewerSource.deploymentV2(deploymentId, showStartExecution);
        var grant = new VerifiedEmbedGraphGrant(tenantId, "deployment:" + deploymentId, deploymentId,
                1, deploymentId, "deferred", "deferred", "deployment-live-v2");
        var eligibility = new EmbedProjectionEligibility("deployment-live-v2",
                false, false, false, false, false, false, false);
        var projection = new EmbedGraphProjection(EmbedGraphProjection.CURRENT_CONTRACT_VERSION,
                deploymentId, "deferred", "deferred", java.util.List.of(), java.util.List.of());
        var capabilities = new java.util.HashSet<EmbedCapability>();
        capabilities.add(EmbedCapability.GRAPH_READ);
        capabilities.add(EmbedCapability.DEPLOYMENT_OBSERVE);
        capabilities.add(EmbedCapability.DEPLOYMENT_RUN_READ);
        if (grantExecution) capabilities.add(EmbedCapability.DEPLOYMENT_EXECUTE);
        return new EmbedProvisionCommand(registrationId, expectedRevision, workloadIssuer, workloadSubject,
                tenantId, parentOrigin, Set.copyOf(capabilities), themeOverride, grant,
                EmbedSnapshotLifecycle.ACTIVE, eligibility, projection, source);
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
                Objects.requireNonNull(provisionedAt, "provisionedAt"), source);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
