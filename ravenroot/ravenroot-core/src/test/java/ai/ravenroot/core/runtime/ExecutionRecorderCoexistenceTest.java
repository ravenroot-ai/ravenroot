package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coexistence guarantee: <strong>the recovery loop never claims an attempt a live engine
 * is executing.</strong>
 *
 * <p>Proved by a test rather than by an argument, which is the point — the argument was made three
 * times in three deferrals and was correct each time, and the work still never happened.</p>
 *
 * <h2>Every case here asserts the converse too, and that is not decoration</h2>
 * <p>A test that passes because a sweep returned nothing is indistinguishable from a test passing
 * against a sweep that is simply broken. So each case also drives the lease away and asserts the work
 * <em>does</em> come back. Only the pair means anything: the first shows the guard holds, the second
 * shows there was something for it to hold back.</p>
 */
class ExecutionRecorderCoexistenceTest {

    private static final String TENANT = "acme";
    private static final Duration TTL = Duration.ofSeconds(30);

    private final MovableClock clock = new MovableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryExecutionStore store = new InMemoryExecutionStore(clock);

    @AfterEach
    void closeStore() {
        store.close();
    }

    @Test
    void aRecoverySweepNeverClaimsAnAttemptALiveEngineIsExecuting() {
        ExecutionKey key = newInstance();
        ExecutionRecorder recorder = ExecutionRecorder.open(store, key, "live-engine", TTL, revisionOf(key));
        try {
            // The engine records intention and dispatch, exactly as GraphRunner does immediately
            // before the send: after this the attempt is RUNNING and therefore claimable in principle.
            UUID invocationId = UUID.randomUUID();
            UUID attemptId = UUID.randomUUID();
            recorder.record(nodeStarted(key, invocationId, attemptId), List.of());
            assertEquals(NodeAttemptStatus.RUNNING, attemptStatus(key));

            // A recovery sweep runs against the same tenant while that engine is still executing.
            List<PendingWork> claimed = await(store.claimPendingWork(TENANT, "recovery-1", 10, TTL));
            assertTrue(claimed.isEmpty(),
                    "the sweep claimed work a live engine is executing; that is double execution of "
                            + "an unfenced effect, or the same work parked as ambiguous while it "
                            + "proceeds perfectly well");

            // A node that legitimately runs longer than one TTL. Renewal happens *before* expiry --
            // renewing an already-expired lease is the crash case and the store rightly refuses it --
            // so the engine keeps the instance across a span longer than the TTL it started with.
            clock.advance(TTL.dividedBy(2));
            recorder.renewNow();
            clock.advance(TTL.dividedBy(2).plusSeconds(1));
            assertTrue(clock.instant().isAfter(Instant.parse("2026-01-01T00:00:00Z").plus(TTL)),
                    "the fixture must actually outlive one TTL, or it proves nothing about renewal");
            assertTrue(await(store.claimPendingWork(TENANT, "recovery-1", 10, TTL)).isEmpty(),
                    "renewal is what keeps healthy long-running work out of the sweep's hands; "
                            + "without it PERS-04 would park work that is proceeding perfectly well");
        } finally {
            recorder.close();
        }

        // THE CONVERSE. Without it the assertions above would pass against a sweep that never
        // returns anything at all, which is a broken control wearing the shape of a working one.
        List<PendingWork> afterRelease = await(store.claimPendingWork(TENANT, "recovery-1", 10, TTL));
        assertEquals(1, afterRelease.size(),
                "once the engine is gone the work must be recoverable, or nothing was being held back");
        assertEquals(NodeAttemptStatus.RUNNING, attemptStatus(key));
    }

    @Test
    void anEngineThatStopsRenewingLosesTheInstanceToTheSweep() {
        ExecutionKey key = newInstance();
        ExecutionRecorder recorder = ExecutionRecorder.open(store, key, "doomed-engine", TTL, revisionOf(key));
        recorder.record(nodeStarted(key, UUID.randomUUID(), UUID.randomUUID()), List.of());

        // The crash: no release, no renewal, nothing. Exactly what a kill -9 leaves behind.
        clock.advance(TTL.plusSeconds(1));

        assertEquals(1, await(store.claimPendingWork(TENANT, "recovery-1", 10, TTL)).size(),
                "a dead engine's lease expires and its work becomes recoverable; that is the whole "
                        + "reason the claim query was widened to RUNNING in PERS-04");
    }

