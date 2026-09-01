package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CORE-03 join semantics: quorum, validation, deduplication, late arrival, failure and timeout. */
class JoinSemanticsTest {

    private final JoinTestEngine engine = new JoinTestEngine();
    private final ExecutionMonitor monitor = new ExecutionMonitor();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    // ---------------------------------------------------------------- quorum is validated up front

    @Test
    void rejectsAQuorumLargerThanTheBranchCount() {
        var error = assertThrows(JoinConfigurationException.class,
                () -> run(JoinMiniGraphs.fanIn(3, JoinMiniGraphs.quorum(4)), always()));

        assertEquals("join", error.nodeId());
        assertEquals(JoinSpec.QUORUM_PROPERTY, error.property());
        assertTrue(error.getMessage().contains("can never be reached"), error.getMessage());
    }

    @Test
    void rejectsAQuorumOfZero() {
        var error = assertThrows(JoinConfigurationException.class,
                () -> run(JoinMiniGraphs.fanIn(3, JoinMiniGraphs.quorum(0)), always()));
        assertTrue(error.getMessage().contains("at least 1"), error.getMessage());
    }

    @Test
    void rejectsANonNumericQuorum() {
        assertThrows(JoinConfigurationException.class,
                () -> run(JoinMiniGraphs.fanIn(3, Map.of(JoinSpec.QUORUM_PROPERTY, "most")), always()));
    }

    @Test
    void rejectsAPolicyThatContradictsAnExplicitQuorum() {
        var error = assertThrows(JoinConfigurationException.class, () -> run(JoinMiniGraphs.fanIn(3,
                Map.of(JoinSpec.POLICY_PROPERTY, "all", JoinSpec.QUORUM_PROPERTY, "1")), always()));
        assertTrue(error.getMessage().contains("disagree"), error.getMessage());
    }

    @Test
    void acceptsAPolicyThatAgreesWithAnExplicitQuorum() throws Exception {
        var result = run(JoinMiniGraphs.fanIn(3,
                Map.of(JoinSpec.POLICY_PROPERTY, "all", JoinSpec.QUORUM_PROPERTY, "3")), always());
        assertEquals(3, ((List<?>) result).size());
    }

