package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.persistence.JoinBranchOutcome;
import ai.ravenroot.api.persistence.JoinFailureReason;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A join that had already <em>failed</em> when the process died (FIX-15).
 *
 * <p>The twin of {@link JoinRestartRecoveryTest#doesNotFireAJoinASecondTimeAfterARestart}, and
 * deliberately built on the same bench: seed the store with exactly the record a dead runtime would
 * have left, then run the traversal on a fresh {@link GraphRunner} over that store. The store is
 * the whole of what survives a restart, so the seed is the whole of the simulation.</p>
 *
 * <p>The two seeds are not two flavours of the same case. A {@code SATISFIED} record means the work
 * downstream of the join already ran before the crash, so a redelivered branch has nothing left to
 * do and is correctly discarded as late. A {@code FAILED} record means a verdict of failure was
 * reached and — if the crash landed between deciding it and delivering it — was never given to
 * anybody. Discarding that arrival too produced the one outcome a failed join must never produce:
 * a traversal reporting success, with no result and with its end node never executed.</p>
 *
 * <p>These tests isolate the discrimination: making a {@code FAILED} record fall back to a late
 * discard must fail every test here and none in
 * {@link JoinRestartRecoveryTest}.</p>
 */
class JoinFailedRecordRecoveryTest {

    private static final String TENANT = TestIdentities.TENANT_A.tenantId();

    private final JoinTestEngine engine = new JoinTestEngine();
    private final InMemoryJoinStore store = new InMemoryJoinStore();

    @AfterEach
    void closeEngine() {
        engine.close();
        store.close();
    }

    // ------------------------------------------------------- a recorded verdict is still delivered

    /**
     * The defect itself: a join whose timeout fired before the crash comes back as a failure with
     * its own reason, not as a silent success.
     */
    @Test
    void deliversAPersistedTimeoutVerdictAfterARestart() {
        var processInstanceId = UUID.randomUUID();
        var traversalId = UUID.randomUUID();
        // What attemptTimeout leaves behind: b0 counted, b1 never came, the deadline passed.
        seedFailed(processInstanceId, traversalId, Map.of("b0", JoinBranchOutcome.ARRIVED),
                JoinFailureReason.TIMEOUT);
        var monitor = new ExecutionMonitor();

        var error = assertThrows(ExecutionException.class,
                () -> run(monitor, processInstanceId, traversalId),
                "a join that timed out before the crash must not come back as a successful traversal");

        JoinFailureException failure = joinFailure(error);
        assertEquals(JoinFailureException.Reason.TIMEOUT, failure.reason(),
                "the verdict must be the one that was persisted, not one reconstructed here");
        assertEquals("join", failure.nodeId());
        assertFalseThatNodeRan(monitor, "join");
        assertFalseThatNodeRan(monitor, "end");
    }

    /**
     * The second origin, and the reason the reason is persisted at all: a quorum that had become
     * unreachable must re-emerge as {@code QUORUM_UNREACHABLE}, not wearing the timeout's name.
     */
    @Test
    void deliversAPersistedQuorumUnreachableVerdictAfterARestart() {
        var processInstanceId = UUID.randomUUID();
        var traversalId = UUID.randomUUID();
        seedFailed(processInstanceId, traversalId,
                Map.of("b0", JoinBranchOutcome.ARRIVED, "b1", JoinBranchOutcome.FAILED),
                JoinFailureReason.QUORUM_UNREACHABLE);
        var monitor = new ExecutionMonitor();

        var error = assertThrows(ExecutionException.class,
                () -> run(monitor, processInstanceId, traversalId));

        JoinFailureException failure = joinFailure(error);
        assertEquals(JoinFailureException.Reason.QUORUM_UNREACHABLE, failure.reason(),
                "an unreachable quorum must not re-emerge as a timeout");
        assertEquals(List.of("b1"), failure.failed(),
                "the branches the record names are the branches the verdict names");
        assertFalseThatNodeRan(monitor, "end");
    }

    /** The third origin, for completeness: branches that were never taken keep their own name. */
    @Test
    void deliversAPersistedBranchNotTakenVerdictAfterARestart() {
        var processInstanceId = UUID.randomUUID();
        var traversalId = UUID.randomUUID();
        seedFailed(processInstanceId, traversalId,
                Map.of("b0", JoinBranchOutcome.ARRIVED, "b1", JoinBranchOutcome.NOT_TAKEN),
                JoinFailureReason.BRANCH_NOT_TAKEN);

        var error = assertThrows(ExecutionException.class,
                () -> run(new ExecutionMonitor(), processInstanceId, traversalId));

        assertEquals(JoinFailureException.Reason.BRANCH_NOT_TAKEN, joinFailure(error).reason());
    }

    // ----------------------------------------------------- a record written before the reason field

    /**
     * Read compatibility, and what the absence of the field is taken to mean.
     *
     * <p>The seed is written through the three-argument {@code next} — the exact API a runtime
     * predating this field used — so the record genuinely has no reason rather than a null someone
     * chose to write. Loading it must work; the verdict must still be delivered, because a failure
     * nobody can explain is still a failure; and it must be delivered as {@code UNRECORDED} rather
     * than as one of the three real causes, none of which anything ever observed for this join.</p>
     */
    @Test
    void stillDeliversTheVerdictOfARecordWrittenBeforeTheReasonWasPersisted() throws Exception {
        var processInstanceId = UUID.randomUUID();
        var traversalId = UUID.randomUUID();
        var key = new JoinKey(TENANT, processInstanceId, traversalId, "join");
        var now = Instant.now();
        store.compareAndSet(JoinRecord.opening(key, now)
                        .next(Map.of("b0", JoinBranchOutcome.ARRIVED), JoinPhase.FAILED, now))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        var loaded = store.load(key).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(loaded.isPresent(), "a record without the reason field must still load");
        assertEquals(JoinPhase.FAILED, loaded.get().phase());
        assertNull(loaded.get().failureReason(),
                "absence is represented as absence, not as a stand-in reason");

        var error = assertThrows(ExecutionException.class,
                () -> run(new ExecutionMonitor(), processInstanceId, traversalId),
                "a failure whose cause was not recorded is still a failure");
        assertEquals(JoinFailureException.Reason.UNRECORDED, joinFailure(error).reason(),
                "the verdict must admit the cause was lost rather than invent one");
    }

    /** The other half of the invariant: a reason on a record that did not fail is meaningless. */
    @Test
    void refusesAReasonOnARecordThatDidNotFail() {
        var key = new JoinKey(TENANT, UUID.randomUUID(), UUID.randomUUID(), "join");
        var now = Instant.now();

        assertThrows(IllegalArgumentException.class, () -> JoinRecord.opening(key, now)
                .next(Map.of("b0", JoinBranchOutcome.ARRIVED), JoinPhase.SATISFIED, now,
                        JoinFailureReason.TIMEOUT));
        assertThrows(IllegalArgumentException.class, () -> JoinRecord.opening(key, now)
                .next(Map.of("b0", JoinBranchOutcome.ARRIVED), JoinPhase.OPEN, null,
                        JoinFailureReason.TIMEOUT));
    }

    // ---------------------------------------------------------------- the write side of the record

    /**
     * The seeds above are only truthful if the runtime really writes those records. This is the
     * live quorum-unreachable origin: a branch fails, the quorum can no longer be met, and the
     * record left behind carries {@code QUORUM_UNREACHABLE} in the same revision that carries
     * {@code FAILED}.
     */
    @Test
    void writesTheQuorumUnreachableReasonIntoTheRecordThatMarksTheJoinFailed() throws Exception {
        var processInstanceId = UUID.randomUUID();
        var traversalId = UUID.randomUUID();
        // Eviction disabled so the record survives the traversal that produced it and can be read.
        var retaining = new EvictionDisabledJoinStore(store);
        var registry = new BehaviorRegistry();
        registry.register("b0", message ->
                CompletableFuture.completedFuture(NodeResult.continueWith("from-b0")));
        registry.register("b1", message ->
                CompletableFuture.failedFuture(new IllegalStateException("branch exploded")));

        try (var manager = GraphManager.from(JoinMiniGraphs.fanIn(2));
             var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor(),
                     ExecutionIdentitySource.randomUuids(), retaining, Clock.systemUTC())) {
            assertThrows(ExecutionException.class, () -> runner
                    .execute(TestIdentities.TENANT_A, processInstanceId, traversalId, "in", "v1")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS));
        }

        JoinRecord written = requireRecord(processInstanceId, traversalId);
        assertEquals(JoinPhase.FAILED, written.phase());
        assertEquals(JoinFailureReason.QUORUM_UNREACHABLE, written.failureReason(),
                "the origin of the failure is what is persisted, not a default");
    }

    /** The live timeout origin, written by the timeout path rather than by an arrival. */
    @Test
    void writesTheTimeoutReasonIntoTheRecordThatMarksTheJoinFailed() throws Exception {
        var processInstanceId = UUID.randomUUID();
        var traversalId = UUID.randomUUID();
        var retaining = new EvictionDisabledJoinStore(store);
        var registry = new BehaviorRegistry();
        var neverArrives = new CompletableFuture<NodeResult>();
        registry.register("b0", message ->
                CompletableFuture.completedFuture(NodeResult.continueWith("from-b0")));
        registry.register("b1", message -> neverArrives);

        try (var manager = GraphManager.from(
                JoinMiniGraphs.fanIn(2, JoinMiniGraphs.quorumWithTimeout(2, "PT30S")));
             var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor(),
                     ExecutionIdentitySource.randomUuids(), retaining, Clock.systemUTC())) {
            var execution = runner
                    .execute(TestIdentities.TENANT_A, processInstanceId, traversalId, "in", "v1")
                    .toCompletableFuture();

            awaitScheduledTimeout();
            engine.manualScheduler().fireAll();
            assertThrows(ExecutionException.class, () -> execution.get(5, TimeUnit.SECONDS));
            neverArrives.complete(NodeResult.continueWith("from-b1"));
        }

        JoinRecord written = requireRecord(processInstanceId, traversalId);
        assertEquals(JoinPhase.FAILED, written.phase());
        assertEquals(JoinFailureReason.TIMEOUT, written.failureReason(),
                "a timeout must persist its own reason, so it is not mistaken for a lost quorum");
    }

    // ----------------------------------------------------------------------------------- helpers

    private void seedFailed(UUID processInstanceId, UUID traversalId,
                            Map<String, JoinBranchOutcome> branches, JoinFailureReason reason) {
        var key = new JoinKey(TENANT, processInstanceId, traversalId, "join");
        var now = Instant.now();
        store.compareAndSet(JoinRecord.opening(key, now)
                        .next(branches, JoinPhase.FAILED, now, reason))
                .toCompletableFuture().join();
    }

    private JoinRecord requireRecord(UUID processInstanceId, UUID traversalId) throws Exception {
        var key = new JoinKey(TENANT, processInstanceId, traversalId, "join");
        var loaded = store.load(key).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(loaded.isPresent(), "the failed join must have left a record behind");
        JoinRecord record = loaded.get();
        assertNotNull(record.settledAt(), "a terminal record carries the instant it settled");
        return record;
    }

    private GraphExecutionResult run(ExecutionMonitor monitor, UUID processInstanceId, UUID traversalId)
            throws Exception {
        try (var manager = GraphManager.from(JoinMiniGraphs.fanIn(2));
             var runner = new GraphRunner(manager, engine, registry(), monitor,
                     ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC())) {
            return runner.execute(TestIdentities.TENANT_A, processInstanceId, traversalId, "in", "v1")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static BehaviorRegistry registry() {
        var registry = new BehaviorRegistry();
        for (int index = 0; index < 2; index++) {
            String branch = "b" + index;
            registry.register(branch, message ->
                    CompletableFuture.completedFuture(NodeResult.continueWith("from-" + branch)));
        }
        return registry;
    }

    private static void assertFalseThatNodeRan(ExecutionMonitor monitor, String nodeId) {
        assertTrue(monitor.eventsAfter(0).stream()
                        .noneMatch(event -> event.type() == ExecutionEventType.NODE_STARTED
                                && nodeId.equals(event.nodeId())),
                "node '" + nodeId + "' must not run for a traversal whose join had already failed");
    }

    private void awaitScheduledTimeout() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (engine.manualScheduler().liveCount() > 0) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("no join timeout was scheduled");
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
