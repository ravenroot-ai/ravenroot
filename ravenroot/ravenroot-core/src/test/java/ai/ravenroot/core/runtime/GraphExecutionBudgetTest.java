package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphMlLimits;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.JoinSemantics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphExecutionBudgetTest {

    @Test
    void programmaticNodeLimitRefusesBeforeAnyActorIsSpawned() {
        var engine = new JoinTestEngine();
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), GraphNode.behavior("work", "missing"),
                GraphNode.end("end")), List.of(GraphEdge.to("start", "work"), GraphEdge.to("work", "end")));
        GraphExecutionLimits limits = limits(graphMl(2, 25_000, 100_000), PayloadLimits.DEFAULTS,
                64, 100_000, 100_000, 64 * 1024 * 1024L);
        try (var manager = GraphManager.from(graph); engine) {
            GraphExecutionLimitException refused = assertThrows(GraphExecutionLimitException.class,
                    () -> runner(manager, engine, new BehaviorRegistry(), limits));
            assertEquals(GraphExecutionLimitException.Reason.NODES, refused.reason());
            assertEquals(0, engine.spawnCount());
        }
    }

    @Test
    void configuredFanOutRefusesBeforeAnyActorIsSpawned() {
        var engine = new JoinTestEngine();
        var graph = fanOut(3);
        GraphExecutionLimits limits = limits(GraphMlLimits.DEFAULTS, PayloadLimits.DEFAULTS,
                2, 100_000, 100_000, 64 * 1024 * 1024L);
        try (var manager = GraphManager.from(graph); engine) {
            GraphExecutionLimitException refused = assertThrows(GraphExecutionLimitException.class,
                    () -> runner(manager, engine, new BehaviorRegistry(), limits));
            assertEquals(GraphExecutionLimitException.Reason.FAN_OUT, refused.reason());
            assertEquals(0, engine.spawnCount());
        }
    }

    @Test
    @Timeout(10)
    void fanOutAmplificationIsReservedAtomicallyBeforeTheFirstChild() {
        var engine = new JoinTestEngine();
        GraphExecutionLimits limits = limits(GraphMlLimits.DEFAULTS, PayloadLimits.DEFAULTS,
                4, 100_000, 2, 64 * 1024 * 1024L);
        try (var manager = GraphManager.from(fanOut(3));
             var runner = runner(manager, engine, new BehaviorRegistry(), limits);
             engine) {
            Throwable failure = assertThrows(CompletionException.class,
                    () -> runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture().join());
            GraphExecutionLimitException refused = findLimit(failure);
            assertEquals(GraphExecutionLimitException.Reason.AMPLIFIED_DELIVERIES, refused.reason());
            assertEquals(1, engine.spawnCount(), "only the already-admitted start actor may have existed");
        }
    }

    @Test
    @Timeout(10)
    void cycleReentryConsumesOneSharedTraversalStepBudget() {
        var engine = new JoinTestEngine();
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.behavior("loop", "missing"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "loop"), GraphEdge.to("loop", "loop"),
                        GraphEdge.to("loop", "end")),
                Map.of(JoinSemantics.MARKER_PROPERTY, JoinSemantics.DECLARED));
        GraphExecutionLimits limits = limits(GraphMlLimits.DEFAULTS, PayloadLimits.DEFAULTS,
                4, 9, 100, 64 * 1024 * 1024L);
        try (var manager = GraphManager.from(graph);
             var runner = runner(manager, engine, new BehaviorRegistry(), limits);
             engine) {
            Throwable failure = assertThrows(CompletionException.class,
                    () -> runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture().join());
            GraphExecutionLimitException refused = findLimit(failure);
            assertEquals(GraphExecutionLimitException.Reason.TRAVERSAL_STEPS, refused.reason());
            if (engine.spawnCount() > 9) {
                throw new AssertionError("cycle spawned past its step ceiling: " + engine.spawnCount());
            }
        }
    }

    @Test
    @Timeout(10)
    void oversizedNodeOutputFailsBeforeItsSuccessorIsSpawned() {
        var engine = new JoinTestEngine();
        var registry = new BehaviorRegistry().register("large", message ->
                java.util.concurrent.CompletableFuture.completedFuture(
                        NodeResult.continueWith("x".repeat(128))));
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.behavior("large", "large"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "large"), GraphEdge.to("large", "end")));
        var payload = new PayloadLimits(64, 32, 1_000, 10_000, 32 * 1024, 256);
        GraphExecutionLimits limits = limits(GraphMlLimits.DEFAULTS, payload,
                4, 100, 100, 1024 * 1024);
        try (var manager = GraphManager.from(graph);
             var runner = runner(manager, engine, registry, limits);
             engine) {
            assertThrows(CompletionException.class,
                    () -> runner.execute(TestIdentities.TENANT_A, "ok").toCompletableFuture().join());
            assertEquals(2, engine.spawnCount(), "the end actor must not be allocated for rejected output");
        }
    }

    @Test
    void liveActorAndInFlightReservationsRefuseAtomically() {
        GraphExecutionLimits limits = new GraphExecutionLimits(GraphMlLimits.DEFAULTS, PayloadLimits.DEFAULTS,
                4, 256, 1, 2, 1, 100, 100, 100, 8);
        var budget = new ExecutionBudget(limits);

        ExecutionBudget.Actor actor = budget.reserveActor();
        assertEquals(GraphExecutionLimitException.Reason.LIVE_ACTORS,
                assertThrows(GraphExecutionLimitException.class, budget::reserveActor).reason());
        actor.close();
        budget.reserveActor().close();

        ExecutionBudget.Hop root = budget.reserveRoot(1);
        assertEquals(GraphExecutionLimitException.Reason.IN_FLIGHT_HOPS,
                assertThrows(GraphExecutionLimitException.class, () -> budget.reserveFanOut(2, 1)).reason());
        budget.reserveFanOut(1, 1).getFirst().close();
        root.close();
    }

    @Test
    void admissionQueueRefusesBeforeAllocatingAnotherWaiter() {
        var registry = new TraversalAdmissionRegistry();
        var key = new TraversalAdmissionRegistry.Key("tenant", "deployment", "version", UUID.randomUUID(), "n");
        TraversalAdmissionRegistry.Lease first = registry.acquire(key, 1, 1, 1).toCompletableFuture().join();
        var waiting = registry.acquire(key, 1, 1, 1).toCompletableFuture();

        GraphExecutionLimitException refusal = findLimit(assertThrows(CompletionException.class,
                () -> registry.acquire(key, 1, 1, 1).toCompletableFuture().join()));
        assertEquals(GraphExecutionLimitException.Reason.ADMISSION_QUEUE, refusal.reason());

        first.close();
        waiting.join().close();
        registry.close();
    }

    @Test
    void wideAndDeepTopologiesStayLinearUnderAdmission() {
        var engine = new JoinTestEngine();
        GraphExecutionLimits limits = limits(GraphMlLimits.DEFAULTS, PayloadLimits.DEFAULTS,
                16, 100_000, 100_000, 64 * 1024 * 1024L);
        try (engine) {
            for (int width = 1; width <= 48; width++) {
                GraphDefinition graph = fanOut(width);
                try (var manager = GraphManager.from(graph)) {
                    if (width <= 16) {
                        try (var ignored = runner(manager, engine, new BehaviorRegistry(), limits)) { }
                    } else {
                        GraphExecutionLimitException refused = assertThrows(GraphExecutionLimitException.class,
                                () -> runner(manager, engine, new BehaviorRegistry(), limits));
                        assertEquals(GraphExecutionLimitException.Reason.FAN_OUT, refused.reason());
                    }
                }
            }
            try (var manager = GraphManager.from(deepChain(512));
                 var ignored = runner(manager, engine, new BehaviorRegistry(), limits)) { }
        }
    }

    @Property(tries = 100)
    void randomizedFanOutAdmissionNeverCreatesAPartialRejectedGraph(
            @ForAll @IntRange(min = 1, max = 80) int width) {
        var engine = new JoinTestEngine();
        GraphExecutionLimits limits = limits(GraphMlLimits.DEFAULTS, PayloadLimits.DEFAULTS,
                16, 100_000, 100_000, 64 * 1024 * 1024L);
        try (var manager = GraphManager.from(fanOut(width)); engine) {
            if (width <= 16) {
                try (var ignored = runner(manager, engine, new BehaviorRegistry(), limits)) { }
            } else {
                assertThrows(GraphExecutionLimitException.class,
                        () -> runner(manager, engine, new BehaviorRegistry(), limits));
                assertEquals(0, engine.spawnCount());
            }
        }
    }

    private static GraphRunner runner(GraphManager manager, JoinTestEngine engine, BehaviorRegistry registry,
                                      GraphExecutionLimits limits) {
        return new GraphRunner(manager, engine, registry, new ExecutionMonitor(),
                ExecutionIdentitySource.randomUuids(), UnknownBehaviorPolicy.passThrough(),
                ExecutionPolicy.STANDARD, limits);
    }

    private static GraphDefinition fanOut(int width) {
        var nodes = new ArrayList<GraphNode>();
        var edges = new ArrayList<GraphEdge>();
        nodes.add(GraphNode.start("start"));
        nodes.add(GraphNode.end("end"));
        for (int index = 0; index < width; index++) {
            String id = "work-" + index;
            nodes.add(GraphNode.behavior(id, "missing"));
            edges.add(new GraphEdge("start", id, "continue"));
            edges.add(new GraphEdge(id, "end", "continue"));
        }
        return new GraphDefinition(nodes, edges);
    }

    private static GraphDefinition deepChain(int depth) {
        var nodes = new ArrayList<GraphNode>();
        var edges = new ArrayList<GraphEdge>();
        nodes.add(GraphNode.start("start"));
        String previous = "start";
        for (int index = 0; index < depth; index++) {
            String id = "step-" + index;
            nodes.add(GraphNode.behavior(id, "missing"));
            edges.add(GraphEdge.to(previous, id));
            previous = id;
        }
        nodes.add(GraphNode.end("end"));
        edges.add(GraphEdge.to(previous, "end"));
        return new GraphDefinition(nodes, edges);
    }

    private static GraphExecutionLimits limits(GraphMlLimits graphMl, PayloadLimits payload, int fanOut,
                                                long steps, long deliveries, long bytes) {
        return new GraphExecutionLimits(graphMl, payload, fanOut, 256, 256, 1024, 1024,
                steps, deliveries, bytes, 8);
    }

    private static GraphMlLimits graphMl(int nodes, int edges, int properties) {
        GraphMlLimits defaults = GraphMlLimits.DEFAULTS;
        return new GraphMlLimits(defaults.maxBytes(), nodes, edges, properties, defaults.maxDepth(),
                defaults.maxStringLength(), defaults.maxKeys(), defaults.maxElements(), defaults.maxAttributes(),
                defaults.maxNamespaceDeclarations());
    }

    private static GraphExecutionLimitException findLimit(Throwable failure) {
        if (failure instanceof GraphExecutionLimitException limit) return limit;
        for (Throwable suppressed : failure.getSuppressed()) {
            GraphExecutionLimitException found = findLimit(suppressed);
            if (found != null) return found;
        }
        return failure.getCause() == null ? null : findLimit(failure.getCause());
    }
}
