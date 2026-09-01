package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The branch that is never taken (CORE-03).
 *
 * <p>A join branch has three ways to settle that the runtime could already see — it arrives, it
 * fails, or the deadline passes — and one it could not: it is never dispatched at all. A decision
 * whose outcomes rejoin is the most ordinary graph that produces it, and under the default
 * {@code all} policy the join waited for an arrival that no execution could ever produce. Because a
 * waiting branch parks on a future that only the join completes, nothing ever completed the
 * traversal, so nothing ever released it: the coordinator, every arrival payload, the ingress
 * security context, one actor per node and the open join record were all retained for the life of
 * the process, once per submission.</p>
 *
 * <p>These tests measure termination and retention, not just the returned error, because the error
 * was never the expensive part.</p>
 */
class JoinUntakenBranchTest {

    private final JoinTestEngine engine = new JoinTestEngine();
    private final ExecutionMonitor monitor = new ExecutionMonitor();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    /**
     * The reproduction. Default join properties, so this is the configuration a graph gets when its
     * author configures nothing at all.
     *
     * <p>The retention counts are read <strong>before</strong> {@code close()}, deliberately.
     * {@code close()} releases everything by force, so a measurement taken after it would pass just
     * as well against the leak: what has to be shown is that the traversal released itself.</p>
     */
    @Test
    void terminatesAndRetainsNothingWhenADecisionRejoinsOneHopOut() throws Exception {
        var store = new InMemoryJoinStore();
        var manager = GraphManager.from(JoinMiniGraphs.rejoiningDecision(Map.of()));
        var runner = new GraphRunner(manager, engine, decisionTaking("accepted"), monitor,
                ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC());
        try {
            var execution = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture();

            // Bounded: without not-taken-branch accounting this future never completes, so the assertion that
            // matters first is simply that the traversal reached an outcome.
            var error = assertThrows(ExecutionException.class, () -> execution.get(5, TimeUnit.SECONDS));
            JoinFailureException join = joinFailure(error);
            assertEquals(JoinFailureException.Reason.BRANCH_NOT_TAKEN, join.reason());
            assertEquals(List.of("y"), join.notTaken(), "y is the branch the decision did not select");
            assertEquals(List.of(), join.failed(), "nothing failed; reporting y as failed would be untrue");

            assertEquals(0, store.totalRecordCount(), "the open join record must not survive the traversal");
            assertEquals(0, runner.liveCoordinatorCount(), "the coordinator must not outlive the traversal");
            assertEquals(0, runner.liveJoinTimeoutCount());
        } finally {
            runner.close();
            manager.close();
        }
    }

    /**
     * The same topology with the policy its author almost certainly meant. This is what makes the
     * test above a report of a real defect rather than a decision to reject a whole class of graph:
     * a rejoining decision under {@code any} is legal, useful and now works.
     */
    @Test
    void completesTheSameTopologyWhenTheQuorumIsOne() throws Exception {
        var store = new InMemoryJoinStore();
        try (var manager = GraphManager.from(JoinMiniGraphs.rejoiningDecision(JoinMiniGraphs.quorum(1)));
             var runner = new GraphRunner(manager, engine, decisionTaking("rejected"), monitor,
                     ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC())) {
            var result = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals("from-y", result.payload(), "the taken branch alone satisfies a quorum of one");
            assertTrue(result.visitedNodes().contains("join"), "the join must actually fire");
            assertEquals(0, store.totalRecordCount());
        }
    }

