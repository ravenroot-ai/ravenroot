package ai.ravenroot.api.programming;

import java.util.Map;
import java.util.UUID;

/**
 * Defines the program request contract exposed to Ravenroot integrators.
 * @param executionId execution to which the invocation belongs.
 * @param nodeId graph node requesting program execution.
 * @param payload input value passed to the artifact.
 * @param attributes immutable message attributes supplied alongside the payload.
 */
public record ProgramRequest(UUID executionId, String nodeId, Object payload, Map<String, Object> attributes) {
/**
 * Requires an execution and node identity, and snapshots absent or mutable attributes safely.
 */
    public ProgramRequest {
        if (executionId == null) throw new IllegalArgumentException("executionId cannot be null");
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId cannot be blank");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
