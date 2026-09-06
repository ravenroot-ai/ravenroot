package ai.ravenroot.core.deployment;

import ai.ravenroot.api.deployment.DeploymentId;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * In-process serialization per {@code (tenant, deployment)}, sitting above durable compare-and-set
 * and never replacing it (ADR 0038 D8).
 *
 * <h2>Why an in-process lock exists at all when the CAS is already correct</h2>
 * <p>The registry's compare-and-set is what makes two <em>processes</em> safe, and no lock held in
 * one JVM can do that job. But the CAS on its own turns concurrent callers inside one process into a
 * retry storm: every one of them reads the same revision, every one but the winner is refused with
 * {@code Conflict}, and every one of them re-reads and tries again. Under a burst of retries for the
 * same deployment — which is exactly the traffic an idempotency key exists to absorb — that is the
 * shape of a livelock, and the deployment it is happening to is the one already under load. The lock
 * collapses those callers into one attempt, and the CAS still decides who wins against the other
 * process. Removing the lock leaves correctness intact and throughput ruined; removing the CAS
 * leaves nothing safe at all.</p>
 *
 * <p>The precedent is in this repository: {@code DefaultGraphDeployment} already collapses concurrent
 * starts and stops onto a single in-flight stage under its own lock, for the same reason.</p>
 *
 * <h2>The unit is the deployment, never the tenant</h2>
 * <p>Serializing per tenant would be simpler and would be wrong. A drain with a two-minute bound
 * would hold the tenant's lock for two minutes, during which every other deployment that tenant owns
 * — independent aggregates, independent runtimes, independent leases — would be unable to answer a
 * single command. Nothing about the correctness argument needs that: two deployments never share a
 * revision, a generation, a fence or an idempotency scope, so there is nothing between them to
 * serialize. The property is discovered under load rather than in a test, which is why it is fixed
 * here as a decision rather than left to whichever key looked convenient.</p>
 *
 * <h2>The map does not grow without bound</h2>
 * <p>A mutex is created on first use and removed once the last holder leaves, tracked by an explicit
 * waiter count under the map's own per-key atomicity. A cache that only ever inserted would be a leak
 * proportional to the number of distinct deployments the process has ever seen, which on a
 * long-running authority is every deployment of every tenant it has ever been asked about.</p>
 */
public final class DeploymentSingleFlight {

    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Runs {@code work} with no other caller of this instance running for the same deployment.
     *
     * <p>Re-entrant for the same thread, because the coordinator legitimately reaches the reconciler's
     * convergence helper while already holding the deployment: a non-re-entrant lock would deadlock a
     * thread against itself for doing exactly what the design asks of it.</p>
     *
     * @param tenantId tenant owning the deployment.
     * @param deploymentId deployment to serialize on.
     * @param work operation to run exclusively for that deployment.
     * @param <T> value the operation produces.
     * @return whatever {@code work} returned.
     */
    public <T> T inFlight(String tenantId, DeploymentId deploymentId, Supplier<T> work) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(work, "work");
        Key key = new Key(tenantId, deploymentId);
        Entry entry = entries.compute(key, (ignored, existing) -> {
            Entry held = existing == null ? new Entry() : existing;
            held.waiters++;
            return held;
        });
        entry.lock.lock();
        try {
            return work.get();
        } finally {
            entry.lock.unlock();
            entries.compute(key, (ignored, existing) -> {
                if (existing == null) return null;
                existing.waiters--;
                return existing.waiters == 0 ? null : existing;
            });
        }
    }

    /**
     * Returns how many deployments currently have a mutex, so a test can prove the map is released.
     *
     * <p>Exposed because "the cache does not leak" is otherwise a claim no test can check without
     * reaching into private state, and an unbounded map is precisely the kind of defect that is
     * invisible until the process has been up for a week.</p>
     *
     * @return number of deployments with a live mutex.
     */
    public int trackedDeployments() {
        return entries.size();
    }

    private record Key(String tenantId, DeploymentId deploymentId) {}

    private static final class Entry {
        private final ReentrantLock lock = new ReentrantLock();
        private int waiters;
    }
}
