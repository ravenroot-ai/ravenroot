package ai.ravenroot.core.deployment;

import ai.ravenroot.api.deployment.lifecycle.DeploymentLifecycleTarget;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry.Record;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Finishes the lifecycle commands a crash or a takeover left half-applied (issue 91 criterion 2).
 *
 * <h2>The same loop as execution recovery, one level up</h2>
 * <p>{@code ExecutionRecoveryService} sweeps tenants, claims work, decides what it is looking at,
 * applies the decision under a fenced expectation, and acknowledges — returning typed outcomes rather
 * than only logging them. This is that loop for deployments rather than for attempts, and it is
 * deliberately shaped the same way: one instance per process, sweeping every tenant it was
 * configured with, mounted beside the execution recovery service. Writing a different loop here would
 * mean two answers in one process to "how does a sweep decide it may act", and the two would drift.</p>
 *
 * <h2>What makes an intent outstanding</h2>
 * <p>The registry records intent on one axis and evidence on another: {@code generation} moves when a
 * lifecycle decision is recorded, and {@code observed.observedGeneration} moves when an owner
 * publishes what the runtime became. An intent is outstanding exactly when the second is behind the
 * first, and that comparison is the whole discovery rule. It needs no extra durable flag written
 * between the intent and the effect — which is the tempting alternative and would simply move the
 * crash window one write earlier rather than closing it.</p>
 *
 * <h2>Why this cannot duplicate an action</h2>
 * <p>Three things together, and all three are needed:</p>
 * <ol>
 *   <li><b>One owner.</b> The effect runs only under a lease this process holds, and the registry
 *       hands out at most one live lease per deployment. A superseded owner's evidence write is
 *       refused by the fence, so it cannot even report having acted.</li>
 *   <li><b>Idempotence per generation.</b> {@link DeploymentLifecycleTarget} requires that applying
 *       the same operation twice at one generation has the effect of applying it once, so re-driving
 *       an intent whose effect already ran is a no-op rather than a second action.</li>
 *   <li><b>Evidence closes the window.</b> Once the observation names the generation, the intent
 *       stops being outstanding and no later sweep considers it at all.</li>
 * </ol>
 * <p>Take away the lease and two processes act; take away idempotence and the surviving owner acts
 * twice; take away the evidence write and every sweep forever re-drives the same intent.</p>
 *
 * <h2>One sweep, not a loop</h2>
 * <p>{@link #sweepOnce()} runs exactly one pass so the caller owns the schedule and a test owns the
 * clock — the same reason the execution recovery service is shaped that way. A reconciler that ran
 * its own timer would be untestable without waiting in real time, and would decide a scheduling
 * policy that belongs to whoever mounts it.</p>
 */
public final class DeploymentReconciler {

    private final DeploymentRegistry registry;
    private final DeploymentTargets targets;
    private final DeploymentSingleFlight singleFlight;
    private final DeploymentOwnership ownership;
    private final List<String> tenantIds;
    private final Duration drainBound;
    private final int batchLimit;

    /**
     * Creates a reconciler for the tenants this process was configured with.
     *
     * <p>Tenants come from configuration rather than from an enumeration on the port, for the reason
     * {@code ExecutionRecoveryService} gives: a "list every tenant" operation would have to undo
     * physical isolation, and cross-tenant fairness is a scheduling policy the runtime owns rather
     * than something a store can express. Visiting the configured tenants in order <em>is</em> the
     * fairness policy.</p>
     *
     * @param registry durable authority for intent, evidence and leases.
     * @param targets resolver for the runtimes this process actually hosts.
     * @param singleFlight per-deployment in-process serialization, shared with the coordinator so a
     *                     sweep and a command never drive the same deployment at once.
     * @param tenantIds tenants this process sweeps, in the order it sweeps them.
     * @param ownerId stable identity this process presents when it takes a lease.
     * @param leaseTtl positive lease duration requested when ownership is taken.
     * @param drainBound bound this service gives a drain it recovers, which no durable intent carries.
     * @param batchLimit positive page size used to walk a tenant's deployments.
     * @param clock time authority used only to decide whether this process's own lease is worth
     *              reusing; lease expiry itself is always decided by the registry's clock.
     */
    public DeploymentReconciler(DeploymentRegistry registry, DeploymentTargets targets,
                                DeploymentSingleFlight singleFlight, List<String> tenantIds,
                                String ownerId, Duration leaseTtl, Duration drainBound,
                                int batchLimit, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.singleFlight = Objects.requireNonNull(singleFlight, "singleFlight");
        this.tenantIds = List.copyOf(Objects.requireNonNull(tenantIds, "tenantIds"));
        this.ownership = new DeploymentOwnership(registry, ownerId, leaseTtl, clock);
        Objects.requireNonNull(drainBound, "drainBound");
        if (drainBound.isNegative() || drainBound.isZero()) {
            throw new IllegalArgumentException("drainBound must be positive");
        }
        this.drainBound = drainBound;
        if (batchLimit < 1) throw new IllegalArgumentException("batchLimit must be positive");
        this.batchLimit = batchLimit;
    }

    /**
     * Runs one sweep across every configured tenant, in configuration order.
     *
     * @return what this sweep decided about each deployment it considered, in visit order.
     */
    public List<DeploymentReconcileOutcome> sweepOnce() {
        var outcomes = new ArrayList<DeploymentReconcileOutcome>();
        for (String tenantId : tenantIds) {
            outcomes.addAll(sweepOnce(tenantId));
        }
        return List.copyOf(outcomes);
    }

    /**
     * Runs the same bounded sweep for one configured tenant.
     *
     * <p>A tenant this reconciler was not configured with produces nothing rather than an error: the
     * question "reconcile a tenant I do not serve" has a correct answer, and it is "not mine".</p>
     *
     * @param tenantId tenant to sweep.
     * @return what this sweep decided about each of that tenant's deployments it considered.
     */
    public List<DeploymentReconcileOutcome> sweepOnce(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (!tenantIds.contains(tenantId)) return List.of();
        var outcomes = new ArrayList<DeploymentReconcileOutcome>();
        int limit = Math.min(batchLimit, registry.limits().maximumPageSize());
        String cursor = null;
        do {
            DeploymentRegistry.Page page = DeploymentOwnership.await(
                    registry.list(tenantId, cursor, limit));
            for (Record record : page.items()) {
                if (!outstanding(record)) continue;
                singleFlight.inFlight(record.tenantId(), record.deploymentId(),
                        () -> outcomes.add(reconcile(record)));
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        return List.copyOf(outcomes);
    }

    /**
     * Whether durable intent is ahead of durable evidence for this deployment.
     *
     * <p>A tombstoned deployment is excluded whatever its counters say. Its aggregate accepts no
     * further command, so there is no intent left to make true, and driving a runtime towards the
     * intent of something that has been removed would be acting on a decision that has already been
     * superseded by the most absorbing one there is.</p>
     */
    private boolean outstanding(Record record) {
        return record.tombstone() == null
                && record.observed().observedGeneration() < record.generation();
    }

    private DeploymentReconcileOutcome reconcile(Record read) {
        // Re-read under the deployment's own single-flight: the page was assembled before this
        // process took the lock, so a command accepted in between would otherwise be applied against
        // a revision that has already moved, and every such attempt would be a wasted CAS refusal.
        Optional<Record> current = DeploymentOwnership.await(
                registry.get(read.tenantId(), read.deploymentId()));
        if (current.isEmpty() || !outstanding(current.get())) {
            return new DeploymentReconcileOutcome.Deferred(read.tenantId(), read.deploymentId(),
                    read.generation(), "already current");
        }
        Record record = current.get();

        Optional<DeploymentLifecycleTarget> target =
                targets.resolve(record.tenantId(), record.deploymentId());
        if (target.isEmpty()) {
            return new DeploymentReconcileOutcome.Deferred(record.tenantId(), record.deploymentId(),
                    record.generation(), "no deployment runtime hosted here");
        }

        Record owned = ownership.own(record);
        if (!ownership.holds(owned)) {
            String holder = owned.lease() == null ? "none" : owned.lease().owner();
            return new DeploymentReconcileOutcome.NotOwned(record.tenantId(), record.deploymentId(),
                    record.generation(), holder);
        }

        long generation = owned.generation();
        try {
            LifecycleEffects.converge(target.get(), owned.desired(), generation, drainBound);
        } catch (RuntimeException failure) {
            ownership.report(owned, failure);
            return new DeploymentReconcileOutcome.Failed(record.tenantId(), record.deploymentId(),
                    generation, DeploymentOwnership.classify(failure));
        }

        Record evidenced = ownership.observe(owned, target.get(), generation);
        return new DeploymentReconcileOutcome.Reconciled(record.tenantId(), record.deploymentId(),
                generation, evidenced.lease().fence());
    }
}
