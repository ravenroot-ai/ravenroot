package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.catalog.NodeRetryProperty;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import ai.ravenroot.core.recovery.ExecutionRecoveryService;
import ai.ravenroot.core.recovery.RecoveryDispatcher;
import ai.ravenroot.core.recovery.RecoveryOutcome;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retry decision survives a restart, across a real store and a genuine close and reopen.
 *
 * <h2>Why this uses SQLite and a second store object rather than a mock</h2>
 * <p>The claim being tested is that the decision is <em>durable</em>, and an in-memory double cannot
 * distinguish "committed" from "still in a field". Everything the first half of the test writes goes
 * through a real {@link SqliteExecutionStore} to a real file; the store is then closed, and the second
 * half opens a new store on the same path with no shared state. What the second half reads is
 * therefore what a restarted process reads.</p>
 *
 * <h2>What "cannot lose the decision" and "cannot duplicate an attempt" mean here, precisely</h2>
 * <p>Not losing it: after the reopen, the invocation still holds a {@code SCHEDULED} attempt at
 * ordinal two, and a recovery sweep claims and dispatches exactly that attempt. That is the whole of
 * the retry decision — the wait is not durable and is not claimed to be, so the sweep dispatches
 * immediately, which is correct: the crash already imposed a delay.</p>
 * <p>Not duplicating it: the sweep runs, and then runs again. The second sweep finds the attempt
 * {@code RUNNING} and does not append a third — and the assertion is on the attempt list, not on the
 * sweep's own report, because a sweep that decided correctly and wrote anyway would pass the weaker
 * check.</p>
 */
class OrchestrationRetryRestartRecoveryTest {

    private static final String TENANT = "tenant-a";
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final String GRAPH_VERSION = "v1";
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final long BOUND_MILLIS = 20_000;

    @Test
    @DisplayName("a retry committed before a restart is found SCHEDULED after it, dispatched once, never twice")
    void aCommittedRetrySurvivesACloseAndReopenAndIsDispatchedExactlyOnce(@TempDir Path dir) throws Exception {
        Path database = dir.resolve("retry-restart.db");
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        var key = new ExecutionKey(TENANT, processInstanceId);
        var clock = new MovableClock(EPOCH);
        var entries = new AtomicInteger();

        // ---------------------------------------------------------------- before the restart
        //
        // Nothing below is in a try-with-resources, and that is the fixture rather than an oversight.
        // A crash closes nothing: the runner is not closed, so the traversal is never cancelled and
        // the process instance is never moved to FAILED; the recorder is not closed, so its lease is
        // left to expire on the store's clock, which is what ADR 0010 section 13.1 calls the crash
        // path. Tidying either one would produce an ORDERLY shutdown, and an orderly shutdown is a
        // strictly easier case: it ends the traversal, after which the aggregate refuses every
        // further attempt transition and recovery would have nothing to recover. The abandoned
        // objects are collected with the test; the backoff's own virtual thread is a daemon and, when
        // it eventually wakes, finds the store below already closed and writes nothing.
        var store = new SqliteExecutionStore(database, clock);
        var engine = new JoinTestEngine();
        var firstAttemptFailed = new CountDownLatch(1);
        var behaviors = new BehaviorRegistry().register("work", message -> {
            entries.incrementAndGet();
            firstAttemptFailed.countDown();
            return CompletableFuture.failedFuture(new IllegalStateException("transient"));
        });
        var monitor = new ExecutionMonitor();
        var joins = new InMemoryJoinStore();
        // Ten minutes, so the retry cannot possibly be delivered by this process. Whatever the
        // reopened store shows was put there by the commit and by nothing else.
        var manager = GraphManager.from(retryingGraph(Duration.ofMinutes(10)));
        var runner = new GraphRunner(manager, engine, behaviors, monitor,
                ExecutionIdentitySource.randomUuids(), joins, Clock.systemUTC());
        long revision = createRunningInstance(store, key, traversalId, manager.start().id());
        var recorder = ExecutionRecorder.open(store, key, "worker-before", TTL, revision);
        runner.execute(TestIdentities.of(TENANT, "alice"), processInstanceId, traversalId, "payload",
                GRAPH_VERSION, null, null, recorder);
        assertTrue(firstAttemptFailed.await(BOUND_MILLIS, TimeUnit.MILLISECONDS));
        awaitAttemptCount(store, key, 2);
        store.close();
        engine.close();
        assertEquals(1, entries.get(), "the retry must not have been delivered before the restart");

        // ---------------------------------------------------------------- after the restart
        try (var reopened = new SqliteExecutionStore(database, clock)) {
            NodeInvocation invocation = workInvocation(reopened, key);
            assertEquals(NodeInvocationStatus.RUNNING, invocation.status(),
                    "the visit was still in progress when the process stopped, and a restart must "
                            + "not find it closed");
            List<NodeAttempt> attempts = invocation.attempts();
            assertEquals(2, attempts.size(), "the retry decision must have survived the restart");
            assertEquals(NodeAttemptStatus.FAILED, attempts.get(0).status());
            assertEquals(NodeAttemptStatus.SCHEDULED, attempts.get(1).status());
            assertEquals(2, attempts.get(1).ordinal());
            UUID retryAttemptId = attempts.get(1).attemptId();

            // The previous worker's lease is gone with it; a real crash would leave it to expire, so
            // the clock is moved past the TTL rather than the lease being tidied away by the test.
            clock.advance(TTL.plusSeconds(1));

            var dispatched = new ArrayList<PendingWork.AttemptDispatch>();
            var recovery = new ExecutionRecoveryService(reopened, List.of(TENANT), "worker-after", 10, TTL,
                    null, new RecordingDispatcher(dispatched));

            List<RecoveryOutcome> first = recovery.sweepOnce();
            assertEquals(1, first.size(), "exactly the scheduled retry is outstanding");
            var outcome = assertInstanceOf(RecoveryOutcome.Dispatched.class, first.get(0),
                    "a SCHEDULED attempt is provably effect-free, so recovery dispatches rather than "
                            + "parking: losing that distinction is what would strand every retry");
            assertEquals(retryAttemptId, outcome.attemptId());
            assertEquals(1, dispatched.size());
            assertEquals(2, dispatched.get(0).attemptOrdinal(),
                    "the recovered work carries the retry's ordinal, so a dispatcher knows it is "
                            + "resuming a retry and not starting a visit");
            assertEquals(retryAttemptId.toString(), dispatched.get(0).attemptId().toString());

            // A second sweep in the same reopened process: the attempt is now RUNNING, and nothing
            // may append a third attempt behind the two that are committed.
            recovery.sweepOnce();
            assertEquals(2, workInvocation(reopened, key).attempts().size(),
                    "a repeated sweep must never turn one committed retry into two attempts");
        }
    }

