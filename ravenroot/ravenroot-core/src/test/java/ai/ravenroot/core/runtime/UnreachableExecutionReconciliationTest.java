package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionTerminationReason;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recovery action for the verdict {@link UnreachableExecutionCriterionTest} establishes.
 *
 * <h2>Why this is a separate class</h2>
 * <p>The criterion answers "can this traversal still reach an outcome"; this answers "what does the
 * runtime do about one that cannot". Kept apart so that widening either is never silently a change
 * to the other: a test that established the verdict and asserted the recovery in one breath would
 * pass just as happily if the recovery started firing on a condition the criterion never named.</p>
 *
 * <h2>The fixture, and why no test here sleeps for an outcome</h2>
 * <p>The same construction the criterion test uses, unchanged: a {@link StalledJoinStore} swallows
 * the second {@code compareAndSet} — the arrival that would satisfy an {@code all} quorum of two —
 * on a join with no configured deadline. That leaves a branch parked with no worker running and no
 * timer armed, which is the unreachable condition, and it leaves it there permanently rather than
 * for a window a test would have to race. Every wait below is either a bounded setup barrier the
 * store itself signals, or the settlement of a stage that reconciliation has already been told to
 * settle; no test waits out a duration and no test infers a verdict from elapsed time.</p>
 */
class UnreachableExecutionReconciliationTest {

