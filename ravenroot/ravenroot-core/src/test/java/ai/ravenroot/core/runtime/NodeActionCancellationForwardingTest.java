package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class NodeActionCancellationForwardingTest {
    @Test
    void sdkRegistrationForwardsTheEngineSignalWhileLegacyEntryPointStillWorks() {
        AtomicReference<CancellationSignal> observed = new AtomicReference<>();
        NodeBehavior behavior = new NodeBehavior() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("cancellation-probe", "Probe", "Tests", "Probe.",
                        "process", false, List.of(), Set.of("deterministic"));
            }
            @Override public NodeAction create(NodeConfiguration configuration) {
                return new NodeAction() {
                    @Override public java.util.concurrent.CompletionStage<NodeResult> handle(NodeMessage message) {
                        return CompletableFuture.completedFuture(NodeResult.continueWith("legacy"));
                    }
                    @Override public java.util.concurrent.CompletionStage<NodeResult> handle(
                            NodeMessage message, CancellationSignal cancellation) {
                        observed.set(cancellation);
                        return CompletableFuture.completedFuture(NodeResult.continueWith("aware"));
                    }
                };
            }
        };
        NodePackage nodePackage = new NodePackage() {
            @Override public String id() { return "example.cancellation"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(behavior); }
        };
        NodeHandler handler = NodePackages.register(new BehaviorRegistry(), nodePackage)
                .create(new GraphNode("node", NodeKind.BEHAVIOR, "cancellation-probe", Map.of()))
                .orElseThrow();
        CancellationSignal signal = new CancellationSignal() {
            @Override public boolean cancelled() { return false; }
            @Override public void onCancel(Runnable listener) { }
        };

        assertEquals("aware", handler.handle(message(), signal).toCompletableFuture().join().payload());
        assertSame(signal, observed.get());
        assertEquals("legacy", handler.handle(message()).toCompletableFuture().join().payload());
    }

    @Test
    void graphRunnerForwardsItsEngineCancellationSignalToTheOperationalHandler() throws Exception {
        AtomicReference<CancellationSignal> observed = new AtomicReference<>();
        BehaviorRegistry registry = new BehaviorRegistry().register("cancellation-probe", new NodeHandler() {
            @Override public java.util.concurrent.CompletionStage<NodeResult> handle(NodeMessage message) {
                return CompletableFuture.completedFuture(NodeResult.continueWith("legacy"));
            }
            @Override public java.util.concurrent.CompletionStage<NodeResult> handle(
                    NodeMessage message, CancellationSignal cancellation) {
                observed.set(cancellation);
                return CompletableFuture.completedFuture(NodeResult.continueWith("aware"));
            }
        });
        GraphDefinition graph = new GraphDefinition(List.of(GraphNode.start("start"),
                GraphNode.behavior("probe", "cancellation-probe"), GraphNode.error("error"),
                GraphNode.end("end")), List.of(GraphEdge.to("start", "probe"), GraphEdge.to("probe", "end")));
        try (var engine = new SameThreadExecutionEngine();
             var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor())) {
            assertEquals("aware", runner.execute(new SecurityContext("request", "tenant", "subject",
                    PrincipalType.WORKLOAD, "issuer"), "input").toCompletableFuture().get().payload());
        }
        assertSame(StubEngineLifecycle.NEVER_CANCELLED, observed.get());
    }

    private static NodeMessage message() {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("request", "tenant", "subject", PrincipalType.WORKLOAD,
                "issuer"), id, id, id, id, Set.of(), "node", Map.of(), Map.of());
    }
}