    @Test
    void aSecondEngineRefusesTheInstanceRatherThanRunningAnUnfencedDuplicate() {
        ExecutionKey key = newInstance();
        ExecutionRecorder first = ExecutionRecorder.open(store, key, "engine-a", TTL, revisionOf(key));
        try {
            var busy = assertThrows(ExecutionInstanceBusyException.class,
                    () -> ExecutionRecorder.open(store, key, "engine-b", TTL, revisionOf(key)));
            assertEquals(key.processInstanceId(), busy.key().processInstanceId(),
                    "the refusal must name the instance, or an operator cannot look up who holds it");

            // Distinguishable from a fault: its own type, not a store failure and not a bare
            // IllegalStateException carrying prose. The two need opposite responses -- this one
            // resolves itself when the holder finishes or its lease expires, a store fault is an
            // incident -- and an operator who cannot tell them apart will treat one as the other.
            assertFalse(ai.ravenroot.api.persistence.ExecutionStoreException.class
                            .isAssignableFrom(busy.getClass()),
                    "contention must not be reported as a store failure");
            assertEquals(ExecutionInstanceBusyException.class, busy.getClass());
        } finally {
            first.close();
        }

        // Converse: once the holder is gone the second engine is admitted.
        ExecutionRecorder second = ExecutionRecorder.open(store, key, "engine-b", TTL, revisionOf(key));
        assertTrue(second.holdsFence());
        second.close();
    }

    @Test
    void anEngineThatLostTheFenceStopsWritingRatherThanDiscoveringItAfterTheNextEffect() {
        ExecutionKey key = newInstance();
        ExecutionRecorder recorder = ExecutionRecorder.open(store, key, "engine-a", TTL, revisionOf(key));
        recorder.record(nodeStarted(key, UUID.randomUUID(), UUID.randomUUID()), List.of());

        // Another worker takes the instance after this one's lease lapses, which fences it out.
        clock.advance(TTL.plusSeconds(1));
        await(store.claim(key, "engine-b", TTL));

        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        assertThrows(RuntimeException.class,
                () -> recorder.record(nodeStarted(key, invocationId, attemptId), List.of()));
        assertFalse(recorder.holdsFence(), "the fence loss must be remembered, not merely reported once");

        // And every subsequent write is refused up front rather than attempted and refused after the
        // effect it was recording already happened.
        var stopped = assertThrows(IllegalStateException.class,
                () -> recorder.record(nodeStarted(key, UUID.randomUUID(), UUID.randomUUID()), List.of()));
        assertTrue(stopped.getMessage().contains("lost the fence"));
        recorder.close();
    }

    // ------------------------------------------------------------------ fixtures

    private static List<ExecutionTransition> nodeStarted(ExecutionKey key, UUID invocationId, UUID attemptId) {
        UUID traversalId = TRAVERSALS.get(key);
        return List.of(
                new ExecutionTransition.InvocationAdded(traversalId,
                        new NodeInvocation(invocationId, "work", null, NodeInvocationStatus.SCHEDULED)),
                new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                        NodeInvocationStatus.RUNNING),
                new ExecutionTransition.AttemptAdded(traversalId, invocationId,
                        new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED)),
                new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, attemptId,
                        NodeAttemptStatus.RUNNING));
    }

    private static final Map<ExecutionKey, UUID> TRAVERSALS = new java.util.concurrent.ConcurrentHashMap<>();

    private ExecutionKey newInstance() {
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        TRAVERSALS.put(key, traversalId);
        var accepted = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                Map.of(traversalId, new Traversal(traversalId, "start", TraversalStatus.ACCEPTED, Map.of())));
        StoredProcessInstance created = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(accepted, new GraphVersionPin("graph-v1")))
                .build()));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .build()));
        return key;
    }

    private long revisionOf(ExecutionKey key) {
        return await(store.load(key)).revision();
    }

    private NodeAttemptStatus attemptStatus(ExecutionKey key) {
        return await(store.load(key)).state().traversals().values().iterator().next()
                .invocations().values().iterator().next().attempts().getLast().status();
    }

    private <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        private MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
