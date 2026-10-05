package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.NodeResult;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalledGraphTerminalAggregationTest {
    private final JoinTestEngine engine = new JoinTestEngine();

    @AfterEach void close() { engine.close(); }

    @Test
    void calledExecutionCollectsEveryRepeatedEndArrivalInCanonicalOrder() throws Exception {
        var registry = registry();
        try (var manager = GraphManager.from(graph());
             var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor())) {
            Object payload = runner.executeCalled(TestIdentities.TENANT_A, java.util.UUID.randomUUID(),
                            java.util.UUID.randomUUID(), "input", "v1", null, null, null)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS).payload();
            assertEquals(List.of("A", "B"), payload);
        }
    }

    @Test
    void ordinaryExecutionRetainsHistoricalSingleTerminalPayloadContract() throws Exception {
        try (var manager = GraphManager.from(graph());
             var runner = new GraphRunner(manager, engine, registry(), new ExecutionMonitor())) {
            Object payload = runner.execute(TestIdentities.TENANT_A, "input")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS).payload();
            assertTrue(Set.of("A", "B").contains(payload));
        }
    }

    private static GraphDefinition graph() {
        return new GraphDefinition(List.of(GraphNode.start("start"),
                GraphNode.behavior("A", "A"), GraphNode.behavior("B", "B"),
                new GraphNode("end", NodeKind.END, null, Map.of("joinPolicy", "each"))),
                List.of(GraphEdge.to("start", "A"), GraphEdge.to("start", "B"),
                        GraphEdge.to("A", "end"), GraphEdge.to("B", "end")));
    }

    private static BehaviorRegistry registry() {
        return new BehaviorRegistry()
                .register("A", message -> CompletableFuture.completedFuture(NodeResult.continueWith("A")))
                .register("B", message -> CompletableFuture.completedFuture(NodeResult.continueWith("B")));
    }
}
