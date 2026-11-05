package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.GraphAdmissionException;
import ai.ravenroot.api.application.GraphAdmissionPurpose;
import ai.ravenroot.api.application.GraphAdmissionReason;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class SagaAuthoringContractTest {
    private static final Map<String, Object> INJECTED = Map.of("saga.role", "forward",
            "saga.irreversible", "false", "saga.businessCompletionRequired", false);

    @Test
    void catalogDoesNotOfferUnsupportedParticipantsOrDeclareSagaDefaults() {
        for (String behavior : List.of("log", "slack.send", "kafka.produce", "custom")) {
            var descriptor = descriptor(behavior, Set.of("side-effect"));
            assertEquals(descriptor, SagaCatalogProperties.decorate(descriptor));
        }
        for (var descriptor : List.of(descriptor("pure", Set.of("saga-pure")),
                descriptor("jdbc.insert", Set.of("side-effect", "saga-adapter:ravenroot.jdbc-receipt.v1")),
                descriptor("amqp.publish", Set.of("side-effect", "saga-adapter:ravenroot.amqp-inbox.v1")),
                descriptor("http-request", Set.of("side-effect", "saga-adapter:ravenroot.http-idempotency.v1")))) {
            var decorated = SagaCatalogProperties.decorate(descriptor);
            assertTrue(decorated.properties().stream().anyMatch(property -> property.name().equals("saga.scope")));
            assertTrue(decorated.properties().stream().filter(property -> property.name().startsWith("saga."))
                    .allMatch(property -> property.defaultValue() == null || property.defaultValue().isEmpty()));
        }
        assertNull(SagaCatalogProperties.participant(descriptor("log", Set.of("saga-pure", "side-effect"))));
        assertNull(SagaCatalogProperties.participant(descriptor("fake", Set.of("saga-adapter:ravenroot.jdbc-receipt.v1"))));
    }

    @Test
    void defaultLogIsAdmittedConnectedDisconnectedAndBeforeAnActualSource() {
        var registry = registry();
        for (var edges : List.of(List.of(GraphEdge.to("start", "end")),
                List.of(GraphEdge.to("start", "log"), GraphEdge.to("log", "end")),
                List.of(GraphEdge.to("start", "log"), GraphEdge.to("log", "pure"), GraphEdge.to("pure", "end")))) {
            var graph = new GraphDefinition(List.of(GraphNode.start("start"), GraphNode.behavior("log", "log"),
                    GraphNode.behavior("pure", "test.saga.source"), GraphNode.end("end")), edges);
            assertDoesNotThrow(() -> new GraphAdmissionValidator(registry, GraphExecutionLimits.DEFAULTS, false)
                    .validate(graph, GraphAdmissionPurpose.EXECUTION, node -> true));
            assertEquals(1, new GraphAdmissionValidator(registry, GraphExecutionLimits.DEFAULTS, false)
                    .validate(graph, GraphAdmissionPurpose.SOURCE_SESSION, node -> true));
            assertTrue(SagaGraphContract.validate(graph, registry).isEmpty());
        }
    }

    @Test
    void onlyTheConfirmedCompleteLogInjectionIsMigrated() {
        var repaired = GraphNode.behavior("log", "log");
        assertEquals(repaired.properties(), new GraphNode("log", repaired.kind(), "log", INJECTED).properties());
        var partial = new LinkedHashMap<>(INJECTED); partial.remove("saga.irreversible");
        var intentional = new LinkedHashMap<>(INJECTED); intentional.put("saga.scope", "order");
        var unknown = new LinkedHashMap<>(INJECTED); unknown.put("saga.future", "authored");
        var nondefault = new LinkedHashMap<>(INJECTED); nondefault.put("saga.irreversible", true);
        for (var declarations : List.of(partial, intentional, unknown, nondefault)) {
            var log = new GraphNode("log", repaired.kind(), "log", declarations);
            assertEquals(declarations, log.properties());
            assertThrows(SagaConfigurationException.class, () -> SagaGraphContract.validate(graph(log), registry()));
        }
        assertEquals(INJECTED, new GraphNode("custom", repaired.kind(), "custom", INJECTED).properties());
    }

    @Test
    void explicitScopesEnrollOnlyDeclaredParticipantsAndKeepLogAndTransformationsOrdinary() {
        var participant = new GraphNode("participant", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "trusted",
                Map.of("saga.scope", "order", "saga.step", "reserve", "saga.participant", "pure"));
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), participant,
                GraphNode.behavior("log", "log"), GraphNode.behavior("transform", "trusted"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "participant"), GraphEdge.to("participant", "log"),
                        GraphEdge.to("log", "transform"), GraphEdge.to("transform", "end")));
        var definitions = SagaGraphContract.validate(graph, registry());
        assertEquals(Set.of("order"), definitions.keySet());
        assertEquals(1, definitions.get("order").steps().size());
        assertFalse(SagaGraphContract.forward(graph.node("log")));
        assertFalse(SagaGraphContract.forward(graph.node("transform")));
        assertDoesNotThrow(() -> new GraphAdmissionValidator(registry(), GraphExecutionLimits.DEFAULTS, true)
                .validate(graph, GraphAdmissionPurpose.EXECUTION, node -> true));
        var refused = assertThrows(GraphAdmissionException.class, () -> new GraphAdmissionValidator(registry(),
                GraphExecutionLimits.DEFAULTS, false).validate(graph, GraphAdmissionPurpose.EXECUTION, node -> true));
        assertEquals(GraphAdmissionReason.SAGA_DURABILITY_REQUIRED, refused.findings().getFirst().reason());
    }

    @Test
    void missingScopeAndForgedLogContractHaveActionableSafeFindings() {
        var incomplete = new GraphNode("log", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "log", Map.of("saga.step", "reserve"));
        var missing = finding(incomplete);
        assertEquals("log", missing.nodeId()); assertEquals("saga.scope", missing.propertyName());
        assertEquals(GraphAdmissionReason.SAGA_CONTRACT_INVALID, missing.reason());
        var forged = new GraphNode("log", incomplete.kind(), "log", Map.of("saga.scope", "order",
                "saga.step", "reserve", "saga.participant", "pure"));
        var unsupported = finding(forged);
        assertEquals("saga.participant", unsupported.propertyName());
        assertEquals(GraphAdmissionReason.SAGA_PARTICIPANT_UNSUPPORTED, unsupported.reason());
    }

    @Test
    void explicitConfigurationFailuresIdentifyTheirActionableProperty() {
        var pure = Map.<String, Object>of("saga.scope", "order", "saga.step", "reserve", "saga.participant", "pure");
        var missingStep = new LinkedHashMap<>(pure); missingStep.remove("saga.step");
        assertProperty("saga.step", graph(new GraphNode("participant", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "trusted", missingStep)), registry());
        for (String dependencies : List.of("unknown", "reserve", "reserve,", "reserve,,other")) {
            var declared = new LinkedHashMap<>(pure); declared.put("saga.dependsOn", dependencies);
            assertProperty("saga.dependsOn", graph(new GraphNode("participant", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "trusted", declared)), registry());
        }
        var absentCompensation = new LinkedHashMap<>(pure); absentCompensation.put("saga.compensation", "missing");
        assertProperty("saga.compensation", graph(new GraphNode("participant", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "trusted", absentCompensation)), registry());
        var unknown = new LinkedHashMap<>(pure); unknown.put("saga.future", "preserve-and-reject");
        assertProperty("saga.future", graph(new GraphNode("participant", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "trusted", unknown)), registry());
        var loop = new GraphDefinition(List.of(GraphNode.start("start"), new GraphNode("participant",
                ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "trusted", pure), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "participant"), GraphEdge.to("participant", "participant"), GraphEdge.to("participant", "end")), Map.of("join.semantics", "declared"));
        assertProperty("saga.scope", loop, registry());
        var httpRegistry = new BehaviorRegistry().registerFactory(new NodeBehaviorFactory() {
            @Override public NodeTypeDescriptor descriptor() { return SagaAuthoringContractTest.descriptor("http-request", Set.of("side-effect", "saga-adapter:ravenroot.http-idempotency.v1")); }
            @Override public NodeHandler create(GraphNode node) { return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())); }
        });
        var http = new LinkedHashMap<>(pure); http.put("saga.participant", "http-idempotency-v1");
        for (String adapter : List.of("", "wrong")) {
            http.put("saga.adapter", adapter);
            assertProperty("saga.adapter", graph(new GraphNode("participant", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "http-request", http)), httpRegistry);
        }
        http.put("saga.adapter", "ravenroot.http-idempotency.v1");
        assertProperty("saga.outcomeLookupUrl", graph(new GraphNode("participant", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "http-request", http)), httpRegistry);
        http.put("saga.outcomeLookupUrl", "https://participant.test/receipt");
        assertProperty("saga.compensation", graph(new GraphNode("participant", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "http-request", http)), httpRegistry);
        http.put("saga.irreversible", true);
        assertDoesNotThrow(() -> new GraphAdmissionValidator(httpRegistry, GraphExecutionLimits.DEFAULTS, true)
                .validate(graph(new GraphNode("participant", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "http-request", http)), GraphAdmissionPurpose.EXECUTION, ignored -> true));
    }

    private static void assertProperty(String property, GraphDefinition graph, BehaviorRegistry registry) {
        var refusal = assertThrows(GraphAdmissionException.class, () -> new GraphAdmissionValidator(registry,
                GraphExecutionLimits.DEFAULTS, true).validate(graph, GraphAdmissionPurpose.EXECUTION, ignored -> true));
        var finding = refusal.findings().getFirst();
        assertEquals("participant", finding.nodeId());
        assertEquals(property, finding.propertyName());
        assertNotEquals(GraphAdmissionReason.INVALID_STRUCTURE, finding.reason());
    }

    private static ai.ravenroot.api.application.GraphAdmissionFinding finding(GraphNode node) {
        return assertThrows(GraphAdmissionException.class, () -> new GraphAdmissionValidator(registry(),
                GraphExecutionLimits.DEFAULTS).validate(graph(node), GraphAdmissionPurpose.EXECUTION, ignored -> true))
                .findings().getFirst();
    }
    private static GraphDefinition graph(GraphNode node) {
        return new GraphDefinition(List.of(GraphNode.start("start"), node, GraphNode.end("end")),
                List.of(GraphEdge.to("start", node.id()), GraphEdge.to(node.id(), "end")));
    }
    private static NodeTypeDescriptor descriptor(String behavior, Set<String> capabilities) {
        return new NodeTypeDescriptor(behavior, behavior, "Test", behavior, "actor", false, List.of(), capabilities);
    }
    private static BehaviorRegistry registry() {
        return ai.ravenroot.core.runtime.NodePackages.register(BehaviorRegistry.standard(), new ai.ravenroot.api.node.NodePackage() {
            @Override public String id() { return "test.saga.source.package"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return ai.ravenroot.api.node.NodeSdk.CONTRACT; }
            @Override public List<ai.ravenroot.api.node.NodeBehavior> behaviors() { return List.of(new TestSource()); }
        }).registerFactory(new NodeBehaviorFactory() {
            @Override public NodeTypeDescriptor descriptor() { return SagaAuthoringContractTest.descriptor("trusted", Set.of("saga-pure")); }
            @Override public NodeHandler create(GraphNode node) {
                return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            }
        });
    }

    private static final class TestSource implements ai.ravenroot.api.node.NodeBehavior, ai.ravenroot.api.node.InboundSourceCapable {
        @Override public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.saga.source", "Saga test source", "Test", "A real inbound source", "actor", false,
                    List.of(), Set.of("inbound-source")).withNature(ai.ravenroot.api.catalog.NodeRuntimeNature.SOURCE,
                    Set.of(ai.ravenroot.api.catalog.NodeRuntimeNature.SOURCE));
        }
        @Override public ai.ravenroot.api.node.NodeAction create(ai.ravenroot.api.node.NodeConfiguration node) {
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        }
        @Override public ai.ravenroot.api.deployment.InboundSource createSource(ai.ravenroot.api.node.NodeConfiguration node,
                ai.ravenroot.api.deployment.InboundSourceContext context) {
            return new ai.ravenroot.api.deployment.InboundSource() {
                @Override public java.util.concurrent.CompletionStage<Void> start(ai.ravenroot.api.deployment.InboundSourceContext started) {
                    return CompletableFuture.completedFuture(null);
                }
                @Override public java.util.concurrent.CompletionStage<Void> stop() { return CompletableFuture.completedFuture(null); }
            };
        }
    }
}