    /**
     * A clock the test moves, so a lease expires because time passed rather than because the test
     * tidied it away.
     *
     * <p>Private to this class rather than shared: the two other copies in this module are private to
     * theirs, and the shape is four lines. Extracting it would be a shared test fixture whose only
     * purpose is to save four lines in four files.</p>
     */
    private static final class MovableClock extends Clock {
        private Instant now;

        private MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Captures what recovery handed it, so the test asserts on the dispatch rather than on a log. */
    private record RecordingDispatcher(List<PendingWork.AttemptDispatch> dispatched)
            implements RecoveryDispatcher {
        @Override
        public boolean canDispatch(PendingWork item) {
            return item instanceof PendingWork.AttemptDispatch;
        }

        @Override
        public void dispatch(PendingWork item, String idempotencyKey) {
            dispatched.add((PendingWork.AttemptDispatch) item);
        }
    }

    private static GraphDefinition retryingGraph(Duration backoff) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("work", NodeKind.BEHAVIOR, "work", Map.of(
                        NodeRetryProperty.MAX_ATTEMPTS, "3",
                        NodeRetryProperty.INITIAL_BACKOFF, String.valueOf(backoff.toMillis()),
                        NodeRetryProperty.BACKOFF_MULTIPLIER, "1.0",
                        NodeRetryProperty.MAX_BACKOFF, String.valueOf(backoff.toMillis()),
                        NodeRetryProperty.RETRY_ON, IllegalStateException.class.getName())),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "work"),
                GraphEdge.to("work", "end")));
    }

    private static long createRunningInstance(ExecutionStore store, ExecutionKey key, UUID traversalId,
                                              String startNodeId) {
        var traversal = new Traversal(traversalId, startNodeId, TraversalStatus.ACCEPTED, Map.of());
        var accepted = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                Map.of(traversalId, traversal));
        var created = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(accepted, new GraphVersionPin(GRAPH_VERSION)))
                .build()));
        return await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .build())).revision();
    }

    private static void awaitAttemptCount(ExecutionStore store, ExecutionKey key, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMillis(BOUND_MILLIS).toNanos();
        while (System.nanoTime() < deadline) {
            NodeInvocation invocation = workInvocation(store, key);
            if (invocation != null && invocation.attempts().size() == expected) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("the retry was never committed within " + BOUND_MILLIS + "ms");
    }

    private static NodeInvocation workInvocation(ExecutionStore store, ExecutionKey key) {
        for (Traversal traversal : await(store.load(key)).state().traversals().values()) {
            for (NodeInvocation invocation : traversal.invocations().values()) {
                if ("work".equals(invocation.nodeId())) {
                    return invocation;
                }
            }
        }
        return null;
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
