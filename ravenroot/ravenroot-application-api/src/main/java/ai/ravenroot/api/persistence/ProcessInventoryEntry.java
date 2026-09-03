package ai.ravenroot.api.persistence;

import ai.ravenroot.api.application.ProcessInstanceStatus;

import java.time.Instant;
import java.util.Optional;

/**
 * One durable process instance as the tenant-scoped inventory reports it.
 *
 * <h2>Both the authoritative status and the derived disposition</h2>
 * <p>{@link #status()} is what the store holds; {@link #disposition()} is what an operator has to act
 * on. They are carried together rather than collapsed because they answer different questions and a
 * caller needs both: a {@code RUNNING} instance whose lease lapsed is still {@code RUNNING} to the
 * lifecycle and {@link InventoryDisposition#INTERRUPTED} to recovery, and reporting only one of those
 * either hides the recovery cohort or falsifies the lifecycle.</p>
 *
 * <h2>Identities are distinct and explicitly related</h2>
 * <p>{@link #key()} carries tenant and process instance. {@link #graphVersionPin()} names the
 * definition, {@link #deploymentId()} names the host, {@link #workloadId()} names the owning workload
 * and {@link #correlationId()} names the causing request. None substitutes for another, and a
 * transient submission is exactly the row whose {@code deploymentId} is absent — which is how the
 * inventory keeps transient and deployment-hosted work under one identity contract without conflating
 * either with a deployment or with a graph version.</p>
 *
 * <h2>Ownership and fencing are two different facts</h2>
 * <p>{@link #fencingToken()} is the instance's current token and survives the lease that issued it;
 * {@link #ownerWorkerId()} and {@link #leaseExpiresAt()} describe the lease itself and are absent
 * once it lapses. A row with a token and no owner is the normal, expected shape of abandoned work,
 * not an inconsistency.</p>
 *
 * @param key                 tenant-scoped identity of the instance
 * @param status              authoritative stored lifecycle status
 * @param disposition         recovery classification derived at read time; see
 *                            {@link InventoryDisposition} for the precedence
 * @param revision            the store revision this row was read at, strictly increasing per instance
 * @param lifecycleGeneration count of authoritative status transitions applied to this instance,
 *                            incremented in the same transaction as the transitions themselves.
 *                            Precisely: it starts at {@code 1} for the batch that creates the
 *                            instance — creation is itself the first transition, into the initial
 *                            status — and every later accepted batch adds the number of
 *                            {@link ExecutionTransition.ProcessTransitioned} transitions that batch
 *                            contains. So it counts <em>per transition</em> and not per batch: a
 *                            single batch moving an instance {@code RUNNING -> WAITING -> RUNNING}
 *                            adds two, which is also why it is not derived by comparing the status
 *                            before and after a batch — that comparison would see no change at all
 *                            and count none. A replayed batch is answered from its idempotency
 *                            record before any increment happens, so an at-least-once redelivery
 *                            cannot inflate it. It is therefore exact for every instance created
 *                            under the schema that introduced it; a row that predates it reports a
 *                            floor of {@code 1}, meaning "at least the transition that created it",
 *                            because no record survives of how many followed.
 *                            <p>Deliberately <strong>not</strong> the fencing token: a generation
 *                            counts how far the lifecycle has moved, a token names who may move it,
 *                            and one worker can apply many transitions under a single token while a
 *                            contested instance can change owner without its status moving at
 *                            all.</p>
 * @param graphVersionPin     the write-once definition this instance replays against
 * @param deploymentId        hosting deployment, absent for a transient submission
 * @param workloadId          owning workload, when the caller models one
 * @param correlationId       caller correlation identity for the causing request
 * @param ownerWorkerId       worker holding an unexpired lease, absent when none does
 * @param fencingToken        the instance's current fencing token, which outlives every lease
 * @param leaseExpiresAt      expiry of the live lease on the store's clock, absent when none is live
 * @param traversalCount      number of traversals contained by this instance
 * @param createdAt           when the instance was first written; immutable, and the primary
 *                            pagination axis
 * @param updatedAt           when it was last written; diagnostic only, never an ordering primitive
 * @param retainedUntil       when a terminal row becomes eligible for
 *                            {@link ExecutionStore#purgeExpiredProcessInstances(String)}; absent while
 *                            the instance is non-terminal, because retention has not started
 */
public record ProcessInventoryEntry(ExecutionKey key, ProcessInstanceStatus status,
                                    InventoryDisposition disposition, long revision,
                                    long lifecycleGeneration, GraphVersionPin graphVersionPin,
                                    Optional<String> deploymentId, Optional<String> workloadId,
                                    Optional<String> correlationId, Optional<String> ownerWorkerId,
                                    long fencingToken, Optional<Instant> leaseExpiresAt,
                                    int traversalCount, Instant createdAt, Instant updatedAt,
                                    Optional<Instant> retainedUntil) {

    /** Rejects an inventory row that could not describe a real stored instance. */
    public ProcessInventoryEntry {
        if (key == null) throw new IllegalArgumentException("key cannot be null");
        if (status == null) throw new IllegalArgumentException("status cannot be null");
        if (disposition == null) throw new IllegalArgumentException("disposition cannot be null");
        if (graphVersionPin == null) throw new IllegalArgumentException("graphVersionPin cannot be null");
        if (createdAt == null) throw new IllegalArgumentException("createdAt cannot be null");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt cannot be null");
        if (lifecycleGeneration < 0) {
            throw new IllegalArgumentException("lifecycleGeneration cannot be negative");
        }
        if (traversalCount < 0) throw new IllegalArgumentException("traversalCount cannot be negative");
        deploymentId = deploymentId == null ? Optional.empty() : deploymentId;
        workloadId = workloadId == null ? Optional.empty() : workloadId;
        correlationId = correlationId == null ? Optional.empty() : correlationId;
        ownerWorkerId = ownerWorkerId == null ? Optional.empty() : ownerWorkerId;
        leaseExpiresAt = leaseExpiresAt == null ? Optional.empty() : leaseExpiresAt;
        retainedUntil = retainedUntil == null ? Optional.empty() : retainedUntil;
    }

    /**
     * The origin components as one value, for a caller that wants to carry them together.
     * @return deployment, workload and correlation identities of this row.
     */
    public ExecutionOrigin origin() {
        return new ExecutionOrigin(deploymentId, workloadId, correlationId);
    }
}