    private final JoinTestEngine engine = new JoinTestEngine();
    private final ExecutionMonitor monitor = new ExecutionMonitor();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    /**
     * The whole recovery, end to end: a traversal that can never settle is ended, its termination
     * carries the reason that says why, and the admission capacity it was holding comes back.
     *
     * <p>The three are asserted together deliberately. Ending the traversal without a distinguishing
     * reason produces an incident report that sends an operator hunting for a failing node that does
     * not exist; ending it without returning the capacity leaves the defect this issue is about
     * exactly where it was, with a tidier log.</p>
     */
    @Test
    void reconciliationEndsAnUnreachableExecutionWithAStableReasonAndReturnsItsCapacity() throws Exception {
        var store = new StalledJoinStore(new InMemoryJoinStore(), 2);
        var graph = JoinMiniGraphs.fanIn(2);
        UUID traversalId = UUID.randomUUID();
        var b1Gate = new CompletableFuture<NodeResult>();

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, branchesWithGatedB1(b1Gate), monitor,
                     ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store,
                     java.time.Clock.systemUTC())) {
            var execution = runner.execute(TestIdentities.TENANT_A, traversalId, "in", "v1")
                    .toCompletableFuture();

            awaitParkedBranchCount(runner, 1);
            b1Gate.complete(NodeResult.continueWith("from-b1"));
            assertTrue(store.awaitStall(5_000),
                    "b1's arrival must have reached the store for the condition to be built");

            assertFalse(execution.isDone(),
                    "the traversal must be open and unable to settle before reconciliation runs");
            assertEquals(Set.of(traversalId), runner.unreachableTraversalIds(),
                    "the criterion must name this traversal, or this test is not exercising the "
                            + "condition it claims to");
            assertTrue(runner.admissionGateCount() > 0,
                    "the stuck traversal is still holding admission capacity -- that retention is the "
                            + "defect reconciliation exists to end");

            assertEquals(Set.of(traversalId), runner.reconcileUnreachableTraversals(),
                    "reconciliation must report the traversal it ended");

            // Settles because reconciliation stranded the parked branch, not because time passed.
            // The store is still swallowing b1's arrival and always will be, so a recovery that
            // depended on the store answering would hang here rather than fail.
            ExecutionException ended = assertThrows(ExecutionException.class,
                    () -> execution.get(5, TimeUnit.SECONDS),
                    "the traversal must reach a terminal state instead of staying open forever");

            assertEquals(ExecutionTerminationReason.UNREACHABLE,
                    ExecutionTermination.reasonOf(ended.getCause()),
                    "the termination must be recorded with the reason that says no node broke");
            var verdict = unreachableVerdictIn(ended.getCause());
            assertEquals(traversalId, verdict.traversalId(),
                    "the verdict must name the traversal it was reached about");
            assertEquals(1, verdict.parkedBranches(),
                    "the verdict must carry the parked-branch evidence the criterion required");
            assertFalse(verdict.getMessage().contains("from-b0")
                            || verdict.getMessage().contains("from-b1"),
                    "the verdict is published on an execution event, so it must carry ids and counts "
                            + "and never a payload");

            assertEquals(0, runner.admissionGateCount(),
                    "the admission capacity the stuck traversal held must be returned");
            assertEquals(0, runner.liveParkedBranchCount(),
                    "no branch may stay parked once the traversal has ended");
            assertEquals(0, runner.liveCoordinatorCount(),
                    "the traversal's own registration must be gone, which is what makes the capacity "
                            + "available to a new execution");

            // The claim "capacity is returned" stated as a behaviour rather than as a counter. The
            // runner-wide admission gates are keyed by node and shared across traversals, so a
            // permit the stuck traversal failed to hand back would not show up in a count of its own
            // gate entries -- it would show up here, as a second execution that never gets admitted
            // to b0 and times out. The store stalls one nominated call and no other, so this
            // traversal is an ordinary healthy one.
            var admitted = runner.execute(TestIdentities.TENANT_A, UUID.randomUUID(), "in", "v1")
                    .toCompletableFuture();
            assertNotNull(admitted.get(5, TimeUnit.SECONDS),
                    "a new execution must be admitted and complete once the unreachable one has "
                            + "released the capacity it was holding");
        }
    }

    /**
     * A second call is not a second ending. The traversal is gone from the runner after the first,
     * so the criterion no longer names it, nothing is stranded again, and the capacity that was
     * returned once is not returned twice.
     *
     * <p>This is the shape of the concurrency guarantee rather than a proof of it under contention:
     * the exactly-once property lives in {@code JoinCoordinator#terminate}, which hands every caller
     * after the first the first caller's own stage. What is checked here is that the caller-facing
     * operation reports honestly which call did the work.</p>
     */
    @Test
    void asecondReconciliationEndsNothingAndReleasesNothingAgain() throws Exception {
        var store = new StalledJoinStore(new InMemoryJoinStore(), 2);
        UUID traversalId = UUID.randomUUID();
        var b1Gate = new CompletableFuture<NodeResult>();

        try (var manager = GraphManager.from(JoinMiniGraphs.fanIn(2));
             var runner = new GraphRunner(manager, engine, branchesWithGatedB1(b1Gate), monitor,
                     ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store,
                     java.time.Clock.systemUTC())) {
            var execution = runner.execute(TestIdentities.TENANT_A, traversalId, "in", "v1")
                    .toCompletableFuture();
            awaitParkedBranchCount(runner, 1);
            b1Gate.complete(NodeResult.continueWith("from-b1"));
            assertTrue(store.awaitStall(5_000), "the condition must be built before reconciling");

            assertEquals(Set.of(traversalId), runner.reconcileUnreachableTraversals());
            ExecutionException ended = assertThrows(ExecutionException.class,
                    () -> execution.get(5, TimeUnit.SECONDS));
            Throwable firstOutcome = ended.getCause();

            assertEquals(Set.of(), runner.reconcileUnreachableTraversals(),
                    "the traversal is already ended: a second call must report that it ended nothing");
            assertEquals(0, runner.admissionGateCount(),
                    "capacity is returned by the traversal's one release, so a second call cannot "
                            + "release it a second time");

            ExecutionException still = assertThrows(ExecutionException.class,
                    () -> execution.get(5, TimeUnit.SECONDS));
            assertSame(firstOutcome, still.getCause(),
                    "the recorded outcome must not be overwritten by a later reconciliation");
        }
    }

    /**
     * The false positive the criterion was corrected to avoid, checked against the recovery rather
     * than against the verdict: an ordinary node-to-node handoff is frozen in the exact window where
     * the previous node's worker instance is released and the next one is not yet registered, and
     * reconciliation must end nothing at all.
     *
     * <p>Asserting it here as well as in the criterion test is not duplication. A reconciliation that
     * consulted anything other than the criterion — a broader condition of its own, a retry, a
     * "close enough" fallback — would pass the criterion test untouched and kill a healthy execution
     * here.</p>
     */
    @Test
    void aHealthyNodeToNodeHandoffIsNeverReconciled() throws Exception {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("a", "a"),
                GraphNode.behavior("b", "b"),
                GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "a"), GraphEdge.to("a", "b"), GraphEdge.to("b", "end")));
        var gating = new SpawnGatingEngine(engine, "b-");
        UUID traversalId = UUID.randomUUID();

        // Kicked off the test thread for the reason SpawnGatingEngine documents: a fast pool can
        // otherwise carry the whole chain inline onto this thread and deadlock it against the gate.
        var kicker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, gating, linearBranches(), monitor,
                     ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(),
                     new InMemoryJoinStore(), java.time.Clock.systemUTC())) {
            var executionStarted = kicker.submit(() -> runner
                    .execute(TestIdentities.TENANT_A, traversalId, "in", "v1")
                    .toCompletableFuture());
            assertTrue(gating.awaitReached(5_000),
                    "b's spawn must have been reached for the transient to be frozen");

            assertEquals(0, runner.liveWorkerInstanceCount(),
                    "the transient this test needs: a has released and b is not registered yet");
            assertEquals(Set.of(), runner.reconcileUnreachableTraversals(),
                    "a healthy handoff must not be reconciled -- it has no parked branch, and the "
                            + "absence of currently-visible activity is not evidence of stuckness");

            gating.releaseSpawn();
            var execution = executionStarted.get(5, TimeUnit.SECONDS);
            assertNotNull(execution.get(5, TimeUnit.SECONDS),
                    "the healthy traversal must still complete normally after reconciliation ran "
                            + "over it");
        } finally {
            kicker.shutdownNow();
        }
    }

    /**
     * A stop asked for before the traversal was reconciled is honoured as a stop.
     *
     * <p>Cancellation refuses a traversal's next hop, and an unreachable traversal has none — which
     * is the case {@code cancelTraversal}'s own contract hands to the forced teardown. So the stop
     * is published, changes nothing, and the traversal stays stuck: exactly the shape this issue is
     * about, in the one place where an operator has already said what they want. Reconciliation is
     * where that stop finally lands, and recording it as a runtime fault would report a deliberate
     * act as an incident.</p>
     */
    @Test
    void aStopAskedForBeforeReconciliationIsRecordedAsACancellation() throws Exception {
        var store = new StalledJoinStore(new InMemoryJoinStore(), 2);
        UUID traversalId = UUID.randomUUID();
        var b1Gate = new CompletableFuture<NodeResult>();

        try (var manager = GraphManager.from(JoinMiniGraphs.fanIn(2));
             var runner = new GraphRunner(manager, engine, branchesWithGatedB1(b1Gate), monitor,
                     ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store,
                     java.time.Clock.systemUTC())) {
            var execution = runner.execute(TestIdentities.TENANT_A, traversalId, "in", "v1")
                    .toCompletableFuture();
            awaitParkedBranchCount(runner, 1);
            b1Gate.complete(NodeResult.continueWith("from-b1"));
            assertTrue(store.awaitStall(5_000), "the condition must be built before the stop");

            assertTrue(runner.cancelTraversal(traversalId),
                    "the stop is published against the id, as it always was");
            assertFalse(execution.isDone(),
                    "and it changes nothing on its own: there is no next hop for it to refuse, which "
                            + "is the gap reconciliation closes");

            assertEquals(Set.of(traversalId), runner.reconcileUnreachableTraversals());
            ExecutionException ended = assertThrows(ExecutionException.class,
                    () -> execution.get(5, TimeUnit.SECONDS));

            assertEquals(ExecutionTerminationReason.CANCELLED,
                    ExecutionTermination.reasonOf(ended.getCause()),
                    "the operator asked for this ending, so it is recorded as their action and not "
                            + "as a fault they did not cause");
            assertEquals(0, runner.admissionGateCount(),
                    "the capacity is returned either way -- what the stop changes is the reason, not "
                            + "the release");
        }
    }

    /**
     * The operator-facing composition: a stop, then the forced teardown, with no reconciliation call
     * in between — and the ending is still recorded as the operator's action.
     *
     * <p>This is the sequence {@code DefaultRavenrootApplication.cancelTraversal} actually performs.
     * It signals the runner and then closes it, because a cooperative stop cannot reach a stalled
     * traversal on its own; the teardown is the half that ends one. Before this change that teardown
     * stranded the parked branch with a bare join failure carrying no cause, so an operator who
     * deliberately stopped a stuck execution read an unqualified {@code FAILED} back — a fault they
     * did not cause, on the one path they were told to use.</p>
     *
     * <p>Asserted separately from the reconciliation path on purpose. The two paths reach the same
     * traversal through different code, and a fix to one that left the other reporting a fault would
     * pass every other test here while the operator-reachable behaviour stayed wrong.</p>
     */
    @Test
    void aStoppedStuckTraversalIsRecordedAsCancelledByTheForcedTeardownToo() throws Exception {
        var store = new StalledJoinStore(new InMemoryJoinStore(), 2);
        UUID traversalId = UUID.randomUUID();
        var b1Gate = new CompletableFuture<NodeResult>();

        var manager = GraphManager.from(JoinMiniGraphs.fanIn(2));
        var runner = new GraphRunner(manager, engine, branchesWithGatedB1(b1Gate), monitor,
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store,
                java.time.Clock.systemUTC());
        try {
            var execution = runner.execute(TestIdentities.TENANT_A, traversalId, "in", "v1")
                    .toCompletableFuture();
            awaitParkedBranchCount(runner, 1);
            b1Gate.complete(NodeResult.continueWith("from-b1"));
            assertTrue(store.awaitStall(5_000), "the condition must be built before the stop");

            assertTrue(runner.cancelTraversal(traversalId));
            assertFalse(execution.isDone(), "the cooperative stop alone cannot end a stalled traversal");

            // No reconcileUnreachableTraversals() here. This is the teardown half of the
            // application's own cancel, and it has to carry the operator's intent by itself.
            runner.close();

            ExecutionException ended = assertThrows(ExecutionException.class,
                    () -> execution.get(5, TimeUnit.SECONDS),
                    "the forced teardown must end the traversal the stop could not reach");
            assertEquals(ExecutionTerminationReason.CANCELLED,
                    ExecutionTermination.reasonOf(ended.getCause()),
                    "the operator stopped this execution, so the record must say so rather than "
                            + "report a fault they did not cause");
        } finally {
            runner.close();
            manager.close();
        }
    }

    /**
     * A shutdown that catches a traversal nobody asked to stop is not a cancellation.
     *
     * <p>The mirror of the test above, and the reason the verdict is conditional rather than applied
     * to everything the teardown reaches: labelling every traversal a shutdown happens to catch as
     * cancelled would fabricate an operator action nobody took, which is the same class of defect in
     * the opposite direction.</p>
     */
    @Test
    void aShutdownIsNotACancellationForATraversalNobodyStopped() throws Exception {
        var store = new StalledJoinStore(new InMemoryJoinStore(), 2);
        UUID traversalId = UUID.randomUUID();
        var b1Gate = new CompletableFuture<NodeResult>();

        var manager = GraphManager.from(JoinMiniGraphs.fanIn(2));
        var runner = new GraphRunner(manager, engine, branchesWithGatedB1(b1Gate), monitor,
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store,
                java.time.Clock.systemUTC());
        try {
            var execution = runner.execute(TestIdentities.TENANT_A, traversalId, "in", "v1")
                    .toCompletableFuture();
            awaitParkedBranchCount(runner, 1);
            b1Gate.complete(NodeResult.continueWith("from-b1"));
            assertTrue(store.awaitStall(5_000), "the condition must be built before the shutdown");

            // Reconciled first, so this test does not depend on close() completing against a store
            // that never answers -- which is a property of the shutdown bound, not of this claim.
            assertEquals(Set.of(traversalId), runner.reconcileUnreachableTraversals());
            ExecutionException ended = assertThrows(ExecutionException.class,
                    () -> execution.get(5, TimeUnit.SECONDS));

            assertEquals(ExecutionTerminationReason.UNREACHABLE,
                    ExecutionTermination.reasonOf(ended.getCause()),
                    "nobody stopped this traversal, so its ending must not be recorded as an "
                            + "operator action");
        } finally {
            runner.close();
            manager.close();
        }
    }

    /**
     * A shutdown that reaches a traversal nobody stopped does not end it as a cancellation.
     *
     * <p>The direct guard on the hazard the fix above creates. {@code close()} cancels every
     * traversal it holds before tearing them down, so the membership that answers "who did an
     * operator stop" is true only if it is read before that loop. Read after it, every traversal a
     * shutdown catches looks stopped, and a stuck one would be recorded as an operator action nobody
     * took — and, because the verdict also shortens the release, the runner's own leak diagnostics
     * would be cleared away at the moment they exist to report.</p>
     *
     * <p>What is asserted is the absence of a fabricated ending: with nobody having asked to stop it,
     * this traversal is left unsettled, exactly as a shutdown has always left it, rather than closed
     * out under a reason that would be untrue. The other half of the same defect — a shortened
     * release clearing away the runner's leak diagnostics — is asserted by
     * {@code JoinRetainedStateTest#reportsTheLiveTimeoutItCouldNotRelease}, whose fixture parks no
     * branch at all and so keeps its coordinator for the whole shutdown; this one's does reach
     * {@code release}, so the coordinator count here says nothing about that.</p>
     */
    @Test
    void aShutdownDoesNotEndATraversalNobodyStoppedAsACancellation() throws Exception {
        var store = new StalledJoinStore(new InMemoryJoinStore(), 2);
        UUID traversalId = UUID.randomUUID();
        var b1Gate = new CompletableFuture<NodeResult>();

        var manager = GraphManager.from(JoinMiniGraphs.fanIn(2));
        var runner = new GraphRunner(manager, engine, branchesWithGatedB1(b1Gate), monitor,
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store,
                java.time.Clock.systemUTC(), java.time.Duration.ofMillis(250));
        try {
            var execution = runner.execute(TestIdentities.TENANT_A, traversalId, "in", "v1")
                    .toCompletableFuture();
            awaitParkedBranchCount(runner, 1);
            b1Gate.complete(NodeResult.continueWith("from-b1"));
            assertTrue(store.awaitStall(5_000), "the condition must be built before the shutdown");

            // No cancelTraversal, no reconcile. Only the shutdown, which cancels internally.
            runner.close();

            // Under the defect this guards, the shutdown's own internal cancel is mistaken for an
            // operator's, the traversal is stranded with a cancellation verdict, its release is
            // shortened past the drain, and this future settles -- reporting an operator stop that
            // nobody asked for. Unsettled is therefore the correct outcome here, and it is the exact
            // observation that separates the two behaviours.
            assertFalse(execution.isDone(),
                    "a shutdown must not manufacture an ending for a traversal nobody stopped: its "
                            + "own internal cancel is not an operator's act");
        } finally {
            runner.close();
            manager.close();
        }
    }

    /**
     * An operator stop and a reconciliation can reach the same traversal together, and each attaches
     * its own cause on the way out. The classifier decides by rank rather than by which of the two
     * happened to wrap the other, so the reason a reader sees does not depend on a race.
     *
     * <p>Cancellation is the answer in both orders: it is the stronger statement about provenance,
     * and a reader told "this could never settle" about a deliberate stop would go looking for a
     * defect that does not exist.</p>
     */
    @Test
    void anOperatorCancellationOutranksAConcurrentUnreachableVerdict() {
        UUID traversalId = UUID.randomUUID();
        var cancelled = new TraversalCancelledException(traversalId, "b1");
        var unreachable = new TraversalUnreachableException(traversalId, 1);

        var cancelUnderUnreachable = new RuntimeException("wrapper", chain(unreachable, cancelled));
        var unreachableUnderCancel = new RuntimeException("wrapper", chain(cancelled, unreachable));

        assertEquals(ExecutionTerminationReason.CANCELLED,
                ExecutionTermination.reasonOf(cancelUnderUnreachable),
                "a cancellation found beneath an unreachable verdict is still a cancellation");
        assertEquals(ExecutionTerminationReason.CANCELLED,
                ExecutionTermination.reasonOf(unreachableUnderCancel),
                "and so is one found above it -- the answer must not depend on wrapping order");
        assertTrue(ExecutionTermination.isCancellation(cancelUnderUnreachable));
        assertFalse(ExecutionTermination.isUnreachable(cancelUnderUnreachable),
                "the two are mutually exclusive answers from one classifier");
    }

    /**
     * An ordinary failure keeps the absent reason it has always had. Adding a member to the
     * termination vocabulary must not start qualifying terminations that nothing distinguishes —
     * that would turn "an ordinary fault" into "a reconciled execution" on every existing run.
     */
    @Test
    void anOrdinaryFailureIsStillRecordedWithNoDistinguishingReason() {
        assertNull(ExecutionTermination.reasonOf(new IllegalStateException("a node broke")),
                "nothing distinguishes an ordinary fault, and absence is the answer that says so");
        assertNull(ExecutionTermination.reasonOf(null),
                "a traversal that did not end with a throwable has no reason to record");
        assertNull(ExecutionTermination.reasonOf(new JoinFailureException(
                        JoinFailureException.Reason.QUORUM_UNREACHABLE, "join", 2,
                        List.of("b0"), List.of("b1"), List.of())),
                "a join that lost its quorum to a failed branch is an ordinary fault: the traversal "
                        + "reached a verdict, which is precisely what an unreachable one cannot do");
    }

    /** {@code outer} above {@code inner}, both really in the chain, with a wrapper between them. */
    private static Throwable chain(Throwable outer, Throwable inner) {
        outer.initCause(new RuntimeException("intermediate wrapper", inner));
        return outer;
    }

    private static TraversalUnreachableException unreachableVerdictIn(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof TraversalUnreachableException verdict) {
                return verdict;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return assertInstanceOf(TraversalUnreachableException.class, failure,
                "the termination must carry the verdict that ended it");
    }

    /** b0 completes immediately; b1's own result is whatever the test later completes {@code gate} with. */
    private static BehaviorRegistry branchesWithGatedB1(CompletableFuture<NodeResult> gate) {
        var registry = new BehaviorRegistry();
        registry.register("b0", message -> CompletableFuture.completedFuture(NodeResult.continueWith("from-b0")));
        registry.register("b1", message -> gate);
        return registry;
    }

    private static BehaviorRegistry linearBranches() {
        var registry = new BehaviorRegistry();
        for (String node : List.of("a", "b")) {
            registry.register(node, message ->
                    CompletableFuture.completedFuture(NodeResult.continueWith("from-" + node)));
        }
        return registry;
    }

    /** Bounded setup synchronization, not an assertion; the idiom this package already uses. */
    private static void awaitParkedBranchCount(GraphRunner runner, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (runner.liveParkedBranchCount() == expected) {
                return;
            }
            Thread.sleep(5);
        }
        assertEquals(expected, runner.liveParkedBranchCount(),
                "b0 never reached the parked state this test needs before releasing b1");
    }
}
