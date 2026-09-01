package ai.ravenroot.api.programming;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Tenant-scoped durable graph build/job snapshot returned by start and status operations.
* @param id stable artifact or build identifier
* @param tenantId authenticated tenant that owns the operation or event
* @param requestDigest server-calculated digest of the canonical build request
* @param dualControl whether activation requires an independent approval
* @param revision positive monotonic revision used for compare-and-set updates
* @param createdAt time at which the durable build state was created
* @param updatedAt time at which the durable state was last updated
* @param terminal whether this durable state can advance no further
* @param trustedMetadata immutable server-established metadata retained with the build
* @param nodes immutable node plans or snapshots belonging to the graph build
 */
public record ProgramBuildSnapshot(
        String id,
        String tenantId,
        String requestDigest,
        boolean dualControl,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        boolean terminal,
        Map<String, String> trustedMetadata,
        List<ProgramBuildNodeSnapshot> nodes) {

    /** Validates and snapshots the durable graph-build state. */
public ProgramBuildSnapshot {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("build id is required");
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenant is required");
        if (requestDigest == null || requestDigest.isBlank()) throw new IllegalArgumentException("request digest is required");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        trustedMetadata = trustedMetadata == null ? Map.of() : Map.copyOf(trustedMetadata);
        nodes = List.copyOf(nodes);
        if (nodes.isEmpty() || nodes.size() > 256) throw new IllegalArgumentException("one to 256 nodes are required");
    }
}
