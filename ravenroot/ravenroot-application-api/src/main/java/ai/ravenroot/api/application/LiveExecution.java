package ai.ravenroot.api.application;

import java.time.Instant;
import java.util.UUID;

/**
 * One live execution's identity: a traversal that is currently accepted and not yet
 * terminal, read directly from the runtime's own active-execution bookkeeping.
 *
 * <p>This is deliberately not derived from {@link ExecutionEvent}. An execution whose behavior has
 * deadlocked or is otherwise stalled stops publishing events entirely — that is precisely the case
 * an operator needs this for — so a listing built from recent events would show every healthy
 * execution and silently omit the one that matters. {@link RavenrootApplication#liveExecutions}
 * instead reads the same bookkeeping {@link RavenrootApplication#cancelTraversal} mutates, so
 * "currently listed" and "currently cancellable" are the same set by construction.
 * @param processInstanceId the stable process instance id used to identify the requested resource.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param graphVersion the graph version constraint applied while processing the request.
 * @param startedAt instant at which the execution was admitted to the runtime
 */
public record LiveExecution(UUID processInstanceId, UUID traversalId, String graphVersion, Instant startedAt) {
/**
 * Validates an active execution snapshot before it is exposed to a tenant-scoped caller.
 */
    public LiveExecution {
        if (processInstanceId == null) throw new IllegalArgumentException("processInstanceId cannot be null");
        if (traversalId == null) throw new IllegalArgumentException("traversalId cannot be null");
        graphVersion = graphVersion == null ? "" : graphVersion;
        if (startedAt == null) throw new IllegalArgumentException("startedAt cannot be null");
    }

/**
 * Compatibility alias, matching {@link ExecutionSubmission#executionId()}: one traversal.
 * @return legacy execution identifier, equal to the active traversal ID
 */
    public UUID executionId() {
        return traversalId;
    }
}
