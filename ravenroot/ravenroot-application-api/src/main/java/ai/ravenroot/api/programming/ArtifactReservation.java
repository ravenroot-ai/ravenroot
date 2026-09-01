package ai.ravenroot.api.programming;

import java.util.Objects;
import java.util.UUID;

/**
 * Opaque, single-use ownership of one lifecycle side-effect slot.
 * @param token unguessable single-use reservation identity.
 * @param artifact snapshot whose lifecycle transition is reserved.
 * @param target lifecycle state to apply when the reservation completes.
 */
public record ArtifactReservation(UUID token, GeneratedArtifact artifact, ArtifactState target) {
/**
 * Requires all fields because a missing reservation detail could apply a transition ambiguously.
 */
    public ArtifactReservation {
        token = Objects.requireNonNull(token, "token");
        artifact = Objects.requireNonNull(artifact, "artifact");
        target = Objects.requireNonNull(target, "target");
    }
}
