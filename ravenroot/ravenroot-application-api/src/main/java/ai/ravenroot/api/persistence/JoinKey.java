package ai.ravenroot.api.persistence;

import java.util.UUID;

/**
 * Tenant-scoped identity of one fan-in join (CORE-03).
 *
 * <p>A join is identified by the <em>traversal</em> it belongs to and the <em>node</em> that joins,
 * never by an invocation or an attempt. {@link PendingWork} states the reason: fan-in "models
 * arrivals that precede invocation creation", so at the moment an arrival is recorded the join node
 * has no invocation and no attempt to be keyed by. Keying on one would require inventing an identity
 * before the thing it identifies exists.</p>
 *
 * <p>{@code processInstanceId} is carried although {@code traversalId} alone is unique. Recovery
 * enumerates joins per tenant ({@link JoinStore#openJoins(String)}) and must correlate each one back
 * to the instance whose aggregate it belongs to; without the instance identifier that correlation
 * needs a second index that only the runtime could build, from state it does not have after a
 * restart.</p>
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @param processInstanceId the stable process instance id used to identify the requested resource.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param joinNodeId the stable join node id used to identify the requested resource.
 */
public record JoinKey(String tenantId, UUID processInstanceId, UUID traversalId, String joinNodeId) {
    /** Validates the tenant, execution and node coordinates of a fan-in join. */
    public JoinKey {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be blank");
        }
        if (processInstanceId == null) {
            throw new IllegalArgumentException("processInstanceId cannot be null");
        }
        if (traversalId == null) {
            throw new IllegalArgumentException("traversalId cannot be null");
        }
        if (joinNodeId == null || joinNodeId.isBlank()) {
            throw new IllegalArgumentException("joinNodeId cannot be blank");
        }
    }

/**
 * The instance this join belongs to, for correlation with {@link ExecutionStore}.
 * @return execution aggregate key that owns this fan-in join.
 */
    public ExecutionKey executionKey() {
        return new ExecutionKey(tenantId, processInstanceId);
    }
}