    /**
     * Why rejecting unsatisfiable joins at composition could not have been the whole answer.
     *
     * <p>This join is statically satisfiable: under outcome {@code a} both {@code p} and {@code q}
     * run and the quorum of two is met, so no composition-time check may reject the graph. Under
     * outcome {@code b} only {@code r} runs, and the join is short by one — a fact that does not
     * exist until an execution chooses. A static rule would have accepted this graph and it would
     * still have hung, which is why the untaken case is decided at run time.</p>
     */
    @Test
    void terminatesWhenAJoinIsSatisfiableStaticallyButNotOnTheOutcomeTaken() throws Exception {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("decision", "decision"),
                GraphNode.behavior("p", "p"),
                GraphNode.behavior("q", "q"),
                GraphNode.behavior("r", "r"),
                new GraphNode("join", NodeKind.PASSTHROUGH, null, JoinMiniGraphs.quorum(2)),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "decision"),
                new GraphEdge("decision", "p", "a"),
                new GraphEdge("decision", "q", "a"),
                new GraphEdge("decision", "r", "b"),
                GraphEdge.to("p", "join"),
                GraphEdge.to("q", "join"),
                GraphEdge.to("r", "join"),
                GraphEdge.to("join", "end")));

        var store = new InMemoryJoinStore();
        var manager = GraphManager.from(graph);
        var runner = new GraphRunner(manager, engine, decisionTaking("b"), monitor,
                ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC());
        try {
            var execution = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture();
            var error = assertThrows(ExecutionException.class, () -> execution.get(5, TimeUnit.SECONDS));

            JoinFailureException join = joinFailure(error);
            assertEquals(JoinFailureException.Reason.BRANCH_NOT_TAKEN, join.reason());
            assertEquals(List.of("p", "q"), join.notTaken());
            assertEquals(0, store.totalRecordCount());
            assertEquals(0, runner.liveCoordinatorCount());
        } finally {
            runner.close();
            manager.close();
        }
    }

    /**
     * The false positive that a reachability-based implementation would produce.
     *
     * <p>{@code z} is reachable from the untaken {@code y} and from the taken {@code x}. Walking
     * forward from {@code y} and marking every join branch it reaches — which is what
     * {@code failureBranches} does for the failure case — would declare {@code z} dead to the outer
     * join and fail a traversal that is proceeding perfectly well. Liveness is counted per
     * predecessor instead, so {@code z} stays live while {@code x} can still reach it.</p>
     *
     * <p>{@code z} is itself a fan-in, because any node with two distinct predecessors is one. It
     * carries {@code quorum=1} so that it is satisfied by whichever of {@code x} and {@code y} the
     * decision selected — which is exactly the policy the outer graph's author wants at a rejoin,
     * and the one the reproduction above shows is mandatory there.</p>
     */
    @Test
    void doesNotKillABranchThatAnotherLivePathStillReaches() throws Exception {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("decision", "decision"),
                GraphNode.behavior("x", "x"),
                GraphNode.behavior("y", "y"),
                new GraphNode("z", NodeKind.PASSTHROUGH, null, JoinMiniGraphs.quorum(1)),
                GraphNode.behavior("w", "w"),
                new GraphNode("join", NodeKind.PASSTHROUGH, null, Map.of()),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "decision"),
                GraphEdge.to("start", "w"),
                new GraphEdge("decision", "x", "accepted"),
                new GraphEdge("decision", "y", "rejected"),
                GraphEdge.to("x", "z"),
                GraphEdge.to("y", "z"),
                GraphEdge.to("z", "join"),
                GraphEdge.to("w", "join"),
                GraphEdge.to("join", "end")));

        var store = new InMemoryJoinStore();
        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, decisionTaking("accepted"), monitor,
                     ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC())) {
            var result = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(List.of("from-w", "from-x"), result.payload(),
                    "z is alive through x, so the all-join over {z, w} must be satisfied");
            assertEquals(0, store.totalRecordCount());
        }
    }

    /**
     * A branch that speaks for itself overrides a not-taken conclusion recorded on its behalf.
     *
     * <p>The runner never produces this on its own: it concludes a branch is not taken only once
     * every path to it has closed, so within one process the conclusion cannot be contradicted. The
     * case that matters is the durable one — a record written by a <em>previous</em> runtime, whose
     * at-least-once redelivery then brings the branch back. The record is therefore seeded directly,
     * because seeding it is the only honest way to reproduce a restart against a shared store.</p>
     *
     * <p>Without the provisional rule the redelivery reads as a duplicate, the quorum is never met,
     * and a join that should recover instead fails.</p>
     */
    @Test
    void anArrivalSupersedesANotTakenLeftBehindByAPreviousRuntime() throws Exception {
        var store = new InMemoryJoinStore();
        var processInstanceId = java.util.UUID.randomUUID();
        var traversalId = java.util.UUID.randomUUID();
        var key = new ai.ravenroot.api.persistence.JoinKey(TestIdentities.TENANT_A.tenantId(),
                processInstanceId, traversalId, "join");
        store.compareAndSet(ai.ravenroot.api.persistence.JoinRecord.opening(key, java.time.Instant.now())
                        .next(Map.of("b0", ai.ravenroot.api.persistence.JoinBranchOutcome.NOT_TAKEN),
                                ai.ravenroot.api.persistence.JoinPhase.OPEN, null))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        var releaseB1 = new CompletableFuture<NodeResult>();
        var registry = new BehaviorRegistry();
        registry.register("b0", message -> CompletableFuture.completedFuture(NodeResult.continueWith("from-b0")));
        registry.register("b1", message -> releaseB1);

        try (var manager = GraphManager.from(JoinMiniGraphs.fanIn(2));
             var runner = new GraphRunner(manager, engine, registry, monitor,
                     ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC())) {
            var execution = runner.execute(TestIdentities.TENANT_A, processInstanceId, traversalId, "in", "v1")
                    .toCompletableFuture();

            // b1 is held until b0's redelivery has actually overwritten the stale conclusion. Letting
            // the two race would measure dispatch order: b1 landing first turns the seeded NOT_TAKEN
            // into the last word on a two-branch quorum and fails the join before b0 is heard.
            awaitBranchOutcome(store, key, "b0", ai.ravenroot.api.persistence.JoinBranchOutcome.ARRIVED);
            releaseB1.complete(NodeResult.continueWith("from-b1"));

            var result = execution.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("from-b0", "from-b1"), result.payload(),
                    "b0 was redelivered and must be counted, not read as a duplicate of a stale conclusion");
            assertEquals(0, store.totalRecordCount());
        }
    }

    /**
     * A join that is itself dead is not a traversal failure.
     *
     * <p>Every branch of {@code J2} is on the side of the decision that was not taken, so
     * {@code J2} is a dead node in exactly the sense {@code y}, {@code p} and {@code q} are. Its
     * quorum is unreachable, but that is a property of a sub-graph this execution did not select,
     * not an outcome of the execution: nothing was waiting for it and nothing downstream of it was
     * going to run either way.</p>
     *
     * <p>Note also that this cannot be decided as the not-taken reports are emitted. {@code p} is
     * proven dead before {@code q} is, and at that instant {@code J2} still has an outstanding
     * branch and looks alive. The dead set has to be closed before any verdict is carried.</p>
     */
    @Test
    void doesNotFailTheTraversalForAJoinThatIsItselfOnTheUntakenSide() throws Exception {
        var store = new InMemoryJoinStore();
        var manager = GraphManager.from(JoinMiniGraphs.deadSideJoin(Map.of()));
        var runner = new GraphRunner(manager, engine, deadSideBehaviours("a"), monitor,
                ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC());
        try {
            var result = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals("from-x", result.payload(), "the live branch decides the traversal's result");
            assertEquals(java.util.Set.of("start", "d", "x", "end"), result.visitedNodes(),
                    "nothing on the untaken side runs, and the end node on the live side does");
            assertEquals(0, store.totalRecordCount(), "a dead join must not even open a record");
            assertEquals(0, runner.liveCoordinatorCount());
            assertEquals(0, runner.liveJoinTimeoutCount());
        } finally {
            runner.close();
            manager.close();
        }
    }

    /** The same dead join under {@code quorum=1}: still dead, still not the traversal's problem. */
    @Test
    void doesNotFailTheTraversalForADeadJoinWhoseQuorumIsOne() throws Exception {
        var store = new InMemoryJoinStore();
        var manager = GraphManager.from(JoinMiniGraphs.deadSideJoin(JoinMiniGraphs.quorum(1)));
        var runner = new GraphRunner(manager, engine, deadSideBehaviours("a"), monitor,
                ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC());
        try {
            var result = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals("from-x", result.payload());
            assertEquals(java.util.Set.of("start", "d", "x", "end"), result.visitedNodes());
            assertEquals(0, store.totalRecordCount());
            assertEquals(0, runner.liveCoordinatorCount());
            assertEquals(0, runner.liveJoinTimeoutCount());
        } finally {
            runner.close();
            manager.close();
        }
    }

    /**
     * The dead side reconverging onto the live one, which is where a wrong answer is silent.
     *
     * <p>A verdict carried from the dead {@code J2} is attributed to {@code J2} rather than to the
     * node reporting it, so the absorbing step looks at what {@code J2} feeds — {@code J3}, through
     * {@code tail} — finds it still satisfiable, and swallows the failure. The parent stage then
     * completes while the live branch is still parked at {@code J3}, and the traversal reports
     * success with no result and with {@code end} never executed.</p>
     *
     * <p>So this asserts the payload <em>and</em> the path. Asserting only that the future completed
     * passes against precisely the defect it is here to catch.</p>
     */
    @Test
    void completesThroughTheLiveBranchWhenTheDeadSideReconverges() throws Exception {
        var store = new InMemoryJoinStore();
        var manager = GraphManager.from(JoinMiniGraphs.deadSideJoinReconverging(Map.of()));
        var runner = new GraphRunner(manager, engine, deadSideBehaviours("a"), monitor,
                ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC());
        try {
            var result = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals("from-x", result.payload(),
                    "J3 has a quorum of one and the live branch met it, so the result is x's");
            assertEquals(java.util.Set.of("start", "d", "x", "J3", "end"), result.visitedNodes(),
                    "the downstream join and the end node must both actually run");
            assertEquals(0, store.totalRecordCount());
            assertEquals(0, runner.liveCoordinatorCount());
            assertEquals(0, runner.liveJoinTimeoutCount());
        } finally {
            runner.close();
            manager.close();
        }
    }

    /**
     * The dead join's two branches die in two separate walks, at two different moments.
     *
     * <p>{@code m} and {@code n} decide independently, so {@code p} is proven dead by one of them
     * and {@code q} by the other. Closing the dead set within a single walk is therefore not enough
     * on its own: when the first walk finishes, {@code J} still has an outstanding branch and is
     * indistinguishable from a live join, and only the second walk proves it dead. A report has to
     * be held across the two, which is why the rule is stated over the join's own liveness rather
     * than over one walk's conclusions.</p>
     *
     * <p>{@code x} is a live {@code all} join over {@code m} and {@code n} in the same graph, so
     * this also shows the held report is not simply a licence to stay silent: the join both
     * decisions <em>do</em> reach is satisfied and runs.</p>
     */
    @Test
    void doesNotFailWhenTwoIndependentDecisionsKillAJoinsBranchesSeparately() throws Exception {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("m", "m"),
                GraphNode.behavior("n", "n"),
                new GraphNode("x", NodeKind.PASSTHROUGH, null, Map.of()),
                GraphNode.behavior("p", "p"),
                GraphNode.behavior("q", "q"),
                new GraphNode("J", NodeKind.PASSTHROUGH, null, Map.of()),
                GraphNode.behavior("tail", "tail"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "m"),
                GraphEdge.to("start", "n"),
                new GraphEdge("m", "x", "a"),
                new GraphEdge("m", "p", "b"),
                new GraphEdge("n", "x", "a"),
                new GraphEdge("n", "q", "b"),
                GraphEdge.to("x", "end"),
                GraphEdge.to("p", "J"),
                GraphEdge.to("q", "J"),
                GraphEdge.to("J", "tail")));

        var registry = new BehaviorRegistry();
        for (String decision : List.of("m", "n")) {
            registry.register(decision, message ->
                    CompletableFuture.completedFuture(new NodeResult("a", "from-" + decision, Map.of())));
        }
        for (String node : List.of("p", "q", "tail")) {
            registry.register(node, message ->
                    CompletableFuture.completedFuture(NodeResult.continueWith("from-" + node)));
        }

        var store = new InMemoryJoinStore();
        var manager = GraphManager.from(graph);
        var runner = new GraphRunner(manager, engine, registry, monitor,
                ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC());
        try {
            var result = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(List.of("from-m", "from-n"), result.payload(),
                    "the live join over both decisions is satisfied and carries both arrivals");
            assertEquals(java.util.Set.of("start", "m", "n", "x", "end"), result.visitedNodes());
            assertEquals(0, store.totalRecordCount());
            assertEquals(0, runner.liveCoordinatorCount());
            assertEquals(0, runner.liveJoinTimeoutCount());
        } finally {
            runner.close();
            manager.close();
        }
    }

    private static void awaitBranchOutcome(InMemoryJoinStore store, ai.ravenroot.api.persistence.JoinKey key,
                                           String branchId,
                                           ai.ravenroot.api.persistence.JoinBranchOutcome expected)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var found = store.load(key).toCompletableFuture().get(5, TimeUnit.SECONDS);
            if (found.isPresent() && found.get().branches().get(branchId) == expected) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("branch " + branchId + " never reached " + expected
                + "; the redelivery did not supersede the conclusion recorded about it");
    }

    // ----------------------------------------------------------------------------------- helpers

    private static BehaviorRegistry decisionTaking(String outcome) {
        var registry = new BehaviorRegistry();
        registry.register("decision", message ->
                CompletableFuture.completedFuture(new NodeResult(outcome, "from-decision", Map.of())));
        for (String node : List.of("x", "y", "z", "w", "p", "q", "r", "b0", "b1")) {
            registry.register(node, message ->
                    CompletableFuture.completedFuture(NodeResult.continueWith("from-" + node)));
        }
        return registry;
    }

    /** {@code d} takes {@code outcome}; everything else is a passthrough that names itself. */
    private static BehaviorRegistry deadSideBehaviours(String outcome) {
        var registry = new BehaviorRegistry();
        registry.register("d", message ->
                CompletableFuture.completedFuture(new NodeResult(outcome, "from-d", Map.of())));
        for (String node : List.of("x", "y", "p", "q", "tail")) {
            registry.register(node, message ->
                    CompletableFuture.completedFuture(NodeResult.continueWith("from-" + node)));
        }
        return registry;
    }

    private static JoinFailureException joinFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof JoinFailureException failure) {
                return failure;
            }
            current = current.getCause();
        }
        throw new AssertionError("expected a JoinFailureException, got " + error, error);
    }
}
