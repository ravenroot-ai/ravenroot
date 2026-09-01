package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.catalog.NodeBypassProperty;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.FailureRouteEdgeProperty;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Authoritative edge observations are produced at actual successor dispatch, never inferred later. */
class EdgeTraversalRuntimeTest {
    private final JoinTestEngine engine = new JoinTestEngine();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    @Test
    void emitsOneStableEventForEveryUnambiguousParallelDispatch() throws Exception {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"), GraphNode.passthrough("left"), GraphNode.passthrough("right"),
                GraphNode.passthrough("join"), GraphNode.error("error"), GraphNode.end("end")), List.of(
                edge("start-left", "start", "left"), edge("start-right", "start", "right"),
                edge("left-join", "left", "join"), edge("right-join", "right", "join"),
                edge("join-end", "join", "end")));

        List<ExecutionEvent> events = run(graph, new BehaviorRegistry());
        List<ExecutionEvent> traversals = traversals(events);

        assertEquals(Set.of("start-left", "start-right", "left-join", "right-join", "join-end"),
                traversals.stream().map(ExecutionEvent::edgeId).collect(Collectors.toSet()));
        assertEquals(5, traversals.size(), "parallel routes are counted per actual successor dispatch");
        traversals.forEach(event -> {
            assertEquals("continue", event.publicReason());
            assertTrue(event.sequence() > completionSequence(events, event.nodeId()),
                    "an edge observation follows its source completion: " + event.edgeId());
        });
    }

    @Test
    void whitespaceDistinctIdsMapOnlyToTheirExactParallelDispatches() throws Exception {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"), GraphNode.passthrough("left"), GraphNode.passthrough("right"),
                new GraphNode("end", NodeKind.END, null, JoinMiniGraphs.quorum(1)), GraphNode.error("error")),
                List.of(edge("edge", "start", "left"), edge(" edge ", "start", "right"),
                        edge("left-end", "left", "end"), edge("right-end", "right", "end")));

        List<String> observed = traversals(run(graph, new BehaviorRegistry())).stream()
                .map(ExecutionEvent::edgeId).filter(id -> id.trim().equals("edge")).toList();

        assertEquals(Set.of("edge", " edge "), Set.copyOf(observed));
        assertEquals(2, observed.size(), "each exact identity must be emitted once without normalization");
    }

    @Test
    void aCollapsedDuplicateRouteDoesNotFabricateOneOfTwoAuthoredEdgeIds() throws Exception {
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), GraphNode.passthrough("work"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                edge("duplicate-a", "start", "work"), edge("duplicate-b", "start", "work"),
                edge("work-end", "work", "end")));

        List<String> observed = traversals(run(graph, new BehaviorRegistry())).stream()
                .map(ExecutionEvent::edgeId).toList();

        assertEquals(List.of("work-end"), observed,
                "the one delivery to work cannot honestly be attributed to either duplicate edge");
    }

    @Test
    void handledFailureRoutingEmitsTheSelectedFailureEdgeAndNoOrdinarySibling() throws Exception {
        var graph = new GraphDefinition(List.of(GraphNode.start("start"),
                GraphNode.behavior("boom", "boom"), GraphNode.passthrough("ordinary"),
                GraphNode.passthrough("handler"),
                new GraphNode("rejoin", NodeKind.PASSTHROUGH, null, JoinMiniGraphs.quorum(1)),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                edge("start-boom", "start", "boom"), edge("ordinary-route", "boom", "ordinary"),
                new GraphEdge("boom", "handler", "continue",
                        Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE), "failure-route"),
                edge("handler-rejoin", "handler", "rejoin"), edge("ordinary-rejoin", "ordinary", "rejoin"),
                edge("rejoin-end", "rejoin", "end")));
        var behaviors = new BehaviorRegistry().register("boom",
                message -> CompletableFuture.failedFuture(new IllegalStateException("expected")));

        Set<String> observed = traversals(run(graph, behaviors)).stream()
                .map(ExecutionEvent::edgeId).collect(Collectors.toSet());

        assertTrue(observed.contains("failure-route"));
        assertTrue(!observed.contains("ordinary-route"));
    }

    @Test
    void commandBypassStillReportsItsRealOutgoingDispatch() throws Exception {
        var graph = new GraphDefinition(List.of(GraphNode.start("start"),
                GraphNode.behavior("worker", "unused"), GraphNode.error("error"), GraphNode.end("end")), List.of(
                new GraphEdge("start", "worker", "continue", Map.of("command", "passthrough"), "to-worker"),
                edge("worker-end", "worker", "end")));

        List<ExecutionEvent> events = run(graph, new BehaviorRegistry());

        assertTrue(events.stream().anyMatch(event -> event.type() == ExecutionEventType.NODE_BYPASSED
                && "worker".equals(event.nodeId())));
        assertEquals(Set.of("to-worker", "worker-end"), traversals(events).stream()
                .map(ExecutionEvent::edgeId).collect(Collectors.toSet()));
    }

    @Test
    void authoredBypassReportsItsDefaultOutgoingDispatchAndNoNamedBranch() throws Exception {
        var graph = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("worker", NodeKind.BEHAVIOR, "unused",
                        Map.of(NodeBypassProperty.NAME, "true")),
                GraphNode.passthrough("approved"), GraphNode.error("error"),
                new GraphNode("end", NodeKind.END, null, JoinMiniGraphs.quorum(1))), List.of(
                edge("to-worker", "start", "worker"),
                new GraphEdge("worker", "approved", "approved", Map.of(), "approved-route"),
                edge("worker-end", "worker", "end"), edge("approved-end", "approved", "end")));

        List<ExecutionEvent> events = run(graph, new BehaviorRegistry());

        assertTrue(events.stream().anyMatch(event -> event.type() == ExecutionEventType.NODE_BYPASSED
                && ExecutionEvent.BYPASS_REASON_AUTHORED.equals(event.publicReason())));
        assertEquals(Set.of("to-worker", "worker-end"), traversals(events).stream()
                .map(ExecutionEvent::edgeId).collect(Collectors.toSet()));
    }

    @Test
    void customOutcomeReportsOnlyTheSelectedStableEdgeIdentity() throws Exception {
        var graph = new GraphDefinition(List.of(GraphNode.start("start"),
                GraphNode.behavior("decision", "decision"), GraphNode.passthrough("approved"),
                GraphNode.passthrough("rejected"), GraphNode.error("error"),
                new GraphNode("end", NodeKind.END, null, JoinMiniGraphs.quorum(1))), List.of(
                edge("to-decision", "start", "decision"),
                new GraphEdge("decision", "approved", "approved", Map.of(), "approved-route"),
                new GraphEdge("decision", "rejected", "rejected", Map.of(), "rejected-route"),
                edge("approved-end", "approved", "end"), edge("rejected-end", "rejected", "end")));
        var behaviors = new BehaviorRegistry().register("decision", message ->
                CompletableFuture.completedFuture(new NodeResult("approved", message.payload(), Map.of())));

        List<ExecutionEvent> events = run(graph, behaviors);
        ExecutionEvent selected = traversals(events).stream()
                .filter(event -> "approved-route".equals(event.edgeId())).findFirst().orElseThrow();

        assertEquals("approved", selected.publicReason());
        assertTrue(traversals(events).stream().noneMatch(event -> "rejected-route".equals(event.edgeId())));
    }

    @Test
    void defaultFailureRouteReportsTheBareErrorEdgeAndNoOrdinarySibling() throws Exception {
        var graph = new GraphDefinition(List.of(GraphNode.start("start"),
                GraphNode.behavior("boom", "boom"), GraphNode.passthrough("ordinary"),
                GraphNode.error("error"),
                new GraphNode("end", NodeKind.END, null, JoinMiniGraphs.quorum(1))), List.of(
                edge("start-boom", "start", "boom"), edge("ordinary-route", "boom", "ordinary"),
                edge("default-failure-route", "boom", "error"),
                edge("ordinary-end", "ordinary", "end"), edge("error-end", "error", "end")));
        var behaviors = new BehaviorRegistry().register("boom",
                message -> CompletableFuture.failedFuture(new IllegalStateException("expected")));

        Set<String> observed = traversals(run(graph, behaviors)).stream()
                .map(ExecutionEvent::edgeId).collect(Collectors.toSet());

        assertTrue(observed.contains("default-failure-route"));
        assertTrue(!observed.contains("ordinary-route"));
    }

    @Test
    void defaultedUnknownBehaviorStillReportsItsFallbackDispatch() throws Exception {
        var graph = new GraphDefinition(List.of(GraphNode.start("start"),
                GraphNode.behavior("probe", "not-in-the-catalog"), GraphNode.error("error"),
                GraphNode.end("end")), List.of(
                edge("to-probe", "start", "probe"), edge("probe-end", "probe", "end")));

        List<ExecutionEvent> events = run(graph, new BehaviorRegistry());

        assertTrue(events.stream().anyMatch(event -> event.type() == ExecutionEventType.NODE_DEFAULTED
                && "probe".equals(event.nodeId())));
        assertEquals(Set.of("to-probe", "probe-end"), traversals(events).stream()
                .map(ExecutionEvent::edgeId).collect(Collectors.toSet()));
    }

    private List<ExecutionEvent> run(GraphDefinition graph, BehaviorRegistry behaviors) throws Exception {
        var monitor = new ExecutionMonitor();
        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, behaviors, monitor)) {
            runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture().get(10, TimeUnit.SECONDS);
            return monitor.eventsAfter(0);
        }
    }

    private static List<ExecutionEvent> traversals(List<ExecutionEvent> events) {
        return events.stream().filter(event -> event.type() == ExecutionEventType.EDGE_TRAVERSED).toList();
    }

    private static long completionSequence(List<ExecutionEvent> events, String nodeId) {
        return events.stream().filter(event -> nodeId.equals(event.nodeId()))
                .filter(event -> event.type() == ExecutionEventType.NODE_COMPLETED
                        || event.type() == ExecutionEventType.NODE_BYPASSED
                        || event.type() == ExecutionEventType.NODE_FAILED)
                .mapToLong(ExecutionEvent::sequence).max().orElseThrow();
    }

    private static GraphEdge edge(String id, String source, String target) {
        return new GraphEdge(source, target, "continue", Map.of(), id);
    }
}
