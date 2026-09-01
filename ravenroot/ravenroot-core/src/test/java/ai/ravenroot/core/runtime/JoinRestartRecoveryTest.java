package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.persistence.JoinBranchOutcome;
import ai.ravenroot.api.persistence.JoinKey;
import ai.ravenroot.api.persistence.JoinPhase;
import ai.ravenroot.api.persistence.JoinRecord;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recovery of an incomplete join across a restart.
 *
 * <p>A crash is simulated by seeding the store with exactly the record a dead runtime would have
 * left — an {@code OPEN} or {@code SATISFIED} join whose owning process never got to discard it —
 * and then running the traversal on a fresh {@link GraphRunner} over the same store. That is a
 * truthful simulation because the store <em>is</em> the whole of what survives: nothing else about a
 * join is durable, so a restart is precisely "this record, and no process memory".</p>
 */
class JoinRestartRecoveryTest {

    private static final String TENANT = TestIdentities.TENANT_A.tenantId();

    private final JoinTestEngine engine = new JoinTestEngine();
    private final InMemoryJoinStore store = new InMemoryJoinStore();

    @AfterEach
    void closeEngine() {
        engine.close();
        store.close();
    }

    /**
     * The property that makes recovery worth having: a join that already fired does not fire again
     * when its branches are redelivered. Without the durable record the restarted runtime would run
     * the join's whole downstream a second time, which is the concrete damage at-least-once delivery
     * does to a fan-in.
     */
    @Test
    void doesNotFireAJoinASecondTimeAfterARestart() throws Exception {
        var processInstanceId = UUID.randomUUID();
        var traversalId = UUID.randomUUID();
        seed(processInstanceId, traversalId, JoinPhase.SATISFIED,
                Map.of("b0", JoinBranchOutcome.ARRIVED, "b1", JoinBranchOutcome.ARRIVED));
        var monitor = new ExecutionMonitor();

        var result = run(monitor, 2, JoinMiniGraphs.fanIn(2), processInstanceId, traversalId, Map.of());

        assertFalse(result.visitedNodes().contains("join"),
                "the join already fired before the restart and must not run again");
        assertFalse(result.visitedNodes().contains("end"));
        assertEquals(2, discardedEvents(monitor, "LATE"), "both redelivered branches are late arrivals");
    }

