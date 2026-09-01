package ai.ravenroot.core.runtime;

import java.util.Objects;
import java.util.UUID;

/** Complete identity of one traversal-scoped logical-node runtime. */
record TraversalInstanceIdentity(String tenantId, String deploymentId, String graphVersion,
                                 UUID processInstanceId, UUID traversalId, String nodeId) {
    TraversalInstanceIdentity {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(graphVersion, "graphVersion");
        Objects.requireNonNull(processInstanceId, "processInstanceId");
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(nodeId, "nodeId");
    }

    String actorName() { return nodeId + "-traversal-" + traversalId; }
}