    @Test
    void rejectsAJoinPropertyOnANodeThatIsNotAFanIn() {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("middle", NodeKind.PASSTHROUGH, null, JoinMiniGraphs.quorum(1)),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "middle"), GraphEdge.to("middle", "end")));

        var error = assertThrows(JoinConfigurationException.class, () -> run(graph, always()));
        assertTrue(error.getMessage().contains("is not a fan-in"), error.getMessage());
    }

    @Test
    void eachPolicyKeepsLegacyMergeArrivalsIndependent() throws Exception {
        var graph = JoinMiniGraphs.fanIn(2, Map.of(JoinSpec.POLICY_PROPERTY, "each"));

        var result = run(graph, always());

        assertTrue(List.of("from-b0", "from-b1").contains(result),
                "each is an OR-merge, so an arrival payload passes independently rather than as a merged list");
    }

    @Test
    void startWithFeedbackIsStillAnIngressAndNotAJoin() {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("a", "a"),
                GraphNode.behavior("b", "b"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "a"),
                GraphEdge.to("a", "start"),
                GraphEdge.to("b", "start"),
                GraphEdge.to("a", "end")));

        assertFalse(JoinSpec.validate(graph).containsKey("start"));
    }

    @Test
    void eachPolicyRejectsJoinOnlyConfiguration() {
        var error = assertThrows(JoinConfigurationException.class, () -> run(
                JoinMiniGraphs.fanIn(2, Map.of(
                        JoinSpec.POLICY_PROPERTY, "each",
                        JoinSpec.TIMEOUT_PROPERTY, "PT1S")), always()));

        assertTrue(error.getMessage().contains("cannot be set"), error.getMessage());
    }

    @Test
    void rejectsANonIsoTimeout() {
        var error = assertThrows(JoinConfigurationException.class, () -> run(
                JoinMiniGraphs.fanIn(2, Map.of(JoinSpec.TIMEOUT_PROPERTY, "30")), always()));
        assertTrue(error.getMessage().contains("ISO-8601"), error.getMessage());
    }

    /**
     * A join deadline is a retention window, not only a deadline: until it fires or is cancelled it
     * holds the coordinator, every arrival payload and the traversal's ingress security context. So
     * the property is bounded, and bounded at composition — a graph is a document, and this one
     * would otherwise let a document choose how long the runtime pins live memory.
     *
     * <p>Rejected before a single actor exists, for the SEC-09 reason the quorum check already
     * follows: a graph accepted, hashed and partly executed before its faulty property is reached is
     * a graph whose upstream nodes have already produced their effects.</p>
     */
    @Test
    void rejectsATimeoutBeyondTheCeilingBeforeSpawningAnyActor() {
        var error = assertThrows(JoinConfigurationException.class, () -> run(
                JoinMiniGraphs.fanIn(2, Map.of(JoinSpec.TIMEOUT_PROPERTY, "PT2400H")), always()));

        assertEquals("join", error.nodeId());
        assertEquals(JoinSpec.TIMEOUT_PROPERTY, error.property());
        assertTrue(error.getMessage().contains("exceeds the maximum"), error.getMessage());
        assertEquals(0, engine.spawnCount(),
                "the graph must be rejected before any node actor is spawned");
    }

    /** The absurd value the previous validation also accepted, and the boundary itself. */
    @Test
    void rejectsAnAbsurdTimeoutButAcceptsTheCeilingExactly() throws Exception {
        assertThrows(JoinConfigurationException.class, () -> run(
                JoinMiniGraphs.fanIn(2, Map.of(JoinSpec.TIMEOUT_PROPERTY, "PT9999999999H")), always()));

        var result = run(JoinMiniGraphs.fanIn(2, Map.of(JoinSpec.TIMEOUT_PROPERTY, "PT24H")), always());
        assertEquals(List.of("from-b0", "from-b1"), result, "the ceiling itself must remain usable");
    }

    /**
     * The topology that made the pre-CORE-03 expectation unsatisfiable. Counting incoming edges gave
     * three; only two arrivals can ever occur, so an {@code all} join over it could never complete.
     */
    @Test
    void countsDistinctPredecessorsRatherThanIncomingEdges() throws Exception {
        var result = run(JoinMiniGraphs.doubleEdgedDecision(Map.of()), node -> switch (node) {
            case "decision" -> new NodeResult("accepted", "from-decision", Map.of());
            default -> NodeResult.continueWith("from-" + node);
        });

        assertEquals(List.of("from-decision", "from-other"), result);
    }

    // ---------------------------------------------------------------------------- quorum behaviour

    /**
     * The quorum decides the join's result before the last branch reports. It does not shorten the
     * traversal: PERS-01 refuses a completed traversal holding a live invocation, and nothing can
     * stop {@code b2} until CORE-04 adds cancellation, so the traversal still waits for it — and
     * still ignores what it brought.
     */
    @Test
    void decidesTheResultOnTheQuorumAndIgnoresTheBranchThatArrivesAfterwards() throws Exception {
        var released = new CompletableFuture<NodeResult>();
        var graph = JoinMiniGraphs.fanIn(3, JoinMiniGraphs.quorum(2));

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry(node ->
                     NodeResult.continueWith("from-" + node), Map.of("b2", released)), monitor)) {
            var execution = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture();

            // The join fires on b0 and b1 while b2 is still blocked.
            awaitEvent(ExecutionEventType.JOIN_SATISFIED);
            assertFalse(execution.isDone(), "the traversal still owns b2's live invocation");

            released.complete(NodeResult.continueWith("from-b2"));
            var result = execution.get(5, TimeUnit.SECONDS).payload();
            assertEquals(List.of("from-b0", "from-b1"), result, "b2 arrived late and was not merged in");
        }
    }

    @Test
    void mergesAllBranchesInBranchIdOrderForAnAllJoin() throws Exception {
        var result = run(JoinMiniGraphs.fanIn(4), node -> NodeResult.continueWith("from-" + node));
        assertEquals(List.of("from-b0", "from-b1", "from-b2", "from-b3"), result);
    }

    @Test
    void unwrapsTheSinglePayloadOfAQuorumOfOne() throws Exception {
        var result = run(JoinMiniGraphs.fanIn(3, JoinMiniGraphs.quorum(1)), node ->
                NodeResult.continueWith("from-" + node));
        assertInstanceOf(String.class, result);
        assertTrue(result.toString().startsWith("from-b"), String.valueOf(result));
    }

    // --------------------------------------------------------------------- failure and reachability

    @Test
    void aFailedBranchDoesNotFailAQuorumItCannotStopReaching() throws Exception {
        var result = run(JoinMiniGraphs.fanIn(3, JoinMiniGraphs.quorum(2)), node ->
                "b0".equals(node) ? failure() : NodeResult.continueWith("from-" + node));

        assertEquals(List.of("from-b1", "from-b2"), result);
    }

    @Test
    void failsTheJoinAsSoonAsTheQuorumBecomesUnreachable() {
        var error = assertThrows(ExecutionException.class, () -> run(
                JoinMiniGraphs.fanIn(3, JoinMiniGraphs.quorum(3)), node ->
                        "b1".equals(node) ? failure() : NodeResult.continueWith("from-" + node)));

        JoinFailureException join = joinFailure(error);
        assertEquals(JoinFailureException.Reason.QUORUM_UNREACHABLE, join.reason());
        assertEquals(List.of("b1"), join.failed());
        // The branch's own error is preserved rather than replaced by the join's summary.
        assertTrue(List.of(join.getSuppressed()).stream()
                .anyMatch(suppressed -> "branch exploded".equals(suppressed.getMessage())),
                "the branch failure must survive as a suppressed cause");
    }

    /**
     * The failing node is two hops upstream of the join, so attribution has to follow the topology
     * rather than look at the join's immediate predecessor.
     */
    @Test
    void attributesAFailureUpstreamOfTheBranchToThatBranch() {
        var error = assertThrows(ExecutionException.class, () -> run(
                JoinMiniGraphs.deepBranch(Map.of()), node ->
                        "deep".equals(node) ? failure() : NodeResult.continueWith("from-" + node)));

        JoinFailureException join = joinFailure(error);
        assertEquals(JoinFailureException.Reason.QUORUM_UNREACHABLE, join.reason());
        assertEquals(List.of("b0"), join.failed(), "the dead branch is b0, not the node that failed");
    }

    @Test
    void survivesAFailureUpstreamOfABranchWhenTheQuorumStillHolds() throws Exception {
        var result = run(JoinMiniGraphs.deepBranch(JoinMiniGraphs.quorum(1)), node ->
                "deep".equals(node) ? failure() : NodeResult.continueWith("from-" + node));

        assertEquals("from-b1", result);
    }

    @Test
    void stillFailsTheTraversalWhenTheFailingNodeIsNotInsideAnyBranch() {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("solo", "solo"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "solo"), GraphEdge.to("solo", "end")));

        var error = assertThrows(ExecutionException.class,
                () -> run(graph, node -> "solo".equals(node) ? failure() : NodeResult.continueWith(node)));
        assertTrue(rootCause(error).getMessage().contains("branch exploded"), error.toString());
    }

    /**
     * A plain branch failure can reach the short-circuit-then-absorb shape, and this pins where it
     * stops.
     *
     * <p>{@code b1} fails, which makes the {@code all} join {@code inner} unreachable while
     * {@code b0} is still running. {@code inner}'s verdict is attributed to {@code inner} rather
     * than to the node reporting it, so the absorbing step looks only at what {@code inner} feeds —
     * {@code outer}, through {@code b3} — finds it still satisfiable through {@code b2}, and
     * swallows the failure. The parent stage then completes <em>successfully</em> with {@code b0}'s
     * whole sub-graph abandoned. No untaken branch is involved anywhere in this graph.</p>
     *
     * <p>So the runner does decide this traversal succeeded, and what stops it is the PERS-01
     * aggregate: {@code b0}'s invocation is still {@code RUNNING}, and a traversal refuses to become
     * {@code COMPLETED} with a non-terminal invocation. That refusal is the assertion here, together
     * with the fact that it is reported as the traversal's failure — published, and with the
     * aggregate driven to a terminal state — rather than thrown through the completion path and
     * leaving the traversal neither completed nor failed.</p>
     *
     * <p>This test therefore pins a boundary, not a contract. It is the alarm for the day someone
     * relaxes {@code hasIncompleteInvocations}, because on the other side of that rule this shape is
     * a traversal reported COMPLETED with no result and with {@code end} never executed.</p>
     */
    @Test
    void refusesToCallATraversalSuccessfulWhileTheBranchItAbandonedIsStillRunning() throws Exception {
        var blocked = new CompletableFuture<NodeResult>();
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("b0", "b0"),
                GraphNode.behavior("b1", "b1"),
                GraphNode.behavior("b2", "b2"),
                new GraphNode("inner", NodeKind.PASSTHROUGH, null, Map.of()),
                GraphNode.behavior("b3", "b3"),
                new GraphNode("outer", NodeKind.PASSTHROUGH, null, JoinMiniGraphs.quorum(1)),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "b0"),
                GraphEdge.to("start", "b1"),
                GraphEdge.to("b0", "inner"),
                GraphEdge.to("b1", "inner"),
                GraphEdge.to("b0", "b2"),
                GraphEdge.to("inner", "b3"),
                GraphEdge.to("b3", "outer"),
                GraphEdge.to("b2", "outer"),
                GraphEdge.to("outer", "end")));

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry(node ->
                     "b1".equals(node) ? failure() : NodeResult.continueWith("from-" + node),
                     Map.of("b0", blocked)), monitor)) {
            var execution = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture();

            var error = assertThrows(ExecutionException.class, () -> execution.get(5, TimeUnit.SECONDS),
                    "the traversal must not report success while b0's sub-graph is abandoned");
            Throwable cause = rootCause(error);
            assertInstanceOf(IllegalStateException.class, cause, error.toString());
            assertTrue(cause.getMessage().contains("incomplete invocations"), cause.toString());
            awaitEvent(ExecutionEventType.EXECUTION_FAILED);
            assertTrue(monitor.eventsAfter(0).stream()
                            .noneMatch(event -> event.type() == ExecutionEventType.EXECUTION_COMPLETED),
                    "a refused completion must not also be published as a completion");

            blocked.complete(NodeResult.continueWith("from-b0"));
        }
    }

    // ------------------------------------------------------------------------------------- timeout

    @Test
    void failsTheJoinOnTimeoutRatherThanCompletingWithWhatArrived() throws Exception {
        var blocked = new CompletableFuture<NodeResult>();
        var graph = JoinMiniGraphs.fanIn(2, JoinMiniGraphs.quorumWithTimeout(2, "PT30S"));

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry(node ->
                     "b1".equals(node) ? null : NodeResult.continueWith("from-" + node),
                     Map.of("b1", blocked)), monitor)) {
            var execution = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture();

            // b0 has arrived and b1 has not. The join is open and the deadline now passes.
            awaitJoinArrival();
            assertEquals(1, engine.manualScheduler().fireAll(), "exactly one join timeout was scheduled");

            var error = assertThrows(ExecutionException.class, () -> execution.get(5, TimeUnit.SECONDS));
            JoinFailureException join = joinFailure(error);
            assertEquals(JoinFailureException.Reason.TIMEOUT, join.reason());
            assertEquals(List.of("b0"), join.arrived());
            assertEquals(List.of("b1"), join.outstanding());
            blocked.complete(NodeResult.continueWith("too late"));
        }
    }

    @Test
    void cancelsTheTimeoutWhenTheJoinIsSatisfied() throws Exception {
        run(JoinMiniGraphs.fanIn(2, JoinMiniGraphs.quorumWithTimeout(2, "PT30S")), always());

        assertEquals(1, engine.manualScheduler().cancelledCount(), "the timeout must be cancelled, not left live");
        assertEquals(0, engine.manualScheduler().liveCount());
        assertEquals(0, engine.manualScheduler().fireAll(), "a cancelled timeout must not run");
    }

    @Test
    void schedulesNoTimeoutWhenNoneIsConfigured() throws Exception {
        run(JoinMiniGraphs.fanIn(2), always());
        assertEquals(0, engine.manualScheduler().liveCount() + engine.manualScheduler().cancelledCount());
    }

    // ------------------------------------------------------------------------ late arrival is data

    @Test
    void publishesADiscardedArrivalEventForABranchThatLosesTheQuorum() throws Exception {
        var slow = new CompletableFuture<NodeResult>();
        var graph = JoinMiniGraphs.fanIn(2, JoinMiniGraphs.quorum(1));

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry(node ->
                     "b1".equals(node) ? null : NodeResult.continueWith("from-" + node),
                     Map.of("b1", slow)), monitor)) {
            var execution = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture();

            // The join fires on b0. b1 then finishes and arrives at a join that has already settled.
            awaitEvent(ExecutionEventType.JOIN_SATISFIED);
            slow.complete(NodeResult.continueWith("from-b1"));
            execution.get(5, TimeUnit.SECONDS);

            var discarded = awaitEvent(ExecutionEventType.JOIN_ARRIVAL_DISCARDED);
            assertTrue(discarded.detail().contains("branch=b1"), discarded.detail());
            assertTrue(discarded.detail().contains("LATE"), discarded.detail());
        }
    }

    @Test
    void publishesSatisfiedExactlyOncePerJoin() throws Exception {
        run(JoinMiniGraphs.fanIn(4), always());

        long satisfied = monitor.eventsAfter(0).stream()
                .filter(event -> event.type() == ExecutionEventType.JOIN_SATISFIED)
                .count();
        assertEquals(1, satisfied);
    }

    @Test
    void neverRunsTheJoinNodeMoreThanOnce() throws Exception {
        var joinRuns = new AtomicInteger();
        var graph = JoinMiniGraphs.fanIn(6, JoinMiniGraphs.quorum(2));

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry(node ->
                     NodeResult.continueWith("from-" + node), Map.of())
                     .register("join-probe", message -> {
                         joinRuns.incrementAndGet();
                         return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                     }), monitor)) {
            runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        // The join node in this graph is a passthrough, so count the runtime's own event instead.
        assertEquals(1, monitor.eventsAfter(0).stream()
                .filter(event -> "join".equals(event.nodeId()))
                .filter(event -> event.type() == ExecutionEventType.NODE_STARTED)
                .count());
        assertFalse(joinRuns.get() > 0);
    }

    // ----------------------------------------------------------------------------------- helpers

    private java.util.function.Function<String, NodeResult> always() {
        return node -> NodeResult.continueWith("from-" + node);
    }

    private static NodeResult failure() {
        throw new IllegalStateException("branch exploded");
    }

    private Object run(GraphDefinition graph, java.util.function.Function<String, NodeResult> behaviour)
            throws Exception {
        return run(graph, behaviour, Map.of());
    }

    private Object run(GraphDefinition graph, java.util.function.Function<String, NodeResult> behaviour,
                       Map<String, CompletableFuture<NodeResult>> pending) throws Exception {
        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry(behaviour, pending), monitor)) {
            return runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).payload();
        }
    }

    private static BehaviorRegistry registry(java.util.function.Function<String, NodeResult> behaviour,
                                             Map<String, CompletableFuture<NodeResult>> pending) {
        var registry = new BehaviorRegistry();
        for (String node : List.of("b0", "b1", "b2", "b3", "b4", "b5", "decision", "other", "deep", "solo")) {
            registry.register(node, message -> {
                CompletableFuture<NodeResult> blocked = pending.get(node);
                if (blocked != null) {
                    return blocked;
                }
                try {
                    return CompletableFuture.completedFuture(behaviour.apply(node));
                } catch (RuntimeException error) {
                    return CompletableFuture.failedFuture(error);
                }
            });
        }
        return registry;
    }

    /** Waits until a branch has reached the join, so the timeout is fired against a partial join. */
    private void awaitJoinArrival() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (engine.manualScheduler().liveCount() > 0) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("no join timeout was ever scheduled");
    }

    private ai.ravenroot.api.application.ExecutionEvent awaitEvent(ExecutionEventType type)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var found = monitor.eventsAfter(0).stream().filter(event -> event.type() == type).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(5);
        }
        throw new AssertionError("no " + type + " event was published");
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

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
