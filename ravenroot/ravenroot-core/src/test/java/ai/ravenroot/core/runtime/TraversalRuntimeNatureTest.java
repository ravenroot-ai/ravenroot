package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.catalog.NodeRuntimeConcurrency;
import ai.ravenroot.api.catalog.NodeRuntimeMaxConcurrencyProperty;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeRuntimeNatureProperty;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.JoinSemantics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executed lifecycle, isolation, admission and cleanup contract for each runtime nature. */
class TraversalRuntimeNatureTest {

    @Test
    void workerCycleCreatesOneActorPerInvocationWhileTraversalCycleReusesOne() throws Exception {
        assertEquals(3, cycleTargets(NodeRuntimeNature.WORKER).stream().distinct().count());
        assertEquals(1, cycleTargets(NodeRuntimeNature.TRAVERSAL).stream().distinct().count());
    }

    @Test
    void concurrentTraversalsNeverShareATraversalScopedActor() throws Exception {
        var pending = new java.util.concurrent.ConcurrentHashMap<UUID, CompletableFuture<NodeResult>>();
        var engine = new SpawnRecordingEngine();
        try (var manager = GraphManager.from(linearGraph(Map.of(NodeRuntimeNatureProperty.NAME, "TRAVERSAL")));
             var runner = runner(manager, engine, registry(descriptor(NodeRuntimeNature.TRAVERSAL, 8),
                     message -> pending.computeIfAbsent(message.traversalId(), ignored -> new CompletableFuture<>())))) {
            UUID firstId = UUID.randomUUID();
            UUID secondId = UUID.randomUUID();
            var first = execute(runner, firstId);
            var second = execute(runner, secondId);
            await(() -> deliveries(engine, "probe").size() == 2);

            var targets = deliveries(engine, "probe");
            assertNotEquals(targets.get(0).target(), targets.get(1).target());
            assertEquals(2, runner.liveTraversalInstanceCount());
            pending.values().forEach(stage -> stage.complete(NodeResult.continueWith("ok")));
            CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS);
            assertEquals(0, runner.liveTraversalInstanceCount());
        }
    }

    @Test
    void graphSelectionOneOverridesParallelTrustedDefault() throws Exception {
        assertFanOutAdmission(Map.of(NodeRuntimeMaxConcurrencyProperty.NAME, "1"), 1);
        assertFanOutAdmission(Map.of(), 2);
    }

    private static void assertFanOutAdmission(Map<String, Object> targetProperties,
                                              int expectedBeforeRelease) throws Exception {
        var releases = new java.util.concurrent.CopyOnWriteArrayList<CompletableFuture<NodeResult>>();
        var running = new AtomicInteger();
        var peak = new AtomicInteger();
        NodeHandler target = message -> {
            int now = running.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            var result = new CompletableFuture<NodeResult>();
            releases.add(result);
            return result.whenComplete((ignored, error) -> running.decrementAndGet());
        };
        var registry = new BehaviorRegistry()
                .registerFactory(factory(descriptor("branch", NodeRuntimeNature.WORKER, 8),
                        message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()))))
                .registerFactory(factory(descriptor("target", NodeRuntimeNature.WORKER, 8), target));
        var graph = new GraphDefinition(List.of(GraphNode.start("start"),
                GraphNode.behavior("left", "branch"), GraphNode.behavior("right", "branch"),
                new GraphNode("target", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "target",
                        targetProperties),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "left"), GraphEdge.to("start", "right"),
                GraphEdge.to("left", "target"), GraphEdge.to("right", "target"),
                GraphEdge.to("target", "end")),
                Map.of(JoinSemantics.MARKER_PROPERTY, JoinSemantics.DECLARED));
        var engine = new SpawnRecordingEngine();
        try (var manager = GraphManager.from(graph); var runner = runner(manager, engine, registry)) {
            var execution = runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture();
            await(() -> releases.size() == expectedBeforeRelease);
            Thread.sleep(50);
            assertEquals(expectedBeforeRelease, releases.size(),
                    expectedBeforeRelease == 1
                            ? "the graph-selected limit must queue the second fan-out arrival"
                            : "the undeclared trusted default must admit both fan-out arrivals");
            if (expectedBeforeRelease == 1) {
                releases.get(0).complete(NodeResult.continueWith("first"));
                await(() -> releases.size() == 2);
                releases.get(1).complete(NodeResult.continueWith("second"));
            } else {
                releases.forEach(stage -> stage.complete(NodeResult.continueWith("released")));
            }
            execution.get(5, TimeUnit.SECONDS);
            assertEquals(expectedBeforeRelease, peak.get());
        }
    }

    @Test
    void invalidAndUnauthorizedConcurrencyAreRefusedBeforeSpawn() {
        for (Object value : List.of("0", "-1", "1.5", "257", "many")) {
            var engine = new SpawnRecordingEngine();
            try (var manager = GraphManager.from(linearGraph(
                    Map.of(NodeRuntimeMaxConcurrencyProperty.NAME, value)))) {
                assertThrows(NodeRuntimeConcurrencyException.class,
                        () -> runner(manager, engine, registry(descriptor(NodeRuntimeNature.WORKER, 256),
                                message -> CompletableFuture.completedFuture(
                                        NodeResult.continueWith(message.payload())))));
                assertEquals(0, engine.spawnedLogicalNames().size());
            }
        }

        var nonBehavior = new GraphDefinition(List.of(
                new GraphNode("start", ai.ravenroot.core.graph.NodeKind.START, null,
                        Map.of(NodeRuntimeMaxConcurrencyProperty.NAME, "1")),
                GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "end")));
        assertRefusedBeforeSpawn(nonBehavior, new BehaviorRegistry(),
                NodeRuntimeConcurrencyException.Reason.DECLARED_ON_NON_BEHAVIOR_NODE);

        assertRefusedBeforeSpawn(linearGraph(Map.of(NodeRuntimeMaxConcurrencyProperty.NAME, "1")),
                new BehaviorRegistry(),
                NodeRuntimeConcurrencyException.Reason.DECLARED_BY_UNCATALOGUED_BEHAVIOR);
    }

    @Test
    void traversalActorsAreReleasedOnSuccessFailureCancellationAndClose() throws Exception {
        assertCleanup(message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())), false);
        assertCleanup(message -> CompletableFuture.failedFuture(new IllegalStateException("boom")), true);

        var blocked = new CompletableFuture<NodeResult>();
        var engine = new SpawnRecordingEngine();
        UUID traversalId = UUID.randomUUID();
        try (var manager = GraphManager.from(linearGraph(Map.of(NodeRuntimeNatureProperty.NAME, "TRAVERSAL")));
             var runner = runner(manager, engine, registry(descriptor(NodeRuntimeNature.TRAVERSAL, 8),
                     message -> blocked))) {
            var execution = execute(runner, traversalId);
            await(() -> runner.liveTraversalInstanceCount() == 1);
            assertTrue(runner.cancelTraversal(traversalId));
            blocked.complete(NodeResult.continueWith("cancelled"));
            execution.handle((ignored, error) -> error).get(5, TimeUnit.SECONDS);
            assertEquals(0, runner.liveTraversalInstanceCount());
            assertEquals(0, engine.liveNodeCount());
        }

        var never = new CompletableFuture<NodeResult>();
        var closeEngine = new SpawnRecordingEngine();
        var manager = GraphManager.from(linearGraph(Map.of(NodeRuntimeNatureProperty.NAME, "TRAVERSAL")));
        var runner = runner(manager, closeEngine, registry(descriptor(NodeRuntimeNature.TRAVERSAL, 8),
                message -> never), Duration.ofMillis(100));
        runner.execute(TestIdentities.TENANT_A, "payload");
        await(() -> runner.liveTraversalInstanceCount() == 1);
        runner.close();
        assertEquals(0, runner.liveTraversalInstanceCount());
        assertEquals(0, closeEngine.liveNodeCount());
        manager.close();
    }

    private static List<ai.ravenroot.api.execution.NodeRef> cycleTargets(NodeRuntimeNature nature) throws Exception {
        var visits = new AtomicInteger();
        var engine = new SpawnRecordingEngine();
        var descriptor = descriptor(nature, 1);
        var registry = registry(descriptor, message -> CompletableFuture.completedFuture(
                new NodeResult(visits.incrementAndGet() < 3 ? "again" : "done", message.payload(), Map.of())));
        var graph = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("probe", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "probe",
                        Map.of(NodeRuntimeNatureProperty.NAME, nature.name())),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "probe"), new GraphEdge("probe", "probe", "again"),
                new GraphEdge("probe", "end", "done")),
                Map.of(JoinSemantics.MARKER_PROPERTY, JoinSemantics.DECLARED));
        try (var manager = GraphManager.from(graph); var runner = runner(manager, engine, registry)) {
            runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(0, runner.liveTraversalInstanceCount());
            return deliveries(engine, "probe").stream().map(SpawnRecordingEngine.Delivery::target).toList();
        }
    }

    private static void assertRefusedBeforeSpawn(GraphDefinition graph, BehaviorRegistry registry,
                                                  NodeRuntimeConcurrencyException.Reason reason) {
        var engine = new SpawnRecordingEngine();
        try (var manager = GraphManager.from(graph)) {
            var refusal = assertThrows(NodeRuntimeConcurrencyException.class,
                    () -> runner(manager, engine, registry));
            assertEquals(reason, refusal.reason());
            assertEquals("Graph property 'runtime.maxConcurrency' is invalid or is not authorized by the trusted catalog",
                    refusal.getMessage());
            assertFalse(refusal.getMessage().contains("start"));
            assertFalse(refusal.getMessage().contains("probe"));
            assertEquals(0, engine.spawnedLogicalNames().size());
        }
    }

    private static void assertCleanup(NodeHandler handler, boolean failed) throws Exception {
        var engine = new SpawnRecordingEngine();
        try (var manager = GraphManager.from(linearGraph(Map.of(NodeRuntimeNatureProperty.NAME, "TRAVERSAL")));
             var runner = runner(manager, engine, registry(descriptor(NodeRuntimeNature.TRAVERSAL, 8), handler))) {
            var result = runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture();
            if (failed) result.handle((ignored, error) -> error).get(5, TimeUnit.SECONDS);
            else result.get(5, TimeUnit.SECONDS);
            assertEquals(0, runner.liveTraversalInstanceCount());
            assertEquals(0, engine.liveNodeCount());
        }
    }

    private static CompletableFuture<?> execute(GraphRunner runner, UUID traversalId) {
        return runner.execute(TestIdentities.TENANT_A, UUID.randomUUID(), traversalId,
                "payload", "v1").toCompletableFuture();
    }

    private static GraphDefinition linearGraph(Map<String, Object> properties) {
        return new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("probe", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "probe", properties),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "probe"), GraphEdge.to("probe", "end")));
    }

    private static NodeTypeDescriptor descriptor(NodeRuntimeNature nature, int ceiling) {
        return descriptor("probe", nature, ceiling);
    }

    private static NodeTypeDescriptor descriptor(String behavior, NodeRuntimeNature nature, int ceiling) {
        return new NodeTypeDescriptor(behavior, behavior, "Test", "d", "actor", false, List.of(), Set.of(),
                nature, Set.of(NodeRuntimeNature.WORKER, NodeRuntimeNature.TRAVERSAL), Set.of(), List.of(),
                new NodeRuntimeConcurrency(Math.min(ceiling, 8), ceiling));
    }

    private static BehaviorRegistry registry(NodeTypeDescriptor descriptor, NodeHandler handler) {
        return new BehaviorRegistry().registerFactory(factory(descriptor, handler));
    }

    private static NodeBehaviorFactory factory(NodeTypeDescriptor descriptor, NodeHandler handler) {
        return new NodeBehaviorFactory() {
            @Override public NodeTypeDescriptor descriptor() { return descriptor; }
            @Override public NodeHandler create(GraphNode node) { return handler; }
        };
    }

    private static GraphRunner runner(GraphManager manager, SpawnRecordingEngine engine,
                                      BehaviorRegistry registry) {
        return runner(manager, engine, registry, Duration.ofSeconds(2));
    }

    private static GraphRunner runner(GraphManager manager, SpawnRecordingEngine engine,
                                      BehaviorRegistry registry, Duration bound) {
        return new GraphRunner(manager, engine, registry, new ExecutionMonitor(),
                ExecutionIdentitySource.randomUuids(), bound);
    }

    private static List<SpawnRecordingEngine.Delivery> deliveries(SpawnRecordingEngine engine, String nodeId) {
        return engine.deliveries().stream().filter(delivery -> nodeId.equals(delivery.nodeId())).toList();
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "condition was not reached within five seconds");
    }
}
