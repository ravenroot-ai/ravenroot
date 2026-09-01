package ai.ravenroot.api.programming;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Defines the artifact registry contract exposed to Ravenroot integrators.
 */
public interface ArtifactRegistry {
/**
 * Creates a new draft artifact from source submitted by an author.
 * @param language runtime language identifier selected for the artifact.
 * @param source uncompiled source text to retain with the artifact.
 * @param metadata immutable author or tooling metadata associated with the draft.
 * @return newly assigned artifact snapshot in its initial lifecycle state.
 */
    GeneratedArtifact create(String language, String source, Map<String, String> metadata);

/**
 * Looks up the current snapshot of one generated artifact.
 * @param id stable artifact identifier.
 * @return the snapshot when it still exists; otherwise empty.
 */
    Optional<GeneratedArtifact> find(String id);

/**
 * Lists artifact snapshots visible to this registry.
 * @return immutable collection of current artifact snapshots.
 */
    List<GeneratedArtifact> list();

    /**
 * Resolves the authoritative artifact for one tenant and server-calculated source digest.
 * Implementations must not disclose a matching digest owned by another tenant.
* @param tenantId authenticated tenant that owns the operation or event
* @param sha256 lowercase server-calculated SHA-256 content digest
* @return matching tenant-owned artifact, or empty when none is observable
 */
    Optional<GeneratedArtifact> findByTenantAndDigest(String tenantId, String sha256);

    /**
 * Records durable qualification evidence without changing the lifecycle state.
 * The expected revision is a transactional compare-and-set token.
* @param id stable artifact or build identifier
* @param expectedRevision current revision required for the compare-and-set update
* @param evidence immutable qualification evidence to record
* @return artifact snapshot with the evidence and incremented revision
 */
    GeneratedArtifact recordEvidence(String id, long expectedRevision, Map<String, String> evidence);

    /**
 * Atomically creates a build or returns the matching nonterminal build for this tenant.
* @param tenantId authenticated tenant that owns the operation or event
* @param requestDigest server-calculated digest of the canonical build request
* @param dualControl whether activation requires an independent approval
* @param trustedMetadata immutable server-established metadata retained with the build
* @param nodes immutable node plans or snapshots belonging to the graph build
* @return new build or matching nonterminal tenant build
 */
    default ProgramBuildSnapshot startOrFindBuild(
            String tenantId, String requestDigest, boolean dualControl,
            Map<String, String> trustedMetadata, List<ProgramBuildNodePlan> nodes) {
        throw new UnsupportedOperationException("durable program builds are not supported");
    }

    /**
 * Finds one build without disclosing a matching identifier owned by another tenant.
* @param tenantId authenticated tenant that owns the operation or event
* @param buildId tenant-scoped durable build identifier
* @return tenant-owned build snapshot, or empty when undisclosed
 */
    default Optional<ProgramBuildSnapshot> findBuild(String tenantId, String buildId) {
        return Optional.empty();
    }

    /**
 * Lists unfinished builds so a restarted application can resume them.
* @return immutable snapshots of unfinished builds
 */
    default List<ProgramBuildSnapshot> listIncompleteBuilds() {
        return List.of();
    }

    /**
 * Transactionally advances one node snapshot using its monotonic revision as a CAS token.
* @param tenantId authenticated tenant that owns the operation or event
* @param buildId tenant-scoped durable build identifier
* @param nodeId graph node identity associated with the operation or event
* @param expectedRevision current revision required for the compare-and-set update
* @param artifactId stable identifier of the generated artifact, or an empty string before creation
* @param phase current server-owned build phase
* @param terminal whether this durable state can advance no further
* @param ready whether the artifact passed every readiness gate
* @param reused whether an existing content-addressed artifact satisfied the build
* @param diagnostic bounded diagnostic text, or an empty string when none is available
* @param smokeOutputJson canonical bounded JSON smoke output, or an empty string when absent
* @return updated node snapshot after the compare-and-set succeeds
 */
    default ProgramBuildNodeSnapshot recordBuildNode(
            String tenantId, String buildId, String nodeId, long expectedRevision,
            String artifactId, ProgramBuildPhase phase, boolean terminal, boolean ready,
            boolean reused, String diagnostic, String smokeOutputJson) {
        throw new UnsupportedOperationException("durable program builds are not supported");
    }

/**
 * Moves an artifact only when its present state matches the expected state.
 * @param id stable artifact identifier.
 * @param expected state required before the transition may be applied.
 * @param target state to record after the compare-and-set succeeds.
 * @return updated artifact snapshot.
 */
    GeneratedArtifact transition(String id, ArtifactState expected, ArtifactState target);

/**
 * Moves an artifact while allowing implementations to attach transition evidence.
 * @param id stable artifact identifier.
 * @param expected state required before the transition may be applied.
 * @param target state to record after the compare-and-set succeeds.
 * @param evidence immutable runtime evidence associated with the transition.
 * @return updated artifact snapshot.
 */
    default GeneratedArtifact transition(String id, ArtifactState expected, ArtifactState target,
                                         Map<String, String> evidence) {
        return transition(id, expected, target);
    }

    /**
 * Admits one artifact for execution on behalf of one tenant (SEC-12; SEC-25).
 *
 * <p>This is the <b>only</b> supported way to obtain an artifact's source for execution.
 * {@link #find(String)} returns a stale snapshot and is not an admission: gating on it is the
 * TOCTOU {@link ProgramAdmission} exists to close, and it is also tenant-blind, which is how a
 * graph belonging to one tenant was able to name another tenant's artifact and run it.
 *
 * <p>Implementations must perform the ownership, state and revision checks inside
 * {@link ProgramAdmission#redeem()} against authoritative state, not here. Returning an admission
 * asserts nothing about whether the artifact will still be executable when it is redeemed --
 * that is the entire point.
 *
 * @throws IllegalArgumentException if the artifact does not exist
 * @param tenantId tenant on whose behalf the execution is requested.
 * @param artifactId artifact that must be owned by that tenant and executable when redeemed.
 * @return revocable admission that performs authoritative checks at redemption time.
 */
    ProgramAdmission admitForExecution(String tenantId, String artifactId);

/**
 * Reserves an artifact's lifecycle transition for work that completes asynchronously.
 * @param id stable artifact identifier.
 * @param expected state required to reserve the transition.
 * @param target state intended when the reservation completes.
 * @return reservation token that must be completed or cancelled.
 */
    ArtifactReservation reserve(String id, ArtifactState expected, ArtifactState target);

/**
 * Commits a reserved transition together with the evidence produced by the work.
 * @param reservation live reservation returned by {@link #reserve(String, ArtifactState, ArtifactState)}.
 * @param evidence immutable validation or execution evidence to retain with the artifact.
 * @return artifact snapshot after successful completion.
 */
    GeneratedArtifact complete(ArtifactReservation reservation, Map<String, String> evidence);

/**
 * Cancels an unfinished reservation, leaving no partially completed transition.
 * @param reservation live reservation to release.
 */
    void cancel(ArtifactReservation reservation);
}
