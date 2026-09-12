package ai.ravenroot.testkit;

import ai.ravenroot.api.ai.ModelProvider;
import ai.ravenroot.api.ai.ModelRequest;
import ai.ravenroot.api.ai.ModelResponse;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.provenance.SyntheticProvenance;
import ai.ravenroot.core.ai.ModelProviderRegistry;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.GraphExecutionResult;
import ai.ravenroot.core.runtime.GraphRunner;
import ai.ravenroot.core.runtime.JoinFailureException;
import ai.ravenroot.core.runtime.JoinSpec;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.runtime.NodeHandler;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared cross-engine fixture for a model refusal absorbed by quorum fan-in. */
final class RefusedModelQuorumFixture {
    private static final String MODEL_BEHAVIOR = "unconfigured-model-fixture";
    private static final String MISSING_PROVIDER = "missing-model-provider";

    private RefusedModelQuorumFixture() {
    }

    static void assertQuorumOneCompletes(ExecutionEngine engine) throws Exception {
        var monitor = new ExecutionMonitor();
        var providerInvocations = new AtomicInteger();
        var modelFailureRecorded = new CompletableFuture<Void>();
        var modelFailureEventSeen = new AtomicBoolean();
        var observedAttributes = new AtomicReference<Map<String, Object>>();
        var observedPayload = new AtomicReference<Object>();
        var fixture = fixture(modelFailureRecorded, observedAttributes, observedPayload);

        try (var subscription = monitor.subscribe(event -> {
                 if (event.type() == ExecutionEventType.NODE_FAILED
                         && "model".equals(event.nodeId())) {
                     modelFailureEventSeen.set(true);
                     modelFailureRecorded.complete(null);
                 }
             });
             var manager = GraphManager.from(graph(1));
             var runner = new GraphRunner(manager, engine, fixture.registry(), monitor)) {
            try {
                fixture.providers().register(countingProvider(MISSING_PROVIDER, providerInvocations));
                GraphExecutionResult result = runner.execute(ExecutionEngineContract.TCK_IDENTITY, "input")
                        .toCompletableFuture().get(10, TimeUnit.SECONDS);

                assertTrue(modelFailureEventSeen.get(),
                        "NODE_FAILED(model) must release the deterministic branch");
                assertEquals("deterministic-content", result.payload());
                assertEquals(Set.of("start", "model", "success", "join", "observe", "end"),
                        result.visitedNodes(),
                        "both branches and the successful continuation must run, but the error terminal must not");
                assertTrue(result.handledFailure(), "the completed traversal must disclose its absorbed failure");
                assertEquals(Set.of("model"), result.handledFailureNodes(),
                        "the refused model branch must remain recorded as failed");
                assertEquals(1, eventCount(monitor, ExecutionEventType.NODE_FAILED, "model"),
                        "the refused branch must record exactly one failure");
                assertEquals(0, eventCount(monitor, ExecutionEventType.NODE_COMPLETED, "model"),
                        "the refused model branch did not produce content");
                assertEquals(0, eventCount(monitor, ExecutionEventType.NODE_DEFAULTED, "model"),
                        "the refusal must not become a synthetic pass-through");
                assertEquals(1, eventCount(monitor, ExecutionEventType.JOIN_SATISFIED, "join"));
                assertEquals(0, eventCount(monitor, ExecutionEventType.JOIN_FAILED, "join"));
                assertEquals(1, eventCount(monitor, ExecutionEventType.EXECUTION_COMPLETED, null));
                assertEquals(0, eventCount(monitor, ExecutionEventType.EXECUTION_FAILED, null));
                assertEquals(0, providerInvocations.get(),
                        "an unconfigured model branch must not acquire or invoke another provider");
                assertEquals("deterministic-content", observedPayload.get());
                assertNotNull(observedAttributes.get(),
                        "the observer must capture deterministic branch attributes");
                assertTrue(SyntheticProvenance.read(observedAttributes.get()).isEmpty(),
                        "the deterministic content must carry no readable synthetic-provenance marker");
                assertFalse(observedAttributes.get().keySet().stream()
                                .anyMatch(SyntheticProvenance::isProvenanceKey),
                        "content from the deterministic branch must not acquire synthetic provenance");
            } finally {
                modelFailureRecorded.complete(null);
            }
        }
    }

