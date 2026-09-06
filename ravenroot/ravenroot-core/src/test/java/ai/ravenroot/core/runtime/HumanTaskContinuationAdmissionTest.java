package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.GraphVersionSnapshot;
import ai.ravenroot.core.graph.JoinSemantics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HumanTaskContinuationAdmissionTest {
    @Test
    void completedNodeIsLazyButCurrentAdmissionReturnsWhenADownstreamCycleRevisitsIt() throws Exception {
        var currentValidations = new AtomicInteger();
        var currentCreations = new AtomicInteger();
        var downstreamRuns = new AtomicInteger();
        var registry = new BehaviorRegistry()
                .register("after", message -> {
                    downstreamRuns.incrementAndGet();
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            NodeResult.continueWith(message.payload()));
                })
                .registerFactory(new NodeBehaviorFactory() {
                    @Override
                    public NodeTypeDescriptor descriptor() {
                        return new NodeTypeDescriptor("human-task", "Human task", "Test", "", "flow",
                                false, List.of(), Set.of());
                    }

                    @Override
                    public void validate(GraphNode node) {
                        currentValidations.incrementAndGet();
                        throw new IllegalStateException("current Human Task admission refused");
                    }

                    @Override
                    public NodeHandler create(GraphNode node) {
                        currentCreations.incrementAndGet();
                        throw new IllegalStateException("current Human Task admission refused");
                    }
                });
        GraphDefinition graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("after", "after"),
                GraphNode.behavior("review", "human-task"),
                GraphNode.error("error"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "after"),
                GraphEdge.to("after", "review"),
                GraphEdge.to("review", "after"),
                GraphEdge.to("review", "end")),
                Map.of(JoinSemantics.MARKER_PROPERTY, JoinSemantics.DECLARED));

        try (var engine = new SameThreadExecutionEngine();
             var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, GraphVersionSnapshot.submission(graph), engine,
                     registry, new ExecutionMonitor(),
                     ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(),
                     GraphRunner.DEFAULT_SHUTDOWN_BOUND, GraphExecutionLimits.DEFAULTS, "review")) {
            assertEquals(0, currentValidations.get(),
                    "the already-admitted completed node must not be revalidated at recovery composition");
            assertEquals(0, currentCreations.get(),
                    "the completed node must not be materialized before continuation routing");

            assertThrows(Exception.class, () -> runner.execute(TestIdentities.TENANT_A, "payload")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals(1, currentCreations.get(),
                    "an actual downstream revisit must apply current factory admission before new work");
        }
    }
}
