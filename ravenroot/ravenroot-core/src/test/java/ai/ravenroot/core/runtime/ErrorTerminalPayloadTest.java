package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.execution.NodeFailurePayload;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.FailureRouteEdgeProperty;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a traversal that ends on the error terminal reports as its result payload.
 *
 * <h2>The defect these are red against</h2>
 * <p>{@code GraphRunner} assigned {@code state.resultPayload} at exactly one site, guarded by
 * {@code node.kind() == NodeKind.END} — measured by
 * {@code grep -rn 'resultPayload' --include='*.java' ravenroot/}, which reports three lines: the
 * declaration, that one assignment, and the read that builds {@code GraphExecutionResult}. With
 * {@code END} the only terminal that could ever be reached, the guard and "the traversal ended" were
 * the same condition. {@code ERROR} makes them two conditions, and an END-only assignment makes both tests below
 * report {@code expected: <...> but was: <null>}: a traversal that routed its failure exactly as its
 * author drew it returned no payload at all, indistinguishable from a node that produced none.</p>
 *
 * <h2>What is decided here, and what is not</h2>
 * <p>Decided: the result payload is whatever reached the terminal, whichever terminal that is. The
 * runner already carries that value in hand at the assignment site; withholding it on {@code ERROR}
 * would be a deliberate discard of the one piece of evidence describing how the run ended, and the
 * failure route's own payload contract ({@link NodeFailurePayload}) exists precisely so that evidence
 * is structured rather than lost.</p>
 *
 * <p><strong>Not decided here:</strong> whether reaching {@code ERROR}
 * makes the execution a <em>functional</em> failure, and whether the result should carry a new field
 * naming the terminal it reached. Both tests below therefore assert the payload and say nothing about
 * the execution's status: {@link #carriesTheArrivingPayloadWhenNothingFailed()} covers a node that
 * completed correctly with a negative outcome, and it is written so that
 * either possible status rule leaves it green.</p>
 */
class ErrorTerminalPayloadTest {

    /**
     * How many times the race test below runs the same traversal. Forty is what the two measurements
     * quoted in its Javadoc used, so the number is the one the evidence was gathered at rather than a
     * round figure chosen afterwards. A race this test could miss at forty it would also miss at four
     * hundred often enough to be worth reporting; what forty buys is that both defects it was written
     * against showed a split well inside it.
     */
    private static final int RACE_REPEATS = 40;

