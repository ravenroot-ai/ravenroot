package ai.ravenroot.pekko;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.GraphRunner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphRunnerIntegrationTest {
    private static final ai.ravenroot.api.security.SecurityContext IDENTITY =
            new ai.ravenroot.api.security.SecurityContext("graphrunner-request", "tenant-a", "alice",
                    ai.ravenroot.api.security.PrincipalType.USER, "urn:ravenroot:test");

    /**
     * Renamed for CORE-03. A fan-in failure now ends the traversal without waiting for superseded
     * branches, because a join that timed out has to be able to fail while a sibling branch is still
     * blocked — which is the situation a timeout exists for. What is still guaranteed, and is what
     * this test actually pins, is that already-started work runs to completion and keeps publishing
     * its node events afterwards rather than being abandoned mid-flight.
     *
     * <p>The failure assertion was also tightened: {@code assertThrows(Exception.class, ...)} around
     * a timed {@code get} was satisfied by the {@code TimeoutException} of an execution that never
     * completed at all, so it could not distinguish "failed" from "hung".</p>
     */
    @Test
    void drainsAlreadyStartedFanOutWorkAfterPublishingTerminalExecutionFailure() throws Exception {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("fails", "fail"),
                GraphNode.behavior("slow", "slow"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "fails"),
                GraphEdge.to("start", "slow"),
                GraphEdge.to("fails", "end"),
                GraphEdge.to("slow", "end")));
        var slowStarted = new CountDownLatch(1);
        var releaseSlow = new CompletableFuture<NodeResult>();
        var releaseFailure = new CompletableFuture<NodeResult>();
        var slowCompleted = new CountDownLatch(1);
        var monitor = new ExecutionMonitor();
        try (AutoCloseable ignored = monitor.subscribe(event -> {
            if (event.type() == ai.ravenroot.api.application.ExecutionEventType.NODE_COMPLETED
                    && "slow".equals(event.nodeId())) {
                slowCompleted.countDown();
            }
        });
             var manager = GraphManager.from(graph);
             var engine = new PekkoExecutionEngine("ravenroot-terminal-drain-test");
             var runner = new GraphRunner(manager, engine, new BehaviorRegistry()
                     .register("fail", message -> releaseFailure)
                     .register("slow", message -> {
                         slowStarted.countDown();
                         return releaseSlow;
                     }), monitor)) {
            var execution = runner.execute(IDENTITY, "payload").toCompletableFuture();
            assertTrue(slowStarted.await(5, TimeUnit.SECONDS), "slow sibling must be in flight");
            releaseFailure.completeExceptionally(new IllegalStateException("expected failure"));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> execution.get(5, TimeUnit.SECONDS));

            releaseSlow.complete(NodeResult.continueWith("slow result"));
            assertTrue(slowCompleted.await(5, TimeUnit.SECONDS),
                    "an already-started sibling must complete before terminal execution failure is published");
        }
    }

    @Test
    void executesFanOutFanInAndUnknownBehaviorFallback() throws Exception {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("known", "append-a"),
                GraphNode.behavior("future", "not-implemented-yet"),
                GraphNode.passthrough("join"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "known"),
                GraphEdge.to("start", "future"),
                GraphEdge.to("known", "join"),
                GraphEdge.to("future", "join"),
                GraphEdge.to("join", "end")));
        var registry = new BehaviorRegistry().register("append-a", message ->
                CompletableFuture.completedFuture(NodeResult.continueWith(message.payload() + "-a")));

        try (var manager = GraphManager.from(graph);
             var engine = new PekkoExecutionEngine("ravenroot-graph-test");
             var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor())) {
            var result = runner.execute(IDENTITY, "root").toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(5, result.visitedNodes().size());
            assertTrue(result.defaultedNodes().contains("future"));
            assertTrue(result.payload() instanceof List<?>);
            assertEquals(2, ((List<?>) result.payload()).size());
        }
    }

    @Test
    void propagatesDeterministicHierarchyAndMultiParentFanInThroughMessagesAndEvents() throws Exception {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.passthrough("left"),
                GraphNode.passthrough("right"),
                GraphNode.behavior("join", "capture-join"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "left"),
                GraphEdge.to("start", "right"),
                GraphEdge.to("left", "join"),
                GraphEdge.to("right", "join"),
                GraphEdge.to("join", "end")));
        var captured = new AtomicReference<NodeMessage>();
        var registry = new BehaviorRegistry().register("capture-join", message -> {
            captured.set(message);
            return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        });
        var identities = new java.util.ArrayDeque<>(List.of(
                id(1), id(2),
                id(3), id(4),
                id(5), id(6),
                id(7), id(8),
                id(9), id(10),
                id(11), id(12)));
        ExecutionIdentitySource source = ignored -> identities.removeFirst();
        var monitor = new ExecutionMonitor();

        try (var manager = GraphManager.from(graph);
             var engine = new PekkoExecutionEngine("ravenroot-identity-test");
             var runner = new GraphRunner(manager, engine, registry, monitor, source)) {
            var result = runner.execute(IDENTITY, "root").toCompletableFuture().get(5, TimeUnit.SECONDS);

            NodeMessage join = captured.get();
            assertEquals(id(1), result.processInstanceId());
            assertEquals(id(2), result.traversalId());
            assertEquals(result.traversalId(), result.executionId());
            assertEquals(id(1), join.processInstanceId());
            assertEquals(id(2), join.traversalId());
            assertEquals(join.traversalId(), join.executionId());
            assertEquals(id(9), join.invocationId());
            assertEquals(id(10), join.attemptId());
            assertEquals(Set.of(id(5), id(7)), join.parentInvocationIds());
            assertTrue(join.payload() instanceof List<?>);

            var joinStarted = monitor.eventsAfter(0).stream()
                    .filter(event -> event.nodeId() != null && event.nodeId().equals("join"))
                    .filter(event -> event.type() == ai.ravenroot.api.application.ExecutionEventType.NODE_STARTED)
                    .findFirst().orElseThrow();
            assertEquals(join.processInstanceId(), joinStarted.processInstanceId());
            assertEquals(join.traversalId(), joinStarted.traversalId());
            assertEquals(join.invocationId(), joinStarted.invocationId());
            assertEquals(join.attemptId(), joinStarted.attemptId());
            assertEquals(joinStarted.traversalId(), joinStarted.executionId());
            assertTrue(identities.isEmpty());
        }
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