    /**
     * A branch that already counted is recognised on redelivery, so the quorum moves once for it
     * rather than twice — and the payload it brings back is the payload the join merges.
     */
    @Test
    void countsARedeliveredBranchOnceAndStillUsesItsPayload() throws Exception {
        var processInstanceId = UUID.randomUUID();
        var traversalId = UUID.randomUUID();
        seed(processInstanceId, traversalId, JoinPhase.OPEN, Map.of("b0", JoinBranchOutcome.ARRIVED));
        var monitor = new ExecutionMonitor();
        var releaseB1 = new CompletableFuture<NodeResult>();

        try (var manager = GraphManager.from(JoinMiniGraphs.fanIn(2));
             var runner = new GraphRunner(manager, engine, registry(2, Map.of("b1", releaseB1)), monitor,
                     ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC())) {
            var execution = runner.execute(TestIdentities.TENANT_A, processInstanceId, traversalId, "in", "v1")
                    .toCompletableFuture();

            // b1 is held until b0's redelivery has been recognised as a duplicate. Racing them
            // measures dispatch order instead of deduplication: if b1 lands first, b0's redelivery
            // is the arrival that completes the quorum rather than a duplicate, and the assertion
            // below fails for a reason that has nothing to do with recovery.
            awaitDiscarded(monitor, "DUPLICATE");
            releaseB1.complete(NodeResult.continueWith("from-b1"));

            var result = execution.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("from-b0", "from-b1"), result.payload());
            assertEquals(1, monitor.eventsAfter(0).stream()
                    .filter(event -> event.type() == ExecutionEventType.JOIN_SATISFIED).count());
            assertEquals(1, discardedEvents(monitor, "DUPLICATE"),
                    "b0 was already recorded, so its redelivery must not move the quorum twice");
        }
    }

    /**
     * Correlation alone is not a result. The store says {@code b0} arrived, but its payload died with
     * the process that held it, so a two-branch join cannot produce a merged payload from
     * {@code b1} alone and must keep waiting for the redelivery.
     */
    @Test
    void waitsForTheRedeliveryOfARecoveredBranchWhosePayloadIsGone() throws Exception {
        var processInstanceId = UUID.randomUUID();
        var traversalId = UUID.randomUUID();
        seed(processInstanceId, traversalId, JoinPhase.OPEN, Map.of("b0", JoinBranchOutcome.ARRIVED));
        var monitor = new ExecutionMonitor();
        var neverRedelivered = new CompletableFuture<NodeResult>();

        try (var manager = GraphManager.from(JoinMiniGraphs.fanIn(2, JoinMiniGraphs.quorumWithTimeout(2, "PT30S")));
             var runner = new GraphRunner(manager, engine, registry(2, Map.of("b0", neverRedelivered)),
                     monitor, ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC())) {
            var execution = runner.execute(TestIdentities.TENANT_A, processInstanceId, traversalId, "in", "v1")
                    .toCompletableFuture();

            awaitScheduledTimeout();
            assertFalse(execution.isDone(), "a recovered arrival without a payload cannot satisfy the join");

            engine.manualScheduler().fireAll();
            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> execution.get(5, TimeUnit.SECONDS));
            var failure = joinFailure(error);
            assertEquals(JoinFailureException.Reason.TIMEOUT, failure.reason());
            assertEquals(List.of("b0", "b1"), failure.arrived(),
                    "both branches are recorded as arrived; the join failed for want of a payload, not a count");
            neverRedelivered.complete(NodeResult.continueWith("from-b0"));
        }
    }

    /** The recovery entry point: a restarted runtime can find what it has to resume. */
    @Test
    void enumeratesOpenJoinsLeftBehindAndNotSettledOnes() throws Exception {
        var open = UUID.randomUUID();
        seed(UUID.randomUUID(), open, JoinPhase.OPEN, Map.of("b0", JoinBranchOutcome.ARRIVED));
        seed(UUID.randomUUID(), UUID.randomUUID(), JoinPhase.SATISFIED,
                Map.of("b0", JoinBranchOutcome.ARRIVED, "b1", JoinBranchOutcome.ARRIVED));

        var recoverable = JoinCoordinator.recoverable(store, TENANT).toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(1, recoverable.size(), "a settled join has nothing to resume");
        assertEquals(open, recoverable.getFirst().key().traversalId());
    }

    @Test
    void neverPurgesAnOpenJoinByAge() throws Exception {
        seed(UUID.randomUUID(), UUID.randomUUID(), JoinPhase.OPEN, Map.of("b0", JoinBranchOutcome.ARRIVED));

        long purged = store.purgeSettledBefore(TENANT, Instant.now().plusSeconds(3600))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(0, purged, "purging an unfinished join would silently discard recoverable work");
        assertEquals(1, store.totalRecordCount());
    }

    /** Tenant scoping: one tenant's recovery never sees or purges another's joins. */
    @Test
    void keepsRecoveryWithinOneTenant() throws Exception {
        seed(UUID.randomUUID(), UUID.randomUUID(), JoinPhase.OPEN, Map.of("b0", JoinBranchOutcome.ARRIVED));
        var otherTenant = new JoinKey(TestIdentities.TENANT_B.tenantId(), UUID.randomUUID(), UUID.randomUUID(),
                "join");
        store.compareAndSet(JoinRecord.opening(otherTenant, Instant.now())
                .next(Map.of("b0", JoinBranchOutcome.ARRIVED), JoinPhase.SATISFIED, Instant.now()))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(1, store.recordCount(TENANT).toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(0, store.purgeSettledBefore(TENANT, Instant.now().plusSeconds(3600))
                .toCompletableFuture().get(5, TimeUnit.SECONDS),
                "tenant-a has nothing settled; tenant-b's settled join is not tenant-a's to purge");
        assertEquals(2, store.totalRecordCount());
    }

    // ----------------------------------------------------------------------------------- helpers

    private void seed(UUID processInstanceId, UUID traversalId, JoinPhase phase,
                      Map<String, JoinBranchOutcome> branches) throws Exception {
        var key = new JoinKey(TENANT, processInstanceId, traversalId, "join");
        var now = Instant.now();
        store.compareAndSet(JoinRecord.opening(key, now)
                        .next(branches, phase, phase.terminal() ? now : null))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private GraphExecutionResult run(ExecutionMonitor monitor, int branches,
                                     ai.ravenroot.core.graph.GraphDefinition graph, UUID processInstanceId,
                                     UUID traversalId, Map<String, CompletableFuture<NodeResult>> pending)
            throws Exception {
        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry(branches, pending), monitor,
                     ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC())) {
            return runner.execute(TestIdentities.TENANT_A, processInstanceId, traversalId, "in", "v1")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static BehaviorRegistry registry(int branches, Map<String, CompletableFuture<NodeResult>> pending) {
        var registry = new BehaviorRegistry();
        for (int index = 0; index < branches; index++) {
            String branch = "b" + index;
            registry.register(branch, message -> {
                CompletableFuture<NodeResult> blocked = pending.get(branch);
                return blocked != null ? blocked
                        : CompletableFuture.completedFuture(NodeResult.continueWith("from-" + branch));
            });
        }
        return registry;
    }

    /** Waits until at least one arrival has been discarded for {@code reason}. */
    private static void awaitDiscarded(ExecutionMonitor monitor, String reason) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (discardedEvents(monitor, reason) > 0) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("no arrival was discarded as " + reason);
    }

    private static long discardedEvents(ExecutionMonitor monitor, String reason) {
        return monitor.eventsAfter(0).stream()
                .filter(event -> event.type() == ExecutionEventType.JOIN_ARRIVAL_DISCARDED)
                .filter(event -> event.detail().contains("reason=" + reason))
                .count();
    }

    private void awaitScheduledTimeout() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (engine.manualScheduler().liveCount() > 0) {
                return;
            }
            Thread.sleep(5);
        }
        assertTrue(false, "no join timeout was scheduled");
    }

    private static JoinFailureException joinFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof JoinFailureException failure) {
                return failure;
            }
            current = current.getCause();
        }
        throw new AssertionError("expected a JoinFailureException", error);
    }
}