    static void assertUnmetQuorumFails(ExecutionEngine engine) throws Exception {
        var monitor = new ExecutionMonitor();
        var providerInvocations = new AtomicInteger();
        var modelFailureRecorded = new CompletableFuture<Void>();
        var modelFailureEventSeen = new AtomicBoolean();
        var observedAttributes = new AtomicReference<Map<String, Object>>();
        var observedPayload = new AtomicReference<Object>();
        var fixture = fixture(modelFailureRecorded, observedAttributes, observedPayload);

        try (var subscription = monitor.subscribe(event -> {
                 if (event.type() == ExecutionEventType.NODE_FAILED
                         && "model".equals(event.nodeId())) {
                     modelFailureEventSeen.set(true);
                     modelFailureRecorded.complete(null);
                 }
             });
             var manager = GraphManager.from(graph(2));
             var runner = new GraphRunner(manager, engine, fixture.registry(), monitor)) {
            try {
                fixture.providers().register(countingProvider(MISSING_PROVIDER, providerInvocations));
                CompletionStage<GraphExecutionResult> execution =
                        runner.execute(ExecutionEngineContract.TCK_IDENTITY, "input");
                var failure = assertThrows(ExecutionException.class,
                        () -> execution.toCompletableFuture().get(10, TimeUnit.SECONDS));

                assertTrue(modelFailureEventSeen.get(),
                        "NODE_FAILED(model) must release the deterministic branch");
                var joinFailure = assertInstanceOf(JoinFailureException.class, rootCause(failure));
                assertEquals(JoinFailureException.Reason.QUORUM_UNREACHABLE, joinFailure.reason());
                assertEquals(1, eventCount(monitor, ExecutionEventType.NODE_FAILED, "model"));
                assertEquals(0, eventCount(monitor, ExecutionEventType.NODE_COMPLETED, "model"));
                assertEquals(0, eventCount(monitor, ExecutionEventType.NODE_DEFAULTED, "model"));
                assertEquals(1, eventCount(monitor, ExecutionEventType.JOIN_FAILED, "join"));
                assertEquals(0, eventCount(monitor, ExecutionEventType.JOIN_SATISFIED, "join"));
                assertEquals(1, eventCount(monitor, ExecutionEventType.EXECUTION_FAILED, null));
                assertEquals(0, eventCount(monitor, ExecutionEventType.EXECUTION_COMPLETED, null));
                assertEquals(0, eventCount(monitor, ExecutionEventType.NODE_STARTED, "observe"));
                assertEquals(0, eventCount(monitor, ExecutionEventType.NODE_STARTED, "end"));
                assertEquals(0, providerInvocations.get(),
                        "an unmet quorum must not grant the refused branch model capability");
                assertNull(observedAttributes.get(),
                        "nothing after an unmet join may observe branch content");
                assertNull(observedPayload.get(),
                        "nothing after an unmet join may observe branch content");
            } finally {
                modelFailureRecorded.complete(null);
            }
        }
    }

    private static Fixture fixture(CompletableFuture<Void> modelFailureRecorded,
                                   AtomicReference<Map<String, Object>> observedAttributes,
                                   AtomicReference<Object> observedPayload) {
        var providers = new ModelProviderRegistry();
        var registry = new BehaviorRegistry()
                .registerFactory(new UnconfiguredModelFactory(providers))
                .register("deterministic-success", message -> modelFailureRecorded.thenApply(ignored ->
                        NodeResult.continueWith("deterministic-content")))
                .register("observe", message -> {
                    observedAttributes.set(message.attributes());
                    observedPayload.set(message.payload());
                    return CompletableFuture.completedFuture(
                            new NodeResult("continue", message.payload(), message.attributes()));
                });
        return new Fixture(providers, registry);
    }

    private static GraphDefinition graph(int quorum) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("model", NodeKind.BEHAVIOR, MODEL_BEHAVIOR,
                        Map.of("provider", MISSING_PROVIDER)),
                GraphNode.behavior("success", "deterministic-success"),
                new GraphNode("join", NodeKind.PASSTHROUGH, null,
                        Map.of(JoinSpec.QUORUM_PROPERTY, String.valueOf(quorum))),
                GraphNode.behavior("observe", "observe"),
                GraphNode.error("error"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "model"),
                GraphEdge.to("start", "success"),
                GraphEdge.to("model", "join"),
                GraphEdge.to("success", "join"),
                GraphEdge.to("join", "observe"),
                GraphEdge.to("observe", "end")));
    }

    private static long eventCount(ExecutionMonitor monitor, ExecutionEventType type, String nodeId) {
        return monitor.eventsAfter(0).stream().filter(event -> event.type() == type
                && (nodeId == null || nodeId.equals(event.nodeId()))).count();
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static ModelProvider countingProvider(String id, AtomicInteger invocations) {
        return new ModelProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public CompletionStage<ModelResponse> generate(ModelRequest request) {
                invocations.incrementAndGet();
                return CompletableFuture.completedFuture(
                        new ModelResponse("model-content", id, request.model(), Map.of()));
            }
        };
    }

    private record Fixture(ModelProviderRegistry providers, BehaviorRegistry registry) {
    }

    private record UnconfiguredModelFactory(ModelProviderRegistry providers)
            implements NodeBehaviorFactory {
        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor(MODEL_BEHAVIOR, "Unconfigured model fixture", "TCK",
                    "Model-backed behavior used to verify refusal through quorum fan-in.",
                    "actor", false, List.of(NodePropertyDescriptor.adapterId(
                    "provider", "Provider", NodePropertyType.STRING, "Model provider identifier.")),
                    Set.of("ai")).withOutcomes(
                    NodeOutcomeDescriptor.literal("continue", "The model answered."));
        }

        @Override
        public NodeHandler create(GraphNode node) {
            String providerId = NodePropertyDescriptor.adapterIdOf(node.properties().get("provider"));
            var provider = providers.find(providerId);
            if (provider.isEmpty()) {
                return message -> CompletableFuture.failedFuture(
                        new IllegalStateException("no model provider is configured"));
            }
            return message -> provider.orElseThrow().generate(new ModelRequest(message.executionId(),
                            node.id(), "fixture prompt", message.payload(), "", "", Map.of()))
                    .thenApply(response -> NodeResult.continueWith(response.payload()));
        }
    }
}
