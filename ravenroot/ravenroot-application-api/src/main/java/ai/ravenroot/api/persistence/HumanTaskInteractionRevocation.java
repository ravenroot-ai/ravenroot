package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Payload-free durable revocation of one signed, task-bound interaction capability. */
public record HumanTaskInteractionRevocation(UUID capabilityId, UUID taskId, long generation,
                                             Instant revokedAt, Instant expiresAt) {
    public HumanTaskInteractionRevocation {
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(taskId, "taskId");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        Objects.requireNonNull(revokedAt, "revokedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!revokedAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException("revocation expiry must be after revocation time");
        }
    }
}
