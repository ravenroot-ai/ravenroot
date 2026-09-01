package ai.ravenroot.core.runtime;

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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unreachable-execution detection criterion and its evidence. This class establishes and checks the
 * verdict; it does not prescribe a recovery action.
 *
 * <h2>Why this is injected below the graph</h2>
 * <p>The only graph-shaped way to make a join wait forever is already closed: a branch that
 * is never taken is now reported {@code NOT_TAKEN}, so the traversal fails cleanly instead of
 * hanging. Every other graph-shaped attempt at "no active nodes, no scheduled waits, no branch in
 * suspension, yet not finished" fails one of the three conditions by construction -- a hung node
 * keeps a worker instance, a parked branch with a deadline keeps a timer. The one shape that is left
 * is a completion that should have happened and did not: a store call whose stage never settles.
 * {@link StalledJoinStore} is exactly that fixture, already in this package for the shutdown suite,
 * and it is used here unmodified.
 *
 * <h2>Why open traversals cannot identify this condition</h2>
 * <p>Three {@code GraphRunner} diagnostics -- {@link GraphRunner#liveWorkerInstanceCount()}, {@link
 * GraphRunner#liveJoinTimeoutCount()} and {@link GraphRunner#liveCoordinatorCount()} -- could be ANDed
 * together at zero, one signal per clause of "no active nodes, no scheduled waits, no branch in
 * suspension". That conjunction cannot detect the condition: {@code liveCoordinatorCount()} stays
 * at 1 for as long as the traversal itself is un-terminated, and a traversal with a lost completion is
 * <em>by definition</em> never terminated -- the same property that (harmlessly) covers the healthy
 * node-to-node handoff transient also makes that signal structurally unable to reach zero on the one
 * case it exists to catch.
 *
 * <h2>The safe criterion</h2>
 * <p>The third clause -- "no branch in suspension" -- is not "no
 * traversal is open" ({@code liveCoordinatorCount()}); it is "no branch is parked waiting for a join
 * that has not settled" ({@link JoinCoordinator#liveParkedBranchCount()}, new here, read-only over the
 * same {@code waiters} list {@link JoinCoordinator#liveTimeoutCount()}'s sibling already exists next
 * to). And it is not a fourth zero-clause: a parked branch that has nothing left running
 * ({@code liveWorkerInstanceCount() == 0}) or scheduled ({@code liveJoinTimeoutCount() == 0}) to ever
 * settle it is exactly the positive evidence of stuckness, so {@link
 * GraphRunner#unreachableTraversalIds()} requires it to be <strong>greater than</strong> zero.
 * {@link #aConstructedUnreachableExecutionIsDetectedByTheCorrectedCriterion()} shows the corrected
 * criterion sees the same constructed condition that a traversal-count-only criterion misses. {@link
 * #theCorrectedCriterionDoesNotFalsePositiveOnTheOrdinaryNodeToNodeHandoff()} shows it does not fire on
 * the transient the handoff itself is now known to produce (release precedes dispatch of the
 * successor), and demonstrates -- as its own explicit over-broad twin -- that dropping the
 * parked-branch requirement would have misfired on exactly that transient.</p>
 */
class UnreachableExecutionCriterionTest {

    private final JoinTestEngine engine = new JoinTestEngine();
    private final ExecutionMonitor monitor = new ExecutionMonitor();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    /**
     * Builds the unreachable condition -- no active nodes, no scheduled waits, a branch parked
     * with nothing left that could ever settle it, execution not finished -- by injecting a store
     * whose second {@code compareAndSet} (the arrival that would satisfy the {@code all} quorum of
     * two) never completes, on a join with no configured deadline. Every observation below is
     * synchronous once the store confirms the stall was reached: no clock is driven, and the
     * assertions read live state rather than wait for anything to happen.
     */
    @Test
    void aConstructedUnreachableExecutionIsDetectedByTheCorrectedCriterion() throws Exception {
        var store = new StalledJoinStore(new InMemoryJoinStore(), 2);
        // fanIn(2) with no join properties defaults to an "all" join over b0/b1 with NO timeout, so
        // liveJoinTimeoutCount() has nothing to hold live -- clause 2 ("no scheduled waits") is
        // satisfied honestly, not by a deadline nobody armed.
        var graph = JoinMiniGraphs.fanIn(2);
        UUID traversalId = UUID.randomUUID();
        // b1 is held on a manually-completed future so b0 is deterministically the FIRST arrival --
        // and therefore the one that parks -- rather than racing it: JoinTestEngine runs branches on
        // a real thread pool, so without this gate, "which of b0/b1 reaches the store's second
        // compareAndSet call first" and "has b0's own park already been recorded" are two different,
        // unordered races. Gating at the node's own returned future (not at spawn, see
        // SpawnGatingEngine's javadoc for why that boundary is unsafe here) blocks nothing
        // synchronously; it just leaves b1's stage pending until the test completes it.
        var b1Gate = new CompletableFuture<NodeResult>();

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, branchesWithGatedB1(b1Gate), monitor,
                     ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store,
                     java.time.Clock.systemUTC())) {
            var execution = runner.execute(TestIdentities.TENANT_A, traversalId, "in", "v1")
                    .toCompletableFuture();

            awaitParkedBranchCount(runner, 1);

            // b0 has parked; b1 is still held. Releasing it now makes its own arrival unambiguously
            // the second compareAndSet call -- the one StalledJoinStore(store, 2) stalls.
            b1Gate.complete(NodeResult.continueWith("from-b1"));
            assertTrue(store.awaitStall(5_000),
                    "b1's arrival must have reached the store for the condition to be built");

            // The construction itself: an execution that has neither succeeded nor failed, and never
            // will, because the completion that would settle it was swallowed one level below the
            // graph.
            assertFalse(execution.isDone(),
                    "the traversal must still be open -- this is the unreachable condition, not a "
                            + "finished one");

            // Clause 1, "no active nodes": both branches already delivered and released their worker
            // instance before parking at the join (GraphRunner#run releases in .handle(), strictly
            // before the successor dispatch that reaches the join).
            assertEquals(0, runner.liveWorkerInstanceCount(),
                    "no node is executing: both branches already ran and released");

            // Clause 2, "no scheduled waits": the join carries no deadline, so nothing was ever armed
            // at the scheduler.
            assertEquals(0, runner.liveJoinTimeoutCount(),
                    "no timeout is armed: this join has no configured deadline");

            // The disproven signal, kept as a permanent negative record so this is never re-proposed
            // without re-reading why it fails. liveCoordinatorCount() cannot be the third clause: the
            // traversal's own coordinator is removed from GraphRunner#coordinators only once
            // allOrFirstFailure(opening) settles the WHOLE traversal, which for this traversal never
            // happens by construction -- so it stays registered forever, exactly like it would for a
            // healthy traversal that simply has not finished yet.
            assertEquals(1, runner.liveCoordinatorCount(),
                    "liveCoordinatorCount() cannot serve as a zero-clause here: it never reaches zero "
                            + "for a traversal that can never terminate, which is precisely the case "
                            + "this detector must catch");

            // Clause 3, corrected: "no branch in suspension" is liveParkedBranchCount() > 0, not a
            // zero-condition. b0 arrived first, could not alone satisfy the quorum of two, and parked
            // in JoinDecision.Wait; nothing will ever complete it, because only a successful
            // compareAndSet for b1 could call completeWaiters(), and that call is exactly what is
            // stalled.
            assertEquals(1, runner.liveParkedBranchCount(),
                    "b0 is parked at the join, waiting for an outcome the stalled store will never "
                            + "produce");

            // The safe criterion sees this traversal, and only this one.
            assertEquals(Set.of(traversalId), runner.unreachableTraversalIds(),
                    "the safe criterion must name exactly the constructed traversal as "
                            + "unreachable");
        }
    }

    /**
     * {@code GraphRunner#run()} releases a completing node's worker instance in {@code .handle(...)}
     * strictly before {@code .thenCompose(dispatchSuccessors)} reaches the successor's own
     * {@code workers.acquire()}. {@link SpawnGatingEngine} freezes the runner in exactly that window
     * -- the previous node's instance is gone, the next one does not exist yet -- on a graph with no
     * join at all, so nothing can ever park a branch here. This is the healthy case the corrected
     * criterion must not call unreachable.
     *
     * <p>The {@code tooBroadCriterion} assertion checks the opposite direction. Dropping the
     * parked-branch requirement -- i.e. calling a traversal
     * unreachable whenever {@code liveWorkerInstanceCount() == 0 && liveJoinTimeoutCount() == 0}, with
     * no third clause at all -- reads {@code true} at this exact instant, on this exact healthy
     * traversal. That is the false positive the parked-branch requirement prevents.</p>
     */
    @Test
    void theCorrectedCriterionDoesNotFalsePositiveOnTheOrdinaryNodeToNodeHandoff() throws Exception {
        // start -> a -> b -> end: no fan-in, so nothing in this graph can ever park a branch.
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("a", "a"),
                GraphNode.behavior("b", "b"),
                GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "a"), GraphEdge.to("a", "b"), GraphEdge.to("b", "end")));
        // b's worker instance is registered under actor name "b-<invocationId>" (WorkerInstanceIdentity
        // #actorName()); gating every spawn whose logical name starts with "b-" freezes the handoff
        // between a's release and b's acquire without racing it.
        var gating = new SpawnGatingEngine(engine, "b-");
        UUID traversalId = UUID.randomUUID();

        // execute() is invoked from a dedicated thread, not this test's own: JoinTestEngine.send()
        // submits to an idle, fast pool, and when the pool wins the race to complete a node before
        // the caller attaches its own continuation, every dependent stage runs inline, recursively,
        // on the calling thread -- which can carry the entire start -> a -> b chain, including the
        // gated spawn call, onto this test's own thread and deadlock it against the awaitReached /
        // releaseSpawn calls below (see SpawnGatingEngine's class javadoc; found by hitting exactly
        // that deadlock).
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

            // The real transient, observed rather than assumed: a released, b not yet registered.
            assertEquals(0, runner.liveWorkerInstanceCount(),
                    "a already released its instance and b's is not registered until spawn returns");
            assertEquals(0, runner.liveJoinTimeoutCount(), "this graph has no join at all");
            assertEquals(0, runner.liveParkedBranchCount(),
                    "nothing can park here: there is no fan-in in this graph");

            // The over-broad twin, checked in place: this is exactly what a criterion with no
            // parked-branch clause would evaluate right now, on a perfectly healthy traversal.
            boolean tooBroadCriterion = runner.liveWorkerInstanceCount() == 0
                    && runner.liveJoinTimeoutCount() == 0;
            assertTrue(tooBroadCriterion,
                    "the over-broad twin reads true here -- proof that dropping the parked-branch "
                            + "requirement would misreport this healthy handoff as unreachable");

            // The safe criterion does not misfire, because it requires evidence of an actual
            // wait, not merely the absence of currently-visible activity.
            assertEquals(Set.of(), runner.unreachableTraversalIds(),
                    "the safe criterion must not flag an ordinary, healthy node-to-node handoff");

            gating.releaseSpawn();
            var execution = executionStarted.get(5, TimeUnit.SECONDS);
            execution.get(5, TimeUnit.SECONDS);
        } finally {
            kicker.shutdownNow();
        }
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

    /**
     * Bounded setup synchronization, not an assertion: matches the {@code awaitRecordCount} idiom
     * already used elsewhere in this package (see {@code JoinRetainedStateTest}) for waiting on a
     * concurrent side effect before the deterministic, synchronous part of a test begins.
     */
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
