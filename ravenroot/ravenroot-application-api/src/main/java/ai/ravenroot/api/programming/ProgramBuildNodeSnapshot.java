package ai.ravenroot.api.programming;

import java.time.Instant;

/**
 * Durable observation of one node in a graph-level program build.
* @param buildId tenant-scoped durable build identifier
* @param tenantId authenticated tenant that owns the operation or event
* @param plan canonical node input retained for resumable execution
* @param artifactId stable identifier of the generated artifact, or an empty string before creation
* @param phase current server-owned build phase
* @param revision positive monotonic revision used for compare-and-set updates
* @param createdAt time at which the durable build state was created
* @param updatedAt time at which the durable state was last updated
* @param terminal whether this durable state can advance no further
* @param ready whether the artifact passed every readiness gate
* @param reused whether an existing content-addressed artifact satisfied the build
* @param diagnostic bounded diagnostic text, or an empty string when none is available
* @param smokeOutputJson canonical bounded JSON smoke output, or an empty string when absent
 */
public record ProgramBuildNodeSnapshot(
        String buildId,
        String tenantId,
        ProgramBuildNodePlan plan,
        String artifactId,
        ProgramBuildPhase phase,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        boolean terminal,
        boolean ready,
        boolean reused,
        String diagnostic,
        String smokeOutputJson) {

    /** Validates and normalizes one durable node-build observation. */
public ProgramBuildNodeSnapshot {
        if (buildId == null || buildId.isBlank()) throw new IllegalArgumentException("buildId is required");
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
        if (plan == null) throw new IllegalArgumentException("plan is required");
        artifactId = artifactId == null ? "" : artifactId;
        if (phase == null) throw new IllegalArgumentException("phase is required");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        diagnostic = diagnostic == null ? "" : diagnostic;
        smokeOutputJson = smokeOutputJson == null ? "" : smokeOutputJson;
    }
}
