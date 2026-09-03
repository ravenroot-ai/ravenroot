package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
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
import ai.ravenroot.api.execution.ConnectorRetryReport;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.RetryClassified;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.Retryability;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Orchestration-level node retries, end to end through the runner and the store.
 *
 * <h2>What each case is pinning, and against which mutant</h2>
 * <p>{@link #aRetriedNodeSucceedsAndLeavesTwoDistinctDurableAttempts()} is red under a mutant that
 * retries in place — it asserts two attempts with two ordinals and two identities, which is the whole
 * of "retries are visible as distinct durable attempts" and is exactly what the persisted model could
 * not express before. {@link #anExhaustedPolicyStopsAtTheDeclaredBoundAndFailsTheVisit()} is red under
 * a mutant that drops the bound: the attempt count is asserted, not merely that it eventually failed.
 * {@link #aNonRetryableFailureIsNotRetriedEvenWithBudgetRemaining()} is the converse and is red under
 * a mutant that retries any failure, which is the dangerous direction — it is the case that would
 * silently repeat an unclassified effect.</p>
 *
 * <p>{@link #cancellingDuringTheBackoffStopsFurtherAttemptsPromptly()} is red under a mutant that
 * waits out the backoff before noticing cancellation: it bounds the time <em>and</em> asserts the
 * node was never entered a second time, because a cancel that merely fails the traversal after the
 * retry has already run has not stopped anything.
 * {@link #theCommittedRetryIsClaimableWhileTheInvocationStaysRunning()} is the crash-safety cell: it
 * looks at the store from outside, mid-backoff, and asserts what a recovering worker would find.</p>
 *
 * <p>{@link #eventsDistinguishAnInitialAttemptARetryAndAConnectorsInternalRetries()} is the
 * observability cell. It is one test rather than three because the three facts are only meaningful
 * against each other: what must be true is that a reader can tell them apart on the same stream.</p>
 */
class OrchestrationRetryTest {

    private static final String TENANT = "tenant-a";
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final String GRAPH_VERSION = "v1";

    /** On a regression a backoff test does not fail slowly, it hangs. */
    private static final long BOUND_MILLIS = 20_000;

    private final JoinTestEngine engine = new JoinTestEngine();
    private final ExecutionMonitor monitor = new ExecutionMonitor();
    private final List<ExecutionEvent> events = new CopyOnWriteArrayList<>();

    OrchestrationRetryTest() {
        monitor.subscribe(events::add);
    }

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    // ------------------------------------------------------------------ success after retry

    @Test
    @DisplayName("a node that fails once and then succeeds leaves two attempts, ordinals 1 and 2, with distinct ids")
    void aRetriedNodeSucceedsAndLeavesTwoDistinctDurableAttempts() throws Exception {
        var store = new InMemoryExecutionStore();
        var entries = new AtomicInteger();
        var behaviors = new BehaviorRegistry().register("work", message ->
                entries.incrementAndGet() == 1
                        ? CompletableFuture.failedFuture(new RetryableBlip())
                        : CompletableFuture.completedFuture(NodeResult.continueWith("second time lucky")));

        ExecutionKey key = run(retryingGraph(3, Duration.ofMillis(1)), behaviors, store, false);

        assertEquals(2, entries.get(), "the node must actually have been entered twice");
        List<NodeAttempt> attempts = attemptsOfWorkNode(store, key);
        assertEquals(2, attempts.size(),
                "a retry must be a new attempt on the same invocation, not a re-run of attempt one");
        assertEquals(1, attempts.get(0).ordinal());
        assertEquals(NodeAttemptStatus.FAILED, attempts.get(0).status());
        assertEquals(2, attempts.get(1).ordinal(), "the ordinal must increase monotonically");
        assertEquals(NodeAttemptStatus.COMPLETED, attempts.get(1).status());
        assertNotEquals(attempts.get(0).attemptId(), attempts.get(1).attemptId(),
                "a retry is a new effect identity: sharing the attempt id would make the second "
                        + "delivery deduplicate against the first under the attempt-scoped key");
        assertEquals(NodeInvocationStatus.COMPLETED, workInvocation(store, key).status());
    }

    // ------------------------------------------------------------------ exhausted retries

    @Test
    @DisplayName("a policy of three attempts stops after three, and the visit fails")
    void anExhaustedPolicyStopsAtTheDeclaredBoundAndFailsTheVisit() throws Exception {
        var store = new InMemoryExecutionStore();
        var entries = new AtomicInteger();
        var behaviors = new BehaviorRegistry().register("work", message -> {
            entries.incrementAndGet();
            return CompletableFuture.failedFuture(new RetryableBlip());
        });

        ExecutionKey key = run(retryingGraph(3, Duration.ofMillis(1)), behaviors, store, true);

        assertEquals(3, entries.get(), "maxAttempts counts attempts, so three means three entries");
        List<NodeAttempt> attempts = attemptsOfWorkNode(store, key);
        assertEquals(3, attempts.size());
        assertEquals(List.of(1, 2, 3), attempts.stream().map(NodeAttempt::ordinal).toList());
        assertTrue(attempts.stream().allMatch(attempt -> attempt.status() == NodeAttemptStatus.FAILED));
        assertEquals(NodeInvocationStatus.FAILED, workInvocation(store, key).status(),
                "an exhausted retry ends the visit exactly as an unretried failure does");

        assertEquals(2, countOf(ExecutionEventType.NODE_RETRY_SCHEDULED),
                "one retry event per retry scheduled, so two for three attempts");
        assertEquals(1, countOf(ExecutionEventType.NODE_FAILED),
                "only the last attempt settles as NODE_FAILED; the intermediate ones are retries and "
                        + "must not inflate a deployment's failure count");
    }

    // ------------------------------------------------------------------ classification

    @Test
    @DisplayName("a failure the node never declared retryable is not retried, however much budget remains")
    void aNonRetryableFailureIsNotRetriedEvenWithBudgetRemaining() throws Exception {
        var store = new InMemoryExecutionStore();
        var entries = new AtomicInteger();
        var behaviors = new BehaviorRegistry().register("work", message -> {
            entries.incrementAndGet();
            // Not RetryableBlip, and not named in retry.retryOn: the fail-closed default applies.
            return CompletableFuture.failedFuture(new IllegalStateException("structurally broken"));
        });

        ExecutionKey key = run(retryingGraph(5, Duration.ofMillis(1)), behaviors, store, true);

        assertEquals(1, entries.get(),
                "an unclassified failure must not be repeated: this is the case where retrying would "
                        + "silently duplicate an effect nobody authorised");
        assertEquals(1, attemptsOfWorkNode(store, key).size());
        assertEquals(0, countOf(ExecutionEventType.NODE_RETRY_SCHEDULED));
        assertEquals(1, countOf(ExecutionEventType.NODE_FAILED));
    }

    @Test
    @DisplayName("a failure declaring INDETERMINATE stops, because the effect may already have landed")
    void anIndeterminateFailureStopsRatherThanRepeatingAPossibleEffect() throws Exception {
        var store = new InMemoryExecutionStore();
        var entries = new AtomicInteger();
        var behaviors = new BehaviorRegistry().register("work", message -> {
            entries.incrementAndGet();
            return CompletableFuture.failedFuture(new ClassifiedFailure(Retryability.INDETERMINATE));
        });

        run(retryingGraph(5, Duration.ofMillis(1)), behaviors, store, true);

        assertEquals(1, entries.get(),
                "ADR 0022's rule holds at the policy boundary too: an effect of unknown outcome is "
                        + "never repeated automatically");
    }

    // ------------------------------------------------------------------ cancellation during backoff

    @Test
    @DisplayName("cancelling while a retry is waiting out its backoff stops further attempts promptly")
    void cancellingDuringTheBackoffStopsFurtherAttemptsPromptly() throws Exception {
        var store = new InMemoryExecutionStore();
        var entries = new AtomicInteger();
        var firstAttemptFailed = new CountDownLatch(1);
        var behaviors = new BehaviorRegistry().register("work", message -> {
            entries.incrementAndGet();
            firstAttemptFailed.countDown();
            return CompletableFuture.failedFuture(new RetryableBlip());
        });

        var security = TestIdentities.of(TENANT, "alice");
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        var key = new ExecutionKey(TENANT, processInstanceId);
        // Long enough that a backoff which is not interrupted cannot possibly finish inside the
        // bound below: the test then fails by timing out rather than by passing slowly.
        var joins = new InMemoryJoinStore();
        try (var manager = GraphManager.from(retryingGraph(5, Duration.ofMinutes(10)));
             var runner = new GraphRunner(manager, engine, behaviors, monitor,
                     ExecutionIdentitySource.randomUuids(), joins, Clock.systemUTC())) {
            long revision = createRunningInstance(store, key, traversalId, manager.start().id());
            try (var recorder = ExecutionRecorder.open(store, key, "test-worker", TTL, revision)) {
                var execution = runner.execute(security, processInstanceId, traversalId, "payload",
                        GRAPH_VERSION, null, null, recorder).toCompletableFuture();
                assertTrue(firstAttemptFailed.await(BOUND_MILLIS, TimeUnit.MILLISECONDS));
                // The retry is committed and the backoff is in progress. Nothing else will move it.
                assertTrue(runner.cancelTraversal(traversalId));
                assertThrows(ExecutionException.class,
                        () -> execution.get(BOUND_MILLIS, TimeUnit.MILLISECONDS),
                        "the traversal must end on the cancellation rather than outlive the backoff");
            }
        }

        assertEquals(1, entries.get(),
                "cancelling during a backoff must stop the attempt that was scheduled; running it "
                        + "anyway and failing afterwards is a cancel that reported success over work "
                        + "it did not prevent");
        // The decision stays durable, and truthfully so: it was made, and then the traversal was
        // cancelled before it could be acted on. Erasing it would hide that the retry was owed.
        List<NodeAttempt> attempts = attemptsOfWorkNode(store, key);
        assertEquals(2, attempts.size());
        assertEquals(NodeAttemptStatus.SCHEDULED, attempts.get(1).status());
    }

    @Test
    @DisplayName("pausing during a backoff holds the retry until the execution is resumed")
    void pausingDuringTheBackoffHoldsTheRetryUntilResumed() throws Exception {
        var store = new InMemoryExecutionStore();
        var entries = new AtomicInteger();
        var runnerRef = new java.util.concurrent.atomic.AtomicReference<GraphRunner>();
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        // The pause is installed from INSIDE the first attempt, before it fails, so the gate is
        // provably in place before the retry decision exists. Pausing from the test thread after the
        // commit would race the backoff, and a racy fixture here would report a green pause on a
        // build that was merely slower than the wait.
        var behaviors = new BehaviorRegistry().register("work", message -> {
            if (entries.incrementAndGet() == 1) {
                runnerRef.get().pauseTraversal(traversalId);
                return CompletableFuture.failedFuture(new RetryableBlip());
            }
            return CompletableFuture.completedFuture(NodeResult.continueWith("resumed"));
        });

        var security = TestIdentities.of(TENANT, "alice");
        var key = new ExecutionKey(TENANT, processInstanceId);
        var joins = new InMemoryJoinStore();
        // One millisecond, so a retry that ignored the gate would have run long before the assertion
        // below looks: the hold has to be real, not merely slow.
        try (var manager = GraphManager.from(retryingGraph(3, Duration.ofMillis(1)));
             var runner = new GraphRunner(manager, engine, behaviors, monitor,
                     ExecutionIdentitySource.randomUuids(), joins, Clock.systemUTC())) {
            runnerRef.set(runner);
            long revision = createRunningInstance(store, key, traversalId, manager.start().id());
            try (var recorder = ExecutionRecorder.open(store, key, "test-worker", TTL, revision)) {
                var execution = runner.execute(security, processInstanceId, traversalId, "payload",
                        GRAPH_VERSION, null, null, recorder).toCompletableFuture();
                awaitSecondAttemptCommitted(store, key);

                Thread.sleep(200);
                assertEquals(1, entries.get(),
                        "a retry dispatched into a paused execution would make the pause a lie: an "
                                + "operator who paused and then saw another attempt has no way to "
                                + "stop the node short of cancelling the whole execution");
                assertFalse(execution.isDone(), "the traversal is held, not finished");

                assertTrue(runner.resumeTraversal(traversalId));
                execution.get(BOUND_MILLIS, TimeUnit.MILLISECONDS);
                assertEquals(2, entries.get(), "resuming must release the held retry, not discard it");
            }
        }
    }

    // ------------------------------------------------------------- crash safety, from outside

    @Test
    @DisplayName("mid-backoff the store shows a claimable SCHEDULED retry under a still-RUNNING invocation")
    void theCommittedRetryIsClaimableWhileTheInvocationStaysRunning() throws Exception {
        var store = new InMemoryExecutionStore();
        var firstAttemptFailed = new CountDownLatch(1);
        var behaviors = new BehaviorRegistry().register("work", message -> {
            firstAttemptFailed.countDown();
            return CompletableFuture.failedFuture(new RetryableBlip());
        });

        var security = TestIdentities.of(TENANT, "alice");
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        var key = new ExecutionKey(TENANT, processInstanceId);
        var joins = new InMemoryJoinStore();
        try (var manager = GraphManager.from(retryingGraph(5, Duration.ofMinutes(10)));
             var runner = new GraphRunner(manager, engine, behaviors, monitor,
                     ExecutionIdentitySource.randomUuids(), joins, Clock.systemUTC())) {
            long revision = createRunningInstance(store, key, traversalId, manager.start().id());
            try (var recorder = ExecutionRecorder.open(store, key, "test-worker", TTL, revision)) {
                var execution = runner.execute(security, processInstanceId, traversalId, "payload",
                        GRAPH_VERSION, null, null, recorder).toCompletableFuture();
                assertTrue(firstAttemptFailed.await(BOUND_MILLIS, TimeUnit.MILLISECONDS));
                awaitSecondAttemptCommitted(store, key);

                NodeInvocation invocation = workInvocation(store, key);
                assertEquals(NodeInvocationStatus.RUNNING, invocation.status(),
                        "the visit is still in progress: marking it FAILED would make the aggregate "
                                + "refuse the retry this batch just appended");
                assertEquals(NodeAttemptStatus.SCHEDULED, invocation.attempts().get(1).status(),
                        "SCHEDULED is what a recovering worker reads as 'the effect provably never "
                                + "started', so this is the state a crash inside the backoff must leave");

                runner.cancelTraversal(traversalId);
                assertThrows(ExecutionException.class,
                        () -> execution.get(BOUND_MILLIS, TimeUnit.MILLISECONDS));
            }
        }

        // With the worker gone and its lease released, the retry is claimable exactly once. This is
        // the same read a recovery sweep performs after a restart.
        List<PendingWork> claimed = await(store.claimPendingWork(TENANT, "recovery-1", 10, TTL));
        assertEquals(1, claimed.size(), "exactly the retry, and nothing about the attempt that failed");
        var dispatch = (PendingWork.AttemptDispatch) claimed.get(0);
        assertEquals(2, dispatch.attemptOrdinal());
        store.close();
    }

    // ------------------------------------------------------------------ observability

    @Test
    @DisplayName("events separate an initial attempt, an orchestration retry, and a connector's own retries")
    void eventsDistinguishAnInitialAttemptARetryAndAConnectorsInternalRetries() throws Exception {
        var store = new InMemoryExecutionStore();
        var entries = new AtomicInteger();
        var behaviors = new BehaviorRegistry().register("work", message ->
                entries.incrementAndGet() == 1
                        // A connector that retried twice inside this one orchestration attempt and
                        // still failed. Those two retries produced no durable attempt of their own.
                        ? CompletableFuture.failedFuture(new ConnectorFailure(3))
                        : CompletableFuture.completedFuture(NodeResult.continueWith("ok")
                                .reportingConnectorAttempts(1)));

        run(retryingGraph(3, Duration.ofMillis(1)), behaviors, store, false);

        List<ExecutionEvent> starts = eventsOf(ExecutionEventType.NODE_STARTED, "work");
        assertEquals(2, starts.size());
        assertEquals(1, starts.get(0).attemptOrdinal(), "the initial attempt is ordinal one");
        assertEquals(2, starts.get(1).attemptOrdinal(),
                "the retry is ordinal two on the same invocation, so a reader can tell it from an "
                        + "initial attempt without correlating two events");

        ExecutionEvent retry = eventsOf(ExecutionEventType.NODE_RETRY_SCHEDULED, "work").get(0);
        assertEquals(1, retry.attemptOrdinal(),
                "the retry event names the attempt that FAILED, not the one being scheduled");
        assertEquals("retryable-no-effect", retry.publicReason(),
                "the classification travels as a bounded classifier, so a metric can be sliced by it");
        assertEquals(3, retry.connectorAttempts(),
                "the connector's own three attempts are reported on the attempt they happened inside, "
                        + "and never as orchestration attempts of their own");

        ExecutionEvent completed = eventsOf(ExecutionEventType.NODE_COMPLETED, "work").get(0);
        assertEquals(2, completed.attemptOrdinal());
        assertEquals(1, completed.connectorAttempts(),
                "a connector reporting one attempt is a measurement, and must not be collapsed into "
                        + "the silence of a node that reports nothing");

        assertEquals(ConnectorRetryReport.NOT_REPORTED,
                eventsOf(ExecutionEventType.NODE_STARTED, "start").get(0).connectorAttempts(),
                "a structural node reports nothing rather than claiming a single attempt");
    }

    // ------------------------------------------------------------------ no policy, no change

    @Test
    @DisplayName("a node that declares no policy behaves exactly as before: one attempt, one failure")
    void aNodeWithoutAPolicyIsUnchanged() throws Exception {
        var store = new InMemoryExecutionStore();
        var entries = new AtomicInteger();
        var behaviors = new BehaviorRegistry().register("work", message -> {
            entries.incrementAndGet();
            return CompletableFuture.failedFuture(new RetryableBlip());
        });

        ExecutionKey key = run(plainGraph(), behaviors, store, true);

        assertEquals(1, entries.get(),
                "the whole feature must be invisible to a graph that did not ask for it, even when "
                        + "the failure would have been classified retryable had a policy existed");
        assertEquals(1, attemptsOfWorkNode(store, key).size());
        assertEquals(0, countOf(ExecutionEventType.NODE_RETRY_SCHEDULED));
    }

    // ================================================================== fixtures

    /** A failure the graph below declares retryable by name. */
    private static final class RetryableBlip extends RuntimeException {
        RetryableBlip() {
            super("transient");
        }
    }

    /** A failure that states its own classification, the way a connector does. */
    private static final class ClassifiedFailure extends RuntimeException implements RetryClassified {
        private final Retryability retryability;

        ClassifiedFailure(Retryability retryability) {
            super("classified");
            this.retryability = retryability;
        }

        @Override
        public Retryability retryability() {
            return this.retryability;
        }
    }

    /** A connector failure that reports how many times it tried internally before giving up. */
    private static final class ConnectorFailure extends RuntimeException
            implements RetryClassified, ConnectorRetryReport {
        private final int attempts;

        ConnectorFailure(int attempts) {
            super("connector exhausted its own retries");
            this.attempts = attempts;
        }

        @Override
        public Retryability retryability() {
            return Retryability.RETRYABLE_NO_EFFECT;
        }

        @Override
        public int connectorAttempts() {
            return attempts;
        }
    }

    private static GraphDefinition retryingGraph(int maxAttempts, Duration backoff) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("work", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "work", Map.of(
                        NodeRetryProperty.MAX_ATTEMPTS, String.valueOf(maxAttempts),
                        NodeRetryProperty.INITIAL_BACKOFF, String.valueOf(backoff.toMillis()),
                        NodeRetryProperty.BACKOFF_MULTIPLIER, "1.0",
                        NodeRetryProperty.MAX_BACKOFF, String.valueOf(backoff.toMillis()),
                        NodeRetryProperty.RETRY_ON, RetryableBlip.class.getSimpleName())),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "work"),
                GraphEdge.to("work", "end")));
    }

    private static GraphDefinition plainGraph() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("work", "work"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "work"),
                GraphEdge.to("work", "end")));
    }

    private ExecutionKey run(GraphDefinition graph, BehaviorRegistry behaviors, ExecutionStore store,
                             boolean expectFailure) throws Exception {
        var security = TestIdentities.of(TENANT, "alice");
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        var key = new ExecutionKey(TENANT, processInstanceId);
        var joins = new InMemoryJoinStore();
        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, behaviors, monitor,
                     ExecutionIdentitySource.randomUuids(), joins, Clock.systemUTC())) {
            long revision = createRunningInstance(store, key, traversalId, manager.start().id());
            try (var recorder = ExecutionRecorder.open(store, key, "test-worker", TTL, revision)) {
                var execution = runner.execute(security, processInstanceId, traversalId, "payload",
                        GRAPH_VERSION, null, null, recorder).toCompletableFuture();
                if (expectFailure) {
                    assertThrows(ExecutionException.class,
                            () -> execution.get(BOUND_MILLIS, TimeUnit.MILLISECONDS));
                } else {
                    execution.get(BOUND_MILLIS, TimeUnit.MILLISECONDS);
                }
            }
        }
        return key;
    }

    /**
     * Spins until the retry batch has landed.
     *
     * <p>The behaviour's latch fires <em>inside</em> the node, before the runner has decided anything,
     * so reading the aggregate immediately after it would be a race with the write rather than an
     * observation of it. Bounded so a regression fails rather than hangs.
     */
    private static void awaitSecondAttemptCommitted(ExecutionStore store, ExecutionKey key)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMillis(BOUND_MILLIS).toNanos();
        while (System.nanoTime() < deadline) {
            if (workInvocation(store, key) != null && workInvocation(store, key).attempts().size() == 2) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("the retry was never committed within " + BOUND_MILLIS + "ms");
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

    private static List<NodeAttempt> attemptsOfWorkNode(ExecutionStore store, ExecutionKey key) {
        NodeInvocation invocation = workInvocation(store, key);
        return invocation == null ? List.of() : invocation.attempts();
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

    private List<ExecutionEvent> eventsOf(ExecutionEventType type, String nodeId) {
        return events.stream()
                .filter(event -> event.type() == type && nodeId.equals(event.nodeId()))
                .toList();
    }

    private long countOf(ExecutionEventType type) {
        return events.stream().filter(event -> event.type() == type).count();
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
