package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.execution.NodeFailurePayload;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.FailureRouteEdgeProperty;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure routing: the function that chooses the next edge is
 * consulted at exactly one site today, and its argument is a <em>successful</em> result. A failing
 * node records its failure and rethrows, short-circuiting composition before edge selection is ever
 * reached, so an author-declared {@code failure.route} edge is the only thing that changes that.
 *
 * <h2>What each test is pinning against a specific mutant</h2>
 * <p>{@link #dispatchesDownTheDeclaredFailureRouteWhenTheNodeFails()} is red under a mutant that
 * removes failure-edge selection: a failing node plus a failure edge must mean downstream executes.
 * {@link #neverDispatchesTheFailureRouteWhenTheNodeSucceeds()} is the converse and is red under a
 * mutant that routes unconditionally: a failure edge declared but never triggered must never fire.
 * {@link #stopsWithoutRunningEndWhenNoFailureRouteIsDeclared()} pins the unchanged case ("otherwise
 * it fails normally"): the traversal stops, but not at END. {@link
 * #leavesAnOrdinaryOutcomeEdgeNamedErrorCompletelyUnaffected()} pins that HTTP's successful {@code
 * error} outcome -- indistinguishable from a failure route only from the editor --
 * is mechanically untouched by this code, because it never takes the {@code error != null} branch.
 * {@link #annotatesTheCompletedResultWithTheNodeWhoseFailureWasHandled()} and {@link
 * #leavesTheHandledFailureAnnotationEmptyWhenNothingFailed()} are a paired check: the same graph run
 * both ways, red under a mutant that drops the annotation and under one that fabricates it.
 * {@link #annotatesAQuorumThatWasMetOverABranchThatFailed()} covers the other populating
 * mechanism, which has no failure route in it at all: a {@code k of n} join that met its quorum over
 * a branch that failed (CORE-03).</p>
 *
 * <h2>The same three questions, asked of a route nobody declared</h2>
 * <p>{@link #routesAFailureDownABareEdgeIntoTheErrorTerminal()},
 * {@link #neverTakesTheBareEdgeIntoTheErrorTerminalWhenTheNodeSucceeds()} and
 * {@link #leavesANodeWithOnlyAnExplicitFailedOutcomeIntoErrorWithNoRouteAtAll()} are the
 * end-to-end counterparts of {@code DefaultFailureRouteToErrorNodeTest}, which pins the same
 * precedence at the definition. Both levels are kept: the definition test says what the graph
 * <em>resolves</em>, and these say what actually runs, and a rule that held only in the first place
 * would be a rule the engine does not obey.</p>
 */
class GraphRunnerFailureRouteTest {

    private final JoinTestEngine engine = new JoinTestEngine();
    private final ExecutionMonitor monitor = new ExecutionMonitor();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    /**
     * The shared topology for the success/failure pair below. {@code boom} either completes or
     * fails; either way its result reaches the single END node through a rejoin whose quorum is one,
     * so the branch that {@code boom} did not take -- proven dead by the same liveness accounting an
     * ordinary outcome uses -- does not stall the traversal (CORE-03). This is also the join-
     * accounting proof the architect's ruling asked for its own test: a live branch and a dead branch
     * feeding one quorum-of-one join, decided correctly whichever of the two {@code boom} takes.
     */
    private static GraphDefinition boomGraph() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("boom", "boom"),
                GraphNode.behavior("normalNext", "normalNext"),
                GraphNode.behavior("handler", "handler"),
                new GraphNode("rejoin", NodeKind.PASSTHROUGH, null, JoinMiniGraphs.quorum(1)),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "boom"),
                GraphEdge.to("boom", "normalNext"),
                new GraphEdge("boom", "handler", null,
                        Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE)),
                GraphEdge.to("normalNext", "rejoin"),
                GraphEdge.to("handler", "rejoin"),
                GraphEdge.to("rejoin", "end")));
    }

    @Test
    void dispatchesDownTheDeclaredFailureRouteWhenTheNodeFails() throws Exception {
        var failure = new IllegalStateException("boom exploded");
        var receivedPayload = new AtomicReference<Object>();
        var registry = new BehaviorRegistry();
        registry.register("boom", message -> CompletableFuture.failedFuture(failure));
        registry.register("normalNext", message -> CompletableFuture.completedFuture(
                NodeResult.continueWith("from-normalNext")));
        registry.register("handler", message -> {
            receivedPayload.set(message.payload());
            return CompletableFuture.completedFuture(NodeResult.continueWith("handled"));
        });

        var store = new InMemoryJoinStore();
        try (var manager = GraphManager.from(boomGraph());
             var runner = new GraphRunner(manager, engine, registry, monitor,
                     ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC())) {
            var result = runner.execute(TestIdentities.TENANT_A, "the-input").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals("handled", result.payload(), "the failure route's own downstream result must win");
            assertEquals(Set.of("start", "boom", "handler", "rejoin", "end"), result.visitedNodes(),
                    "the failure route must actually run, and normalNext -- the branch boom did not "
                            + "take -- must not");
            assertEquals(0, store.totalRecordCount(), "the rejoin's join record must not survive the traversal");
            assertEquals(0, runner.liveCoordinatorCount());

            assertTrue(receivedPayload.get() instanceof NodeFailurePayload,
                    "the failure route's documented payload shape, never a fabricated result: got "
                            + receivedPayload.get());
            var payload = (NodeFailurePayload) receivedPayload.get();
            assertEquals("boom", payload.nodeId());
            assertEquals(IllegalStateException.class.getName(), payload.errorClass());
            assertEquals("boom exploded", payload.message());
            assertEquals("the-input", payload.input(), "the input boom itself received, forwarded verbatim");
        }
    }

    @Test
    void neverDispatchesTheFailureRouteWhenTheNodeSucceeds() throws Exception {
        var handlerRan = new AtomicReference<Boolean>(false);
        var registry = new BehaviorRegistry();
        registry.register("boom", message -> CompletableFuture.completedFuture(
                NodeResult.continueWith("from-boom")));
        registry.register("normalNext", message -> CompletableFuture.completedFuture(
                NodeResult.continueWith("from-normalNext")));
        registry.register("handler", message -> {
            handlerRan.set(true);
            return CompletableFuture.completedFuture(NodeResult.continueWith("handled"));
        });

        var store = new InMemoryJoinStore();
        try (var manager = GraphManager.from(boomGraph());
             var runner = new GraphRunner(manager, engine, registry, monitor,
                     ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC())) {
            var result = runner.execute(TestIdentities.TENANT_A, "the-input").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertFalse(handlerRan.get(), "a declared failure route must never fire on a successful attempt");
            assertEquals("from-normalNext", result.payload());
            assertEquals(Set.of("start", "boom", "normalNext", "rejoin", "end"), result.visitedNodes(),
                    "handler -- the failure route -- must not run when boom succeeds");
            assertEquals(0, store.totalRecordCount());
            assertEquals(0, runner.liveCoordinatorCount());
        }
    }

    /**
     * The paired check: the two runs below execute the <em>same
     * graph</em> and differ only in whether {@code boom} throws. Everything a caller reads about the
     * ending agrees -- both complete, both reach END -- so if the result did not carry the
     * annotation, a reader holding only the result could not tell a traversal that suffered a real
     * fault from one that did not. They are written as one test each rather than one combined test
     * so that a mutant which drops the annotation and a mutant which fabricates one unconditionally
     * are each red on their own.
     *
     * <p>This asserts <em>alongside</em> the success, never instead of it: option 3, not option 2.
     * {@link #dispatchesDownTheDeclaredFailureRouteWhenTheNodeFails()} still gets its result from a
     * plain {@code get()}, and so does this.</p>
     */
    @Test
    void annotatesTheCompletedResultWithTheNodeWhoseFailureWasHandled() throws Exception {
        var registry = new BehaviorRegistry();
        registry.register("boom", message -> CompletableFuture.failedFuture(
                new IllegalStateException("boom exploded")));
        registry.register("normalNext", message -> CompletableFuture.completedFuture(
                NodeResult.continueWith("from-normalNext")));
        registry.register("handler", message -> CompletableFuture.completedFuture(
                NodeResult.continueWith("handled")));

        try (var manager = GraphManager.from(boomGraph());
             var runner = new GraphRunner(manager, engine, registry, monitor,
                     ExecutionIdentitySource.randomUuids(), new InMemoryJoinStore(), Clock.systemUTC())) {
            var result = runner.execute(TestIdentities.TENANT_A, "the-input").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertTrue(result.handledFailure(),
                    "the result must say a fault occurred and was handled, not merely that the run ended");
            assertEquals(Set.of("boom"), result.handledFailureNodes(),
                    "and must name the node that failed -- boom, never the handler that recovered from it");
        }
    }

    /** The converse of the test above, on the identical graph: a clean run annotates nothing. */
    @Test
    void leavesTheHandledFailureAnnotationEmptyWhenNothingFailed() throws Exception {
        var registry = new BehaviorRegistry();
        registry.register("boom", message -> CompletableFuture.completedFuture(
                NodeResult.continueWith("from-boom")));
        registry.register("normalNext", message -> CompletableFuture.completedFuture(
                NodeResult.continueWith("from-normalNext")));
        registry.register("handler", message -> CompletableFuture.completedFuture(
                NodeResult.continueWith("handled")));

        try (var manager = GraphManager.from(boomGraph());
             var runner = new GraphRunner(manager, engine, registry, monitor,
                     ExecutionIdentitySource.randomUuids(), new InMemoryJoinStore(), Clock.systemUTC())) {
            var result = runner.execute(TestIdentities.TENANT_A, "the-input").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertFalse(result.handledFailure(),
                    "a run in which nothing failed must not claim a handled failure");
            assertEquals(Set.of(), result.handledFailureNodes());
        }
    }

    /**
     * The <em>second</em> mechanism {@code handledFailureNodes()}'s javadoc promises, and the one no
     * test covered until now: a {@code k of n} fan-in whose quorum was met over a branch that failed
     * (CORE-03). No failure route is declared anywhere in this graph -- the join alone is what makes
     * the traversal survive {@code b1}, which is why this is not a second copy of the test above.
     *
     * <p>Ordered rather than raced. {@code b0} is held on a pending future until {@code b1}'s
     * {@code NODE_FAILED} has been published, so the quorum is provably satisfied <em>after</em> the
     * branch failed rather than before it, and the annotation is not being read from a traversal that
     * happened to finish first. Without the ordering the test would still pass on most runs, which is
     * exactly the kind of test that later fails on a loaded machine and gets blamed on flakiness.</p>
     */
    @Test
    void annotatesAQuorumThatWasMetOverABranchThatFailed() throws Exception {
        var survivor = new CompletableFuture<NodeResult>();
        var registry = new BehaviorRegistry();
        registry.register("b0", message -> survivor);
        registry.register("b1", message -> CompletableFuture.failedFuture(
                new IllegalStateException("b1 exploded")));

        var store = new InMemoryJoinStore();
        try (var manager = GraphManager.from(JoinMiniGraphs.fanIn(2, JoinMiniGraphs.quorum(1)));
             var runner = new GraphRunner(manager, engine, registry, monitor,
                     ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC())) {
            var execution = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture();

            awaitFailureOf("b1");
            survivor.complete(NodeResult.continueWith("from-b0"));
            var result = execution.get(5, TimeUnit.SECONDS);

            assertTrue(result.visitedNodes().contains("end"),
                    "the quorum must have carried the traversal to END despite the failed branch: "
                            + result.visitedNodes());
            assertTrue(result.handledFailure(),
                    "a quorum met over a failed branch is a fault the run survived, not a clean run");
            assertEquals(Set.of("b1"), result.handledFailureNodes(),
                    "and must name the branch that failed, never the branch that carried the quorum");
        }
    }

    /** Blocks until the runtime has published {@code node}'s failure, so ordering is asserted, not hoped for. */
    private void awaitFailureOf(String node) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (monitor.eventsAfter(0).stream().anyMatch(event -> event.type() == ExecutionEventType.NODE_FAILED
                    && node.equals(event.nodeId()))) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("no NODE_FAILED event was ever published for " + node);
    }

    /**
     * The unchanged case, now specified rather than merely expected: a failed traversal with no
     * declared failure route stops, but not
     * at END -- no end node runs, no result payload is set, and the read reports failure.
     */
    @Test
    void stopsWithoutRunningEndWhenNoFailureRouteIsDeclared() throws Exception {
        var failure = new IllegalStateException("no route for this one");
        var registry = new BehaviorRegistry().register("boom",
                message -> CompletableFuture.failedFuture(failure));

        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.behavior("boom", "boom"), GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "boom"), GraphEdge.to("boom", "end")));

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry, monitor)) {
            var execution = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture();

            var error = assertThrows(ExecutionException.class, () -> execution.get(5, TimeUnit.SECONDS));
            assertNotNull(unwrapTo(error, IllegalStateException.class),
                    "the original failure must still be the one the read surfaces: " + error);

            List<ExecutionEvent> events = monitor.eventsAfter(0);
            assertTrue(events.stream().anyMatch(e -> e.type() == ExecutionEventType.NODE_FAILED
                    && "boom".equals(e.nodeId())), "boom's own failure must still be recorded unconditionally");
            assertFalse(events.stream().anyMatch(e -> "end".equals(e.nodeId())),
                    "no end node runs: the traversal stops at the failure, not at END");
        }
    }

    /**
     * HTTP's {@code error} outcome is a <em>successful</em> result carrying a different outcome for a
     * non-2xx response -- never the {@code error != null} branch handled here. This reproduces
     * that shape structurally (a node that completes with outcome {@code "error"}) and pins that
     * ordinary outcome-edge selection is mechanically unaffected by failure routing.
     */
    @Test
    void leavesAnOrdinaryOutcomeEdgeNamedErrorCompletelyUnaffected() throws Exception {
        var registry = new BehaviorRegistry();
        registry.register("httpish", message ->
                CompletableFuture.completedFuture(new NodeResult("error", "http-error-body", Map.of())));
        registry.register("errorHandler", message ->
                CompletableFuture.completedFuture(NodeResult.continueWith("handled-" + message.payload())));

        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"), GraphNode.behavior("httpish", "httpish"),
                GraphNode.behavior("errorHandler", "errorHandler"), GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "httpish"),
                new GraphEdge("httpish", "errorHandler", "error"),
                GraphEdge.to("errorHandler", "end")));

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry, monitor)) {
            var result = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals("handled-http-error-body", result.payload(),
                    "a node that succeeds with outcome=error must still select its outcome edge exactly "
                            + "as before: this path never reaches the failure-routing code at all");
            assertEquals(Set.of("start", "httpish", "errorHandler", "end"), result.visitedNodes());
        }
    }

    /**
     * The same topology as {@link #boomGraph()}, except that the failure route is not declared
     * anywhere: {@code boom} simply has a bare edge into the {@code ERROR} terminal, and default
     * failure routing is the only thing that can make it fire. The error terminal continues into the rejoin,
     * which is the shape error-terminal routing contract describes and the shipped programmable example now draws.
     */
    private static GraphDefinition bareEdgeToErrorGraph() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("boom", "boom"),
                GraphNode.behavior("normalNext", "normalNext"),
                new GraphNode("rejoin", NodeKind.PASSTHROUGH, null, JoinMiniGraphs.quorum(1)),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "boom"),
                GraphEdge.to("boom", "normalNext"),
                GraphEdge.to("boom", "error"),
                GraphEdge.to("normalNext", "rejoin"),
                GraphEdge.to("error", "rejoin"),
                GraphEdge.to("rejoin", "end")));
    }

    /**
     * Direction 1 at the runner: a bare edge into {@code ERROR} routes an unhandled failure with
     * nothing declared on it. Red under a mutant that drops the default, because {@code error} would
     * never be visited and the traversal would end in an exception instead.
     */
    @Test
    void routesAFailureDownABareEdgeIntoTheErrorTerminal() throws Exception {
        var registry = new BehaviorRegistry();
        registry.register("boom", message -> CompletableFuture.failedFuture(
                new IllegalStateException("boom exploded")));
        registry.register("normalNext", message -> CompletableFuture.completedFuture(
                NodeResult.continueWith("from-normalNext")));

        try (var manager = GraphManager.from(bareEdgeToErrorGraph());
             var runner = new GraphRunner(manager, engine, registry, monitor,
                     ExecutionIdentitySource.randomUuids(), new InMemoryJoinStore(), Clock.systemUTC())) {
            var result = runner.execute(TestIdentities.TENANT_A, "the-input").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(Set.of("start", "boom", "error", "rejoin", "end"), result.visitedNodes(),
                    "the error terminal must have run on the undeclared route, and normalNext -- the "
                            + "branch boom did not take -- must not");
            assertTrue(result.handledFailure(),
                    "a defaulted route is a route: the fault was handled, not merely survived");
            assertEquals(Set.of("boom"), result.handledFailureNodes());
        }
    }

    /**
     * Direction 3 at the runner, and the converse of the test above on the identical graph: the
     * same bare edge must never fire on a success. Red under a mutant that defaults the edge into a
     * failure route but forgets to exclude it from ordinary outcome selection — which is the failure
     * mode of implementing the rule anywhere other than in the definition, since {@code nextEdges}
     * would keep selecting it for a plain {@code continue} and the error terminal would fire on a
     * clean run.
     */
    @Test
    void neverTakesTheBareEdgeIntoTheErrorTerminalWhenTheNodeSucceeds() throws Exception {
        var registry = new BehaviorRegistry();
        registry.register("boom", message -> CompletableFuture.completedFuture(
                NodeResult.continueWith("from-boom")));
        registry.register("normalNext", message -> CompletableFuture.completedFuture(
                NodeResult.continueWith("from-normalNext")));

        try (var manager = GraphManager.from(bareEdgeToErrorGraph());
             var runner = new GraphRunner(manager, engine, registry, monitor,
                     ExecutionIdentitySource.randomUuids(), new InMemoryJoinStore(), Clock.systemUTC())) {
            var result = runner.execute(TestIdentities.TENANT_A, "the-input").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(Set.of("start", "boom", "normalNext", "rejoin", "end"), result.visitedNodes(),
                    "a clean run must not touch the error terminal");
            assertEquals("from-normalNext", result.payload());
            assertFalse(result.handledFailure());
        }
    }

    /**
     * Direction 2 at the runner, backed by measurement: an edge that declares an
     * outcome into {@code ERROR} stays an outcome edge, so a node that <em>throws</em> still has no
     * route. This is the exact shape the two shipped examples carried — {@code outcome=failed} into
     * the error terminal — and this test is what says, in code, that the shape never fired: an
     * exception produces no outcome for {@code failed} to match, so the traversal stops as if nothing
     * had been wired at all.
     *
     * <p>It is therefore also the regression guard for the correction: were someone to restore
     * {@code outcome=failed} on those examples, this is the behaviour they would be restoring.</p>
     */
    @Test
    void leavesANodeWithOnlyAnExplicitFailedOutcomeIntoErrorWithNoRouteAtAll() throws Exception {
        var registry = new BehaviorRegistry().register("boom",
                message -> CompletableFuture.failedFuture(new IllegalStateException("boom exploded")));

        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"), GraphNode.behavior("boom", "boom"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "boom"),
                GraphEdge.to("boom", "end"),
                new GraphEdge("boom", "error", "failed")));

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry, monitor)) {
            var execution = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture();

            var error = assertThrows(ExecutionException.class, () -> execution.get(5, TimeUnit.SECONDS));
            assertNotNull(unwrapTo(error, IllegalStateException.class),
                    "an explicit outcome is not a failure route, so the failure still surfaces: " + error);

            List<ExecutionEvent> events = monitor.eventsAfter(0);
            assertFalse(events.stream().anyMatch(e -> "error".equals(e.nodeId())),
                    "the error terminal must not have run: no outcome exists for `failed` to select");
        }
    }

    private static <T extends Throwable> T unwrapTo(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
