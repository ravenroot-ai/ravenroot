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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /**
     * A traversal that ends while a retry is in backoff must not deliver that retry.
     *
     * <h2>No crash anywhere in this story, and that is the point</h2>
     * <p>Two branches. {@code a} fails retryably and commits attempt two, then waits. {@code b} then
     * fails with nothing to route to, which ends the traversal. When {@code a}'s wait expires, its
     * {@code RUNNING} transition can no longer be recorded — the aggregate refuses every attempt
     * transition on a terminal traversal.</p>
     *
     * <p>Dispatching anyway is the defect this pins, and the harm is not "an unrecorded attempt". It
     * is a <strong>duplicated external effect</strong>: the node would run, while its attempt stayed
     * {@code SCHEDULED} in the store — and {@code SCHEDULED} is exactly what a recovery sweep reads
     * as "provably effect-free" and is entitled to dispatch. The two halves of that are each already
     * demonstrated elsewhere in this file and in {@code OrchestrationRetryRestartRecoveryTest}; this
     * test closes the gap between them.</p>
     *
     * <p>So the assertion is on the node's own entry count, not on the store: whether the attempt was
     * recorded is beside the point if the effect happened.</p>
     */
    @Test
    @DisplayName("a traversal that ends during a backoff never delivers the retry it had scheduled")
    void aRetryIsNotDeliveredIntoATraversalThatEndedWhileItWaited() throws Exception {
        var store = new InMemoryExecutionStore();
        var retryingEntries = new AtomicInteger();
        var siblingMayFail = new CountDownLatch(1);
        var behaviors = new BehaviorRegistry()
                .register("a", message -> {
                    retryingEntries.incrementAndGet();
                    return CompletableFuture.failedFuture(new RetryableBlip());
                })
                .register("b", message -> {
                    awaitLatch(siblingMayFail);
                    // No failure route and no retry policy on this node: the branch dies, and with it
                    // the traversal.
                    return CompletableFuture.failedFuture(new IllegalStateException("hard failure"));
                });

        var security = TestIdentities.of(TENANT, "alice");
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        var key = new ExecutionKey(TENANT, processInstanceId);
        var joins = new InMemoryJoinStore();
        // A second is far longer than the latch countdown plus one store write that separates the
        // commit from the traversal's end, so the ordering the test needs is not a coin flip.
        try (var manager = GraphManager.from(fanOutGraph(Duration.ofSeconds(1)));
             var runner = new GraphRunner(manager, engine, behaviors, monitor,
                     ExecutionIdentitySource.randomUuids(), joins, Clock.systemUTC())) {
            long revision = createRunningInstance(store, key, traversalId, manager.start().id());
            try (var recorder = ExecutionRecorder.open(store, key, "test-worker", TTL, revision)) {
                var execution = runner.execute(security, processInstanceId, traversalId, "payload",
                        GRAPH_VERSION, null, null, recorder).toCompletableFuture();
                awaitAttemptCount(store, key, "a", 2);
                assertEquals(1, retryingEntries.get(), "the fixture must retry exactly once so far");

                siblingMayFail.countDown();
                assertThrows(ExecutionException.class,
                        () -> execution.get(BOUND_MILLIS, TimeUnit.MILLISECONDS),
                        "the sibling's failure must end the traversal");

                // Well past the backoff, with the recorder still open -- so a delivery would be
                // refused by the terminal traversal and by nothing else.
                Thread.sleep(2_000);
                assertEquals(1, retryingEntries.get(),
                        "the retry was delivered into a traversal that had already ended: its "
                                + "RUNNING transition is refused, so the attempt stays SCHEDULED "
                                + "while the effect runs -- and a recovery sweep reads SCHEDULED as "
                                + "'provably never started' and runs it a second time");
                assertEquals(NodeAttemptStatus.SCHEDULED,
                        invocationOf(store, key, "a").attempts().get(1).status(),
                        "the fixture is only meaningful while the attempt is one a sweep would claim");
            }
        }
    }

    @Test
    @DisplayName("closing the runner during a backoff stops the retry promptly rather than after the wait")
    void graphShutdownDuringTheBackoffStopsTheRetryPromptly() throws Exception {
        var store = new InMemoryExecutionStore();
        var entries = new AtomicInteger();
        var behaviors = new BehaviorRegistry().register("work", message -> {
            entries.incrementAndGet();
            return CompletableFuture.failedFuture(new RetryableBlip());
        });

        var security = TestIdentities.of(TENANT, "alice");
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        var key = new ExecutionKey(TENANT, processInstanceId);
        var joins = new InMemoryJoinStore();
        // Ten minutes. Any outcome at all inside the bound below is therefore proof of promptness
        // rather than of patience -- a shutdown that merely let the timer elapse could not finish.
        var manager = GraphManager.from(retryingGraph(5, Duration.ofMinutes(10)));
        var runner = new GraphRunner(manager, engine, behaviors, monitor,
                ExecutionIdentitySource.randomUuids(), joins, Clock.systemUTC());
        long revision = createRunningInstance(store, key, traversalId, manager.start().id());
        try (var recorder = ExecutionRecorder.open(store, key, "test-worker", TTL, revision)) {
            var execution = runner.execute(security, processInstanceId, traversalId, "payload",
                    GRAPH_VERSION, null, null, recorder).toCompletableFuture();
            awaitSecondAttemptCommitted(store, key);

            long startedAt = System.nanoTime();
            runner.close();
            assertThrows(ExecutionException.class,
                    () -> execution.get(BOUND_MILLIS, TimeUnit.MILLISECONDS),
                    "shutdown must end the traversal instead of leaving it asleep on a timer");
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
            assertTrue(elapsed.compareTo(Duration.ofMinutes(1)) < 0,
                    "shutdown waited on the backoff rather than ending it: " + elapsed);
            assertEquals(1, entries.get(), "no further attempt may be dispatched into a closing runner");
        } finally {
            manager.close();
        }
    }

    @Test
    @DisplayName("a join timeout that ends the traversal during a backoff stops the retry at once")
    void aTraversalTimeoutDuringTheBackoffStopsTheRetry() throws Exception {
        var store = new InMemoryExecutionStore();
        var retryingEntries = new AtomicInteger();
        var behaviors = new BehaviorRegistry()
                .register("b0", message -> {
                    retryingEntries.incrementAndGet();
                    return CompletableFuture.failedFuture(new RetryableBlip());
                })
                .register("b1", message -> CompletableFuture.completedFuture(
                        NodeResult.continueWith("arrived")));

        var security = TestIdentities.of(TENANT, "alice");
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        var key = new ExecutionKey(TENANT, processInstanceId);
        var joins = new InMemoryJoinStore();
        // The join's deadline is fired by hand rather than waited for, exactly as the join suite
        // drives every other timeout: the point of the test is the ORDER of two events, and a
        // wall-clock race between them would prove whichever the machine happened to run first.
        // The backoff is half an hour, so nothing here can pass by outlasting it.
        try (var manager = GraphManager.from(timedJoinGraph("PT30S", Duration.ofMinutes(30)));
             var runner = new GraphRunner(manager, engine, behaviors, monitor,
                     ExecutionIdentitySource.randomUuids(), joins, Clock.systemUTC())) {
            long revision = createRunningInstance(store, key, traversalId, manager.start().id());
            try (var recorder = ExecutionRecorder.open(store, key, "test-worker", TTL, revision)) {
                var execution = runner.execute(security, processInstanceId, traversalId, "payload",
                        GRAPH_VERSION, null, null, recorder).toCompletableFuture();
                awaitAttemptCount(store, key, "b0", 2);
                assertEquals(1, retryingEntries.get(), "b0 is in backoff, not running");

                assertEquals(1, engine.manualScheduler().fireAll(),
                        "exactly one join timeout was scheduled, and this is it");

                var thrown = assertThrows(ExecutionException.class,
                        () -> execution.get(BOUND_MILLIS, TimeUnit.MILLISECONDS),
                        "the join's deadline passed with b0 still outstanding, so the traversal fails");
                assertInstanceOf(JoinFailureException.class, thrown.getCause());

                // The wait was ENDED, not merely destined to be refused when it expired half an hour
                // from now. Without that the traversal would be reported failed to its caller while a
                // thread of it was still scheduled to try the node again.
                assertEquals(0, runner.pendingBackoffCount(traversalId),
                        "a traversal that has ended must hold no retry backoff");
                assertEquals(1, retryingEntries.get(), "no attempt may be dispatched after the timeout");
                assertEquals(NodeAttemptStatus.SCHEDULED,
                        invocationOf(store, key, "b0").attempts().get(1).status(),
                        "the decision stays durable and truthful: it was made, and then the traversal "
                                + "ended before it could be acted on");
            }
        }
    }

    /**
     * The guard itself: the {@code RUNNING} commit refuses, and the dispatch must obey the refusal.
     *
     * <h2>Why this fixture goes to such trouble to make the backoff registry useless</h2>
     * <p>A traversal that ends now cancels every backoff it holds, so the ordinary path to this harm
     * is closed one step earlier — which is why
     * {@link #aRetryIsNotDeliveredIntoATraversalThatEndedWhileItWaited()} stays green even with the
     * guard removed. That leaves the guard itself untested, and it is not redundant: the branch can
     * be <em>past</em> the wait and inside the pause gate or the admission queue when the traversal
     * ends, where there is no registered wait to cancel. This test puts it exactly there.</p>
     *
     * <p>The backoff is zero, so nothing is ever registered — {@link GraphRunner#pendingBackoffCount}
     * is asserted to be zero, which is the fixture's own proof that the cancellation path could not
     * have produced the result. The branch is instead held on a pause gate while a sibling ends the
     * traversal, and is then released into a traversal that is already terminal.</p>
     */
    @Test
    @DisplayName("a retry released into an already-ended traversal is refused by the RUNNING commit")
    void aRefusedRunningCommitAbortsTheDispatchRatherThanBeingDiscarded() throws Exception {
        var store = new InMemoryExecutionStore();
        var retryingEntries = new AtomicInteger();
        var runnerRef = new java.util.concurrent.atomic.AtomicReference<GraphRunner>();
        var siblingRunning = new CountDownLatch(1);
        var siblingMayFail = new CountDownLatch(1);
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        var behaviors = new BehaviorRegistry()
                .register("a", message -> {
                    if (retryingEntries.incrementAndGet() == 1) {
                        // Installed only once the sibling is provably past its own gate check, so the
                        // pause holds the retry and nothing else.
                        awaitLatch(siblingRunning);
                        runnerRef.get().pauseTraversal(traversalId);
                        return CompletableFuture.failedFuture(new RetryableBlip());
                    }
                    return CompletableFuture.completedFuture(NodeResult.continueWith("ran again"));
                })
                .register("b", message -> {
                    siblingRunning.countDown();
                    awaitLatch(siblingMayFail);
                    return CompletableFuture.failedFuture(new IllegalStateException("hard failure"));
                });

        var security = TestIdentities.of(TENANT, "alice");
        var key = new ExecutionKey(TENANT, processInstanceId);
        var joins = new InMemoryJoinStore();
        try (var manager = GraphManager.from(fanOutGraph(Duration.ZERO));
             var runner = new GraphRunner(manager, engine, behaviors, monitor,
                     ExecutionIdentitySource.randomUuids(), joins, Clock.systemUTC())) {
            runnerRef.set(runner);
            long revision = createRunningInstance(store, key, traversalId, manager.start().id());
            try (var recorder = ExecutionRecorder.open(store, key, "test-worker", TTL, revision)) {
                var execution = runner.execute(security, processInstanceId, traversalId, "payload",
                        GRAPH_VERSION, null, null, recorder).toCompletableFuture();
                awaitAttemptCount(store, key, "a", 2);
                assertEquals(0, runner.pendingBackoffCount(traversalId),
                        "a zero backoff registers no wait, so nothing here can be rescued by "
                                + "cancelling one -- which is the whole point of this fixture");

                siblingMayFail.countDown();
                assertThrows(ExecutionException.class,
                        () -> execution.get(BOUND_MILLIS, TimeUnit.MILLISECONDS));

                // Released into a traversal that has already ended. The commit refuses; the dispatch
                // must refuse with it.
                // The gate is released by the traversal's own teardown, so the parked retry has
                // already been handed a thread by the time this returns; resumeTraversal is called
                // only to prove that, and its answer is not the assertion.
                runner.resumeTraversal(traversalId);
                Thread.sleep(500);
                assertEquals(1, retryingEntries.get(),
                        "the RUNNING commit was refused and the node was dispatched anyway: the "
                                + "effect runs while the attempt stays SCHEDULED, and a recovery "
                                + "sweep reads SCHEDULED as 'provably never started' and runs it "
                                + "a second time");
                assertEquals(0, runner.admissionGateCount(),
                        "a traversal that has ended must leave no admission gate behind. This is the "
                                + "end-to-end statement of the property; which of the two layered "
                                + "refusals produces it depends on the order of two lines in "
                                + "release(), so the mechanism itself is pinned by "
                                + "TraversalAdmissionRegistryTest rather than here");
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

    @Test
    @DisplayName("the failure that caused a retry survives on the retry event, for both readers")
    void aRetriedAttemptsFailureIsStillRecorded() throws Exception {
        var store = new InMemoryExecutionStore();
        var entries = new AtomicInteger();
        var behaviors = new BehaviorRegistry().register("work", message ->
                entries.incrementAndGet() == 1
                        ? CompletableFuture.failedFuture(new RetryableBlip())
                        : CompletableFuture.completedFuture(NodeResult.continueWith("ok")));

        run(retryingGraph(3, Duration.ofMillis(1)), behaviors, store, false);

        ExecutionEvent retry = eventsOf(ExecutionEventType.NODE_RETRY_SCHEDULED, "work").get(0);
        assertTrue(retry.detail().contains("RetryableBlip"),
                "the failure's class must be on the event: NODE_RETRY_SCHEDULED replaces NODE_FAILED "
                        + "for this attempt, so it is the only record the attempt gets. Without it a "
                        + "node that fails twice and then succeeds leaves no trace of what went "
                        + "wrong, and the run looks untroubled. Got: " + retry.detail());
        assertTrue(retry.detail().contains("transient"),
                "the failure's message too, exactly as NODE_FAILED carries it: " + retry.detail());
        assertTrue(retry.detail().startsWith("retrying as attempt 2 after"),
                "the retry facts lead, so a long exception message cannot push them past the "
                        + "detail bound: " + retry.detail());
        assertNotNull(retry.authorMessage(),
                "the author diagnostics view and the SSE frame read authorMessage, and it is nulled "
                        + "for every type outside the failure set -- so omitting this type there "
                        + "would delete the diagnostic from the surfaces an operator actually reads");
        assertTrue(retry.authorMessage().value().contains("transient"),
                "got: " + retry.authorMessage().value());
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

    /**
     * Two branches into a join that needs both, where {@code b0} retries and can never arrive in time.
     *
     * @param joinTimeout ISO-8601 deadline the join waits for its quorum before failing the traversal
     * @param backoff     {@code b0}'s retry wait, deliberately far longer than {@code joinTimeout}
     */
    private static GraphDefinition timedJoinGraph(String joinTimeout, Duration backoff) {
        var joinProperties = new java.util.LinkedHashMap<String, Object>();
        joinProperties.put(JoinSpec.QUORUM_PROPERTY, "2");
        joinProperties.put(JoinSpec.TIMEOUT_PROPERTY, joinTimeout);
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("b0", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "b0", Map.of(
                        NodeRetryProperty.MAX_ATTEMPTS, "3",
                        NodeRetryProperty.INITIAL_BACKOFF, String.valueOf(backoff.toMillis()),
                        NodeRetryProperty.BACKOFF_MULTIPLIER, "1.0",
                        NodeRetryProperty.MAX_BACKOFF, String.valueOf(backoff.toMillis()),
                        NodeRetryProperty.RETRY_ON, RetryableBlip.class.getSimpleName())),
                GraphNode.behavior("b1", "b1"),
                new GraphNode("join", ai.ravenroot.core.graph.NodeKind.PASSTHROUGH, null, joinProperties),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "b0"),
                GraphEdge.to("start", "b1"),
                GraphEdge.to("b0", "join"),
                GraphEdge.to("b1", "join"),
                GraphEdge.to("join", "end")));
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
        awaitAttemptCount(store, key, "work", 2);
    }

    private static void awaitAttemptCount(ExecutionStore store, ExecutionKey key, String nodeId,
                                          int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMillis(BOUND_MILLIS).toNanos();
        while (System.nanoTime() < deadline) {
            NodeInvocation invocation = invocationOf(store, key, nodeId);
            if (invocation != null && invocation.attempts().size() == expected) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("node '" + nodeId + "' never reached " + expected
                + " attempts within " + BOUND_MILLIS + "ms");
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
        return invocationOf(store, key, "work");
    }

    private static NodeInvocation invocationOf(ExecutionStore store, ExecutionKey key, String nodeId) {
        for (Traversal traversal : await(store.load(key)).state().traversals().values()) {
            for (NodeInvocation invocation : traversal.invocations().values()) {
                if (nodeId.equals(invocation.nodeId())) {
                    return invocation;
                }
            }
        }
        return null;
    }

    /** Blocks a node's own thread on a latch, converting the interrupt into a node failure. */
    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(BOUND_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("the fixture's latch never opened");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /**
     * Two branches from one start: {@code a} retries, {@code b} kills the traversal.
     *
     * <p>{@code b} has no retry policy and no failure route, so its failure ends the traversal rather
     * than being routed anywhere — which is precisely the condition {@code a}'s pending retry then
     * meets.</p>
     */
    private static GraphDefinition fanOutGraph(Duration backoff) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("a", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "a", Map.of(
                        NodeRetryProperty.MAX_ATTEMPTS, "3",
                        NodeRetryProperty.INITIAL_BACKOFF, String.valueOf(backoff.toMillis()),
                        NodeRetryProperty.BACKOFF_MULTIPLIER, "1.0",
                        NodeRetryProperty.MAX_BACKOFF, String.valueOf(backoff.toMillis()),
                        NodeRetryProperty.RETRY_ON, RetryableBlip.class.getSimpleName())),
                GraphNode.behavior("b", "b"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "a"),
                GraphEdge.to("start", "b"),
                GraphEdge.to("a", "end"),
                GraphEdge.to("b", "end")));
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
