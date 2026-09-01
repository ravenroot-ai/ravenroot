package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a traversal is completed and when it is failed.
 *
 * <h2>The topology under test</h2>
 * <p>Two {@code program} nodes fail with an empty tool allowlist while a healthy branch satisfies
 * the join quorum. The failing nodes must themselves be <em>branches of the join</em>. When they are,
 * the healthy branch
 * absorbs both failures through {@code GraphRunner.absorbIntoJoins} (CORE-03) and the traversal
 * completes. When they merely dead-end — "without an exit" — nothing absorbs them and the traversal
 * fails.</p>
 *
 * <p>The verdict is right on both mechanisms below, and the claim is deliberately narrow:
 * <strong>a traversal whose every failure
 * was either absorbed by a satisfied quorum or carried by a failure route is genuinely complete.</strong>
 * What was wrong is the <em>event</em>. {@code GraphExecutionResult} has carried
 * {@code handledFailure()} and named the nodes, but nothing on the event stream did, and a
 * log is what an operator reads. {@link #namesTheHandledFailuresOnTheCompletionEventItself()} is the
 * test that would have caught it, and is red under a mutant that restores the unconditional
 * {@code "execution completed"} detail.</p>
 *
 * <h2>Why these live here and not in {@code GraphRunnerFailureRouteTest}</h2>
 * <p>That class pins what failure <em>routing</em> does — which edges fire, which do not, what the
 * routed payload carries. These pin what the traversal's <em>verdict</em> is and how it is reported,
 * which previously had no single home and was being answered case by case. The
 * document above is that home; this class is its executable half.</p>
 */
class TraversalVerdictTest {

    private final JoinTestEngine engine = new JoinTestEngine();
    private final ExecutionMonitor monitor = new ExecutionMonitor();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    private static final String ALLOWLIST_REFUSAL = "Tool is not allowlisted: program.execute";

    /**
     * Two {@code program} nodes
     * fanned out from {@code start}, each with a <em>bare</em> edge into the one {@code ERROR} node,
     * which continues to {@code end}. Nothing here declares a failure route; the default is what
     * turns both bare edges into one.
     */
    private static GraphDefinition twoProgramsIntoErrorTerminal() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("program-1", "program-1"),
                GraphNode.behavior("program-2", "program-2"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "program-1"),
                GraphEdge.to("start", "program-2"),
                GraphEdge.to("program-1", "error"),
                GraphEdge.to("program-2", "error"),
                GraphEdge.to("error", "end")));
    }

    private static BehaviorRegistry bothProgramsRefused() {
        return new BehaviorRegistry()
                .register("program-1", message -> CompletableFuture.failedFuture(
                        new IllegalStateException(ALLOWLIST_REFUSAL)))
                .register("program-2", message -> CompletableFuture.failedFuture(
                        new IllegalStateException(ALLOWLIST_REFUSAL)));
    }

    /**
     * Three branches fan out
     * from {@code start} — two {@code program} nodes and one healthy node — and all three are
     * branches of {@code END}, taken as a fan-in with a quorum of one. The healthy branch meets the
     * quorum; both program failures are then absorbed by {@code absorbIntoJoins}, because a failure
     * inside a join's branch belongs to that join and not to the traversal (CORE-03). The traversal
     * completes, having published two {@code NODE_FAILED} events:
     *
     * <pre>
     * NODE_FAILED nodeId=program-1 detail="Tool is not allowlisted: program.execute"
     * NODE_FAILED nodeId=program-2 detail="Tool is not allowlisted: program.execute"
     * EXECUTION_COMPLETED
     * </pre>
     *
     * <p>There is <strong>no error node and no failure route anywhere in this graph</strong>. It is red under a
     * mutant that propagates a branch failure past a satisfied quorum.</p>
     *
     * <p>{@link #failsWhenTheSameTwoFailuresAreNotBranchesOfTheJoin()} is its control, and the two
     * differ by one edge.</p>
     */
    @Test
    void completesWhenAJoinMetItsQuorumOverTwoFailedBranches() throws Exception {
        var registry = new BehaviorRegistry()
                .register("program-1", message -> CompletableFuture.failedFuture(
                        new IllegalStateException(ALLOWLIST_REFUSAL)))
                .register("program-2", message -> CompletableFuture.failedFuture(
                        new IllegalStateException(ALLOWLIST_REFUSAL)))
                .register("healthy", message -> CompletableFuture.completedFuture(
                        NodeResult.continueWith("healthy-result")));

        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("program-1", "program-1"),
                GraphNode.behavior("program-2", "program-2"),
                GraphNode.behavior("healthy", "healthy"),
                new GraphNode("end", NodeKind.END, null, JoinMiniGraphs.quorum(1))), List.of(
                GraphEdge.to("start", "program-1"),
                GraphEdge.to("start", "program-2"),
                GraphEdge.to("start", "healthy"),
                GraphEdge.to("program-1", "end"),
                GraphEdge.to("program-2", "end"),
                GraphEdge.to("healthy", "end")));

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry, monitor,
                     ExecutionIdentitySource.randomUuids(), new InMemoryJoinStore(), Clock.systemUTC())) {
            var result = runner.execute(TestIdentities.TENANT_A, "the-input").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals("healthy-result", result.payload(),
                    "the quorum was met by the healthy branch, so its value is the result");
            assertEquals(Set.of("program-1", "program-2"), result.handledFailureNodes(),
                    "both failures were absorbed by the satisfied quorum, not routed -- there is no "
                            + "failure route in this graph at all");

            List<ExecutionEvent> events = monitor.eventsAfter(0);
            assertEquals(2, events.stream().filter(e -> e.type() == ExecutionEventType.NODE_FAILED).count(),
                    "both program nodes must still publish their own NODE_FAILED");
            assertTrue(events.stream().anyMatch(e -> e.type() == ExecutionEventType.EXECUTION_COMPLETED),
                    "and the traversal completes after the satisfied quorum absorbs both failures");
        }
    }

    /**
     * The control for the shape above: the same two failures and the same healthy branch reaching
     * {@code END}, but
     * the programs are <em>not</em> branches of the join — they have no outgoing edge at all. Nothing
     * absorbs them, so unrouted failures make the traversal fail.
     *
     * <p>Red under a mutant that absorbs a failure from a node that owns no join branch, which would
     * make every unrouted failure survivable.</p>
     */
    @Test
    void failsWhenTheSameTwoFailuresAreNotBranchesOfTheJoin() throws Exception {
        var registry = new BehaviorRegistry()
                .register("program-1", message -> CompletableFuture.failedFuture(
                        new IllegalStateException(ALLOWLIST_REFUSAL)))
                .register("program-2", message -> CompletableFuture.failedFuture(
                        new IllegalStateException(ALLOWLIST_REFUSAL)))
                .register("healthy", message -> CompletableFuture.completedFuture(
                        NodeResult.continueWith("healthy-result")));

        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("program-1", "program-1"),
                GraphNode.behavior("program-2", "program-2"),
                GraphNode.behavior("healthy", "healthy"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "program-1"),
                GraphEdge.to("start", "program-2"),
                GraphEdge.to("start", "healthy"),
                GraphEdge.to("healthy", "end")));

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry, monitor,
                     ExecutionIdentitySource.randomUuids(), new InMemoryJoinStore(), Clock.systemUTC())) {
            var execution = runner.execute(TestIdentities.TENANT_A, "the-input").toCompletableFuture();

            assertThrows(ExecutionException.class, () -> execution.get(5, TimeUnit.SECONDS),
                    "a failure that no join owns and no route carries must fail the traversal");
        }
    }

    /**
     * The default dependency made explicit. Both graphs are identical except for <em>one property of
     * one edge</em>: whether the
     * edge into {@code ERROR} carries an outcome.
     *
     * <ul>
     *   <li>A <strong>bare</strong> edge resolves to a failure route — the current default.</li>
     *   <li>An edge carrying {@code outcome=failed} does <strong>not</strong>. That is the shape the
     *       editor generated and still generates, and it is why those graphs never
     *       fired: on a failed attempt the runner asks {@code failureEdges}, which does not select
     *       an ordinary outcome edge however it is named.</li>
     * </ul>
     *
     * <p>Asserted at the definition, where the rule lives, rather than inferred from a run: this is
     * the resolution {@code GraphRunner} consults, so a change to the default is red here directly.
     * {@code DefaultFailureRouteToErrorNodeTest} pins the same precedence more broadly; this exists
     * so the reasoning here does not depend on remembering that it does.</p>
     */
    @Test
    void routesTheBareEdgeIntoErrorOnlyBecauseOfTheDefaultIntroducedBy567() {
        var bare = twoProgramsIntoErrorTerminal();
        assertFalse(bare.failureEdges("program-1").isEmpty(),
                "a bare edge into an ERROR node IS the unhandled-failure route");

        var authoredOutcome = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("program-1", "program-1"),
                GraphNode.behavior("program-2", "program-2"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "program-1"),
                GraphEdge.to("start", "program-2"),
                new GraphEdge("program-1", "error", "failed"),
                new GraphEdge("program-2", "error", "failed"),
                GraphEdge.to("error", "end")));
        assertTrue(authoredOutcome.failureEdges("program-1").isEmpty(),
                "an edge carrying outcome=failed is an ordinary outcome edge, never a failure route "
                        + "-- this is the editor's shape and the reason it never fired");
    }

    /**
     * Graft 1, second mechanism: both nodes failed, both failures were <em>routed</em>,
     * and a routed failure is handled by construction — so {@code COMPLETED} is the correct verdict
     * and this test asserts it rather than fighting it.
     *
     * <p>This is a real shape in which the event could misreport success without naming the handled
     * failures, and the test covers it directly.</p>
     */
    @Test
    void completesWhenEveryFailedNodeHadARouteAndEveryRouteWasTaken() throws Exception {
        try (var manager = GraphManager.from(twoProgramsIntoErrorTerminal());
             var runner = new GraphRunner(manager, engine, bothProgramsRefused(), monitor,
                     ExecutionIdentitySource.randomUuids(), new InMemoryJoinStore(), Clock.systemUTC())) {
            var result = runner.execute(TestIdentities.TENANT_A, "the-input").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(Set.of("program-1", "program-2"), result.handledFailureNodes(),
                    "both program nodes failed and both failures were routed down the defaulted "
                            + "edge into the error terminal");
            assertTrue(result.visitedNodes().contains("error"),
                    "the defaulted route must actually have run: " + result.visitedNodes());

            List<ExecutionEvent> events = monitor.eventsAfter(0);
            assertEquals(2, events.stream().filter(e -> e.type() == ExecutionEventType.NODE_FAILED).count(),
                    "each node keeps its own NODE_FAILED: routing changes what happens next, never "
                            + "what happened");
            assertTrue(events.stream().anyMatch(e -> e.type() == ExecutionEventType.EXECUTION_COMPLETED),
                    "and the traversal completes, because no failure was left unhandled");
        }
    }

    /**
     * Graft 1 and the assertion that proves handled failures are published. A mutant that
     * publishes the unconditional {@code "execution completed"}: the run would still be correct and
     * the log would still say a clean run had happened.
     */
    @Test
    void namesTheHandledFailuresOnTheCompletionEventItself() throws Exception {
        try (var manager = GraphManager.from(twoProgramsIntoErrorTerminal());
             var runner = new GraphRunner(manager, engine, bothProgramsRefused(), monitor,
                     ExecutionIdentitySource.randomUuids(), new InMemoryJoinStore(), Clock.systemUTC())) {
            runner.execute(TestIdentities.TENANT_A, "the-input").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            String detail = completionDetail();
            assertNotNull(detail, "the traversal must have published a completion event");
            assertFalse("execution completed".equals(detail),
                    "a run in which two nodes failed must not publish the clean-run sentence");
            assertTrue(detail.contains("program-1") && detail.contains("program-2"),
                    "the completion event must name the nodes that failed, so a reader holding only "
                            + "the log knows what the result object knows: " + detail);
            assertTrue(detail.contains("2"),
                    "and must state how many, so a truncated list is readable as truncation rather "
                            + "than as a miscount: " + detail);
        }
    }

    /**
     * The converse on the identical graph, red under a mutant that annotates unconditionally: a run
     * in which nothing failed must publish exactly the sentence it always published. This is what
     * makes the assertion above evidence of a fault rather than of a changed string.
     */
    @Test
    void leavesTheCompletionEventUntouchedWhenNothingFailed() throws Exception {
        var registry = new BehaviorRegistry()
                .register("program-1", message -> CompletableFuture.completedFuture(
                        NodeResult.continueWith("from-1")))
                .register("program-2", message -> CompletableFuture.completedFuture(
                        NodeResult.continueWith("from-2")));

        try (var manager = GraphManager.from(twoProgramsIntoErrorTerminal());
             var runner = new GraphRunner(manager, engine, registry, monitor,
                     ExecutionIdentitySource.randomUuids(), new InMemoryJoinStore(), Clock.systemUTC())) {
            var result = runner.execute(TestIdentities.TENANT_A, "the-input").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertFalse(result.handledFailure(), "nothing failed on this run");
            assertEquals("execution completed", completionDetail(),
                    "a clean run's completion event must be byte-identical to what it always was");
        }
    }

    /**
     * Graft 3: a branch that runs out of edges on a node that is neither {@code END} nor {@code ERROR}.
     * Repeated measurements reported {@code COMPLETED} with
     * {@code payload: null} — the branch produced a value, the last node received it, and the result
     * was empty because that node's {@code kind} was not {@code END}.
     *
     * <p>Red under a mutant that drops the third terminal arrival, which was the previous behaviour.</p>
     */
    @Test
    void keepsThePayloadOfABranchThatRanOutOfEdgesOutsideEnd() throws Exception {
        var registry = new BehaviorRegistry()
                .register("stat", message -> CompletableFuture.completedFuture(
                        NodeResult.continueWith("counted")))
                .register("check", message -> CompletableFuture.completedFuture(
                        NodeResult.continueWith("checked")))
                .register("sparse-data", message -> CompletableFuture.completedFuture(
                        NodeResult.continueWith("too-few-data-points")));

        // "sparse-data" has no outgoing edge and is not kind=END. The graph declares an END node that
        // this input never reaches, which is what makes the dead end a property of the run rather
        // than of a graph with no terminal at all.
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("stat", "stat"),
                GraphNode.behavior("check", "check"),
                GraphNode.behavior("sparse-data", "sparse-data"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "stat"),
                GraphEdge.to("stat", "check"),
                GraphEdge.to("check", "sparse-data")));

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry, monitor,
                     ExecutionIdentitySource.randomUuids(), new InMemoryJoinStore(), Clock.systemUTC())) {
            var result = runner.execute(TestIdentities.TENANT_A, "3,4").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals("too-few-data-points", result.payload(),
                    "the branch reached the end of its edges and its result must survive: a payload "
                            + "is not discarded because the node it arrived at was not called END");
            assertFalse(result.visitedNodes().contains("end"),
                    "and none of this reaches the declared END node: " + result.visitedNodes());
            assertFalse(result.handledFailure(),
                    "a dead end is not a failure -- nothing failed on this run");
        }
    }

    /**
     * The precedence guard for the rank added above: a graph in which one branch dead-ends while
     * another reaches {@code END} must still report {@code END}'s payload, exactly as it did before
     * the third rank existed. Red under a mutant that ranks the dangling arrival first, which would
     * silently change what every fan-out graph with an uneven branch returns.
     */
    @Test
    void stillPrefersTheEndTerminalOverABranchThatDeadEnds() throws Exception {
        var registry = new BehaviorRegistry()
                .register("deadEnd", message -> CompletableFuture.completedFuture(
                        NodeResult.continueWith("from-the-dead-end")))
                .register("healthy", message -> CompletableFuture.completedFuture(
                        NodeResult.continueWith("from-the-healthy-branch")));

        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("deadEnd", "deadEnd"),
                GraphNode.behavior("healthy", "healthy"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "deadEnd"),
                GraphEdge.to("start", "healthy"),
                GraphEdge.to("healthy", "end")));

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry, monitor,
                     ExecutionIdentitySource.randomUuids(), new InMemoryJoinStore(), Clock.systemUTC())) {
            var result = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals("from-the-healthy-branch", result.payload(),
                    "END outranks a dead end: adding the third rank must not change what a graph "
                            + "that reaches END reports");
            assertTrue(result.visitedNodes().contains("deadEnd"),
                    "the dead-ending branch did run -- this is a precedence test, not a liveness one");
        }
    }

    /** The detail of this traversal's completion event, or {@code null} if it published none. */
    private String completionDetail() {
        return monitor.eventsAfter(0).stream()
                .filter(event -> event.type() == ExecutionEventType.EXECUTION_COMPLETED)
                .map(ExecutionEvent::detail)
                .findFirst()
                .orElse(null);
    }
}
