package ai.ravenroot.api.persistence;

import ai.ravenroot.api.application.ProcessInstance;

import java.time.Instant;

/**
 * Store-side envelope around the pure PERS-01 aggregate (ADR 0010 section 2).
 *
 * <p>The aggregate stays pure and ADR 0007 is not reopened: everything persistence needs that the
 * domain does not model lives here instead.</p>
 *
 * @param state          the validated aggregate
 * @param revision       opaque, <em>strictly increasing</em> per process instance — the contract
 *                       guarantees strict increase, <strong>not</strong> contiguity, so a
 *                       distributed adapter may use a log offset or a hybrid logical clock
 * @param graphVersionPin write-once pin, set at creation and rejected on change
 * @param tenantId       opaque tenant scope; the port does not define the isolation mechanism
 * @param updatedAt      <strong>diagnostic only</strong>. Never a concurrency or ordering
 *                       primitive; comparing these across nodes would inherit clock skew
 */
public record StoredProcessInstance(ProcessInstance state, long revision, GraphVersionPin graphVersionPin,
                                    String tenantId, Instant updatedAt) {
    /** Validates the revisioned snapshot returned by the execution store. */
    public StoredProcessInstance {
        if (state == null) throw new IllegalArgumentException("state cannot be null");
        if (graphVersionPin == null) throw new IllegalArgumentException("graphVersionPin cannot be null");
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId cannot be blank");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt cannot be null");
    }

/**
 * Derives the store key from the tenant scope and aggregate identifier.
 * @return execution key identifying this snapshot.
 */
    public ExecutionKey key() {
        return new ExecutionKey(tenantId, state.processInstanceId());
    }
}
