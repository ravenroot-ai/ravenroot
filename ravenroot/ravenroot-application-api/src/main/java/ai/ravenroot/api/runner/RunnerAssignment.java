package ai.ravenroot.api.runner;

import java.util.Objects;
import java.util.UUID;

/**
 * Versioned dispatch view. Contains no graph checkpoint, bearer credential or operator context.
 * @param protocolVersion exact supported wire version
 * @param workspaceId opaque process workspace identity
 * @param job accepted job with pinned definition, authority and execution fence
 */
public record RunnerAssignment(int protocolVersion, UUID workspaceId, RunnerJob job) {
    /** Validates the wire version and complete dispatch identity. */
    public RunnerAssignment {
        if (protocolVersion != RunnerRegistration.PROTOCOL_VERSION) {
            throw new IllegalArgumentException("unsupported runner protocol");
        }
        Objects.requireNonNull(workspaceId); Objects.requireNonNull(job);
    }
}