    private final JoinTestEngine engine = new JoinTestEngine();
    private final ExecutionMonitor monitor = new ExecutionMonitor();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    /**
     * {@code boom} fails and its author-declared failure route carries it to the error terminal;
     * {@code end} is never reached. The payload that arrives at {@code error} is the structured
     * failure payload, and that is what the result must report.
     */
    @Test
    void carriesTheFailurePayloadThatReachedTheErrorTerminal() throws Exception {
        var registry = new BehaviorRegistry().register("boom",
                message -> CompletableFuture.failedFuture(new IllegalStateException("boom exploded")));

        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("boom", "boom"),
                GraphNode.error("error"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "boom"),
                GraphEdge.to("boom", "end"),
                new GraphEdge("boom", "error", null,
                        Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE))));

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry, monitor)) {
            var result = runner.execute(TestIdentities.TENANT_A, "the-input").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertNotNull(result.payload(),
                    "a traversal that ended on the error terminal must not report a null payload: "
                            + "END was the only terminal that assigned one");
            var failure = assertInstanceOf(NodeFailurePayload.class, result.payload());
            assertEquals("boom", failure.nodeId());
            assertEquals(Set.of("start", "boom", "error"), result.visitedNodes(),
                    "end must not run: the failure route replaced the branch boom did not take");
            assertTrue(result.handledFailure());
            assertEquals(Set.of("boom"), result.handledFailureNodes());
        }
    }

    /**
     * The result payload is a function of the graph, not of the scheduler.
     *
     * <p><strong>Repeated deliberately.</strong> This defect is invisible in a single execution: the
     * traversal below completes every time, and any one run returns a payload that looks perfectly
     * reasonable. Only the distribution over repeats reveals an answer selected by whichever branch
     * happens to finish last; a one-shot assertion cannot distinguish that race from determinism.</p>
     *
     * <p>Measured against the two-writer version of {@code state.resultPayload}, forty runs of this
     * exact graph returned three different payloads:
     * {@code {END-PAYLOAD=5, ERROR(first)=20, ERROR(second)=15}}. Against a compare-and-set variant —
     * "the first terminal to arrive wins" — the same forty returned
     * {@code {END-PAYLOAD=33, ERROR(first)=7}}: the odds moved, the race did not go away. Both counts
     * come from running it, not from reading the code.</p>
     *
     * <p>What is pinned is the invariant, not a tally: a traversal that reaches {@code END} reports
     * {@code END}'s payload, every time. That is also the earlier outcome, when {@code END} was the
     * only writer — so this asserts that adding another terminal left the defined case unchanged.</p>
     */
    @Test
    void reportsTheEndPayloadOnEveryRunWhenBothTerminalsAreReached() throws Exception {
        var registry = new BehaviorRegistry()
                .register("ok", message -> CompletableFuture.completedFuture(
                        NodeResult.continueWith("END-PAYLOAD")))
                .register("first", message -> CompletableFuture.failedFuture(
                        new IllegalStateException("boom-1")))
                .register("second", message -> CompletableFuture.failedFuture(
                        new IllegalStateException("boom-2")));

        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("ok", "ok"),
                GraphNode.behavior("first", "first"),
                GraphNode.behavior("second", "second"),
                GraphNode.error("error"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "ok"),
                GraphEdge.to("start", "first"),
                GraphEdge.to("start", "second"),
                GraphEdge.to("ok", "end"),
                new GraphEdge("first", "error", null,
                        Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE)),
                new GraphEdge("second", "error", null,
                        Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE))));

        var observed = new java.util.TreeSet<String>();
        for (int run = 0; run < RACE_REPEATS; run++) {
            try (var manager = GraphManager.from(graph);
                 var runner = new GraphRunner(manager, engine, registry, monitor)) {
                var result = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                observed.add(String.valueOf(result.payload()));
                assertTrue(result.visitedNodes().contains("error"),
                        "the graph must really reach both terminals, or this proves nothing: "
                                + result.visitedNodes());
            }
        }

        assertEquals(java.util.Set.of("END-PAYLOAD"), observed,
                "the result payload must not depend on which branch finished last");
    }

    /**
     * The consequence of "exactly one error terminal" that only shows up with two fallible nodes:
     * both must route to the same node, which makes that node a fan-in.
     *
     * <p>Red before {@code JoinSpec.defaultQuorum} existed, and not as an assertion failure — the
     * traversal failed outright with
     * {@code JoinFailureException: Join 'error' can never reach its quorum, because branches it needs
     * are never taken: quorum 2 of 2 branches, arrived=[] failed=[] outstanding=[first]
     * notTaken=[second]}. Under the ordinary fan-in default the error terminal waited for every
     * fallible node in the graph to fail, so the shape the product now requires could not run at all
     * past a single fallible node.</p>
     */
    @Test
    void firesOnTheFirstFailureWhenSeveralFallibleNodesShareTheOneErrorTerminal() throws Exception {
        var registry = new BehaviorRegistry()
                .register("first", message -> CompletableFuture.failedFuture(new IllegalStateException("boom")))
                .register("second", message -> CompletableFuture.completedFuture(
                        NodeResult.continueWith("second-ok")));

        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("first", "first"),
                GraphNode.behavior("second", "second"),
                GraphNode.error("error"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "first"),
                GraphEdge.to("first", "second"),
                GraphEdge.to("second", "end"),
                new GraphEdge("first", "error", null,
                        Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE)),
                new GraphEdge("second", "error", null,
                        Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE))));

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry, monitor)) {
            var result = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(Set.of("start", "first", "error"), result.visitedNodes(),
                    "the terminal must fire on the branch that actually failed, not wait for the "
                            + "branch that was never taken");
            assertInstanceOf(NodeFailurePayload.class, result.payload());
            assertEquals(Set.of("first"), result.handledFailureNodes());
        }
    }

    /**
     * The case both of the tests above look like they cover and neither touches: <strong>two branches
     * that both really fail, with {@code END} never reached</strong>.
     *
     * <p>Worth recording why the gap existed, because it is a shape that recurs.
     * {@link #firesOnTheFirstFailureWhenSeveralFallibleNodesShareTheOneErrorTerminal()} has two
     * fallible nodes but only <em>one</em> real failure — {@code second} is downstream of
     * {@code first} and is never reached — and
     * {@link #reportsTheEndPayloadOnEveryRunWhenBothTerminalsAreReached()} has two real failures but
     * {@code END} wins the precedence and <em>masks</em> whatever the error terminal did. Two tests
     * that each cover part of the shape, and between them a case neither one enters.</p>
     *
     * <p>What is pinned here is everything that is actually invariant: the terminal fires exactly
     * once, exactly one arrival is discarded and says so, and the payload is one of the two failures.
     * <strong>Which</strong> of the two is deliberately not asserted, because it is not invariant —
     * measured over two hundred traversals of this graph: {@code {first=175, second=25}}. That is
     * expected nondeterminism, not a defect in this scope, and asserting either answer would pin a
     * scheduler-dependent coin toss.</p>
     */
    @Test
    void firesOnceAndReportsOneOfTheTwoWhenBothBranchesFailAndEndIsNeverReached() throws Exception {
        var registry = new BehaviorRegistry()
                .register("first", message -> CompletableFuture.failedFuture(
                        new IllegalStateException("boom-1")))
                .register("second", message -> CompletableFuture.failedFuture(
                        new IllegalStateException("boom-2")));

        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("first", "first"),
                GraphNode.behavior("second", "second"),
                GraphNode.error("error"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "first"),
                GraphEdge.to("start", "second"),
                new GraphEdge("first", "error", null,
                        Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE)),
                new GraphEdge("second", "error", null,
                        Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE))));

        for (int run = 0; run < RACE_REPEATS; run++) {
            var localMonitor = new ExecutionMonitor();
            try (var manager = GraphManager.from(graph);
                 var runner = new GraphRunner(manager, engine, registry, localMonitor)) {
                var result = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);

                assertTrue(result.visitedNodes().contains("error")
                                && !result.visitedNodes().contains("end"),
                        () -> "this case requires both failures to route and END never to run: "
                                + result.visitedNodes());
                var failure = assertInstanceOf(NodeFailurePayload.class, result.payload());
                assertTrue(Set.of("first", "second").contains(failure.nodeId()),
                        () -> "the payload must come from one of the two failures, got " + failure.nodeId());

                var events = localMonitor.eventsAfter(0);
                assertEquals(1, events.stream()
                                .filter(event -> event.type() == ExecutionEventType.NODE_COMPLETED
                                        && "error".equals(event.nodeId()))
                                .count(),
                        "the one error terminal must fire exactly once however many branches arrive");
                assertEquals(1, events.stream()
                                .filter(event -> event.type() == ExecutionEventType.JOIN_ARRIVAL_DISCARDED
                                        && "error".equals(event.nodeId()))
                                .count(),
                        "and the arrival it did not take must be recorded as discarded, not dropped");
            }
        }
    }

    /**
     * The non-failing scenario, asserted only as far as this test can answer it: {@code probe}
     * completes correctly and produces a negative outcome, whose edge the author drew to the error
     * terminal. Nothing failed, so the traversal reaches {@code ERROR} with an ordinary payload — and
     * that payload, not {@code null}, is the result.
     */
    @Test
    void carriesTheArrivingPayloadWhenNothingFailed() throws Exception {
        var registry = new BehaviorRegistry().register("probe",
                message -> CompletableFuture.completedFuture(new NodeResult("rejected", "http-503", Map.of())));

        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("probe", "probe"),
                GraphNode.error("error"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "probe"),
                new GraphEdge("probe", "end", "accepted", Map.of()),
                new GraphEdge("probe", "error", "rejected", Map.of())));

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry, monitor)) {
            var result = runner.execute(TestIdentities.TENANT_A, "the-input").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals("http-503", result.payload(),
                    "the payload that reached the terminal is the result, whichever terminal it was");
            assertEquals(Set.of("start", "probe", "error"), result.visitedNodes());
            assertTrue(result.handledFailureNodes().isEmpty(),
                    "nothing failed: reaching the error terminal is not by itself a handled failure");
        }
    }
}
