package ai.ravenroot.api.programming;

import java.time.Instant;
import java.util.Map;

/**
 * Defines the generated artifact contract exposed to Ravenroot integrators.
 * @param id registry-assigned artifact identifier.
 * @param language runtime language token chosen for the source.
 * @param sha256 content digest used to identify the source revision.
 * @param source retained program source, possibly empty for a new draft.
 * @param state current lifecycle state.
 * @param revision monotonically increasing lifecycle revision.
 * @param createdAt time at which the artifact was first registered.
 * @param updatedAt time at which this snapshot was last updated.
 * @param metadata immutable author and tooling metadata.
 */
public record GeneratedArtifact(
        String id,
        String language,
        String sha256,
        String source,
        ArtifactState state,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Map<String, String> metadata) {

/**
 * Enforces required identity fields and snapshots metadata while assigning safe creation defaults.
 */
    public GeneratedArtifact {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Artifact id cannot be blank");
        if (language == null || language.isBlank()) throw new IllegalArgumentException("Language cannot be blank");
        if (sha256 == null || sha256.isBlank()) throw new IllegalArgumentException("SHA-256 cannot be blank");
        source = source == null ? "" : source;
        if (state == null) throw new IllegalArgumentException("Artifact state cannot be null");
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
