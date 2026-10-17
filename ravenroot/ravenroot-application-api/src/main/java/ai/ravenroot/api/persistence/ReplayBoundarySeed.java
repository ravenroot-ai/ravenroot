package ai.ravenroot.api.persistence;

import java.util.Set;
import java.util.UUID;

/** A pending derived node and the completed source invocations whose retained values seed it.
 * @param nodeId pending node that will run in the derived execution
 * @param predecessorInvocationIds exact completed source predecessors
 */
public record ReplayBoundarySeed(String nodeId, Set<UUID> predecessorInvocationIds) {
    /** Maximum accepted node identifier length. */
    public static final int MAX_NODE_ID_LENGTH = 200;
    /** Validates and defensively copies the bounded boundary. */
    public ReplayBoundarySeed {
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId cannot be blank");
        nodeId = nodeId.strip();
        if (nodeId.length() > MAX_NODE_ID_LENGTH) throw new IllegalArgumentException("nodeId is too long");
        predecessorInvocationIds = Set.copyOf(predecessorInvocationIds == null
                ? Set.of() : predecessorInvocationIds);
        if (predecessorInvocationIds.isEmpty()
                || predecessorInvocationIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("a replay boundary requires completed predecessor evidence");
        }
        if (predecessorInvocationIds.size() > 256) {
            throw new IllegalArgumentException("a replay boundary has too many predecessor invocations");
        }
    }
}
