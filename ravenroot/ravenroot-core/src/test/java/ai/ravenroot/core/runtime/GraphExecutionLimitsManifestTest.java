package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifestDifference;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.core.manifest.ExecutionManifestIncompatibleException;
import ai.ravenroot.core.manifest.ExecutionManifestResolver;
import ai.ravenroot.core.manifest.ExecutionManifestService;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.persistence.InMemoryExecutionManifestStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphExecutionLimitsManifestTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
    private static final GraphContentId CONTENT =
            GraphContentId.of("canonical graph".getBytes(StandardCharsets.UTF_8));

    @Test
    void sharedRunnerUsesEachProcessPinnedGraphBudget() {
        GraphExecutionLimits startup = GraphExecutionLimits.fromEnvironment(
                Map.of(GraphExecutionLimits.MAX_PAYLOAD_TEXT_LENGTH_VARIABLE, "32"));
        GraphExecutionLimits narrower = GraphExecutionLimits.fromEnvironment(
                Map.of(GraphExecutionLimits.MAX_PAYLOAD_TEXT_LENGTH_VARIABLE, "16"));
        GraphExecutionLimits wider = GraphExecutionLimits.fromEnvironment(
                Map.of(GraphExecutionLimits.MAX_PAYLOAD_TEXT_LENGTH_VARIABLE, "256"));
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "end")));
        var engine = new SameThreadExecutionEngine();
        var registry = new BehaviorRegistry();
        var highKey = new ExecutionKey("tenant-a", UUID.randomUUID());
        var lowKey = new ExecutionKey("tenant-a", UUID.randomUUID());
        var highPolicy = resolver(wider).manifestFor(highKey, CONTENT,
                GraphDefinitionIdentity.forSubmission(CONTENT), ExecutionPolicy.STANDARD, CLOCK.instant())
                .operationalPolicy();
        var lowPolicy = resolver(narrower).manifestFor(lowKey, CONTENT,
                GraphDefinitionIdentity.forSubmission(CONTENT), ExecutionPolicy.STANDARD, CLOCK.instant())
                .operationalPolicy();

        try (var runner = new GraphRunner(GraphManager.from(graph), engine, registry,
                new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                UnknownBehaviorPolicy.passThrough(), ExecutionPolicy.STANDARD, startup)) {
            String widePayload = "x".repeat(64);
            assertEquals(widePayload, runner.execute(TestIdentities.TENANT_A, highKey.processInstanceId(),
                    UUID.randomUUID(), widePayload, CONTENT.value(), "deployment", "high", null, highPolicy)
                    .toCompletableFuture().join().payload());
            String narrowPayload = "x".repeat(24);
            ai.ravenroot.api.payload.PayloadException refused = assertThrows(
                    ai.ravenroot.api.payload.PayloadException.class,
                    () -> runner.execute(TestIdentities.TENANT_A, lowKey.processInstanceId(),
                            UUID.randomUUID(), narrowPayload, CONTENT.value(), "deployment", "low", null,
                            lowPolicy));
            assertEquals(ai.ravenroot.api.payload.PayloadException.Reason.TEXT_TOO_LONG, refused.reason());
        }
    }

    @ParameterizedTest
    @MethodSource("structuralBindings")
    void everyStructuralBindingChangesNewPinsButRecoveryRestoresTheAcceptedValues(
            String name, int configured, ToIntFunction<GraphExecutionLimits> value) {
        GraphExecutionLimits changed = GraphExecutionLimits.fromEnvironment(
                Map.of(name, Integer.toString(configured)));
        assertEquals(configured, value.applyAsInt(changed));
        assertEquals(1, differingLimits(GraphExecutionLimits.DEFAULTS, changed),
                "one environment variable must change one logical limit");

        var key = new ExecutionKey("tenant-a", UUID.randomUUID());
        var store = new InMemoryExecutionManifestStore(CLOCK);
        var accepting = new ExecutionManifestService(store, resolver(GraphExecutionLimits.DEFAULTS), CLOCK);
        var accepted = accepting.pin(key, CONTENT, GraphDefinitionIdentity.forSubmission(CONTENT),
                ExecutionPolicy.STANDARD);
        String acceptedLimitsDigest = accepted.manifest().runtime().executionLimitsDigest();

        var changedRuntime = new ExecutionManifestService(store, resolver(changed), CLOCK);
        assertEquals(accepted, changedRuntime.verify(key, ExecutionPolicy.STANDARD));
        GraphExecutionLimits restored = ExecutionManifestResolver.graphExecutionLimits(
                changedRuntime.resolvePolicy(key, ExecutionPolicy.STANDARD, List.of()));
        assertEquals(0, differingLimits(GraphExecutionLimits.DEFAULTS, restored),
                "recovery consumes the accepted tuple after the current environment changes");
        assertNotEquals(acceptedLimitsDigest,
                resolver(changed).manifestFor(key, CONTENT, GraphDefinitionIdentity.forSubmission(CONTENT),
                        ExecutionPolicy.STANDARD, accepted.manifest().pinnedAt())
                        .runtime().executionLimitsDigest());

        var retained = store.load(key).toCompletableFuture().join();
        assertEquals(accepted, retained);
        assertEquals(acceptedLimitsDigest, retained.manifest().runtime().executionLimitsDigest());
    }

    private static ExecutionManifestResolver resolver(GraphExecutionLimits limits) {
        return ExecutionManifestResolver.from(new SameThreadExecutionEngine(), Set.of(),
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                UnknownBehaviorPolicy.passThrough(), limits, null);
    }

    private static int differingLimits(GraphExecutionLimits left, GraphExecutionLimits right) {
        List<Long> leftValues = limitValues(left);
        List<Long> rightValues = limitValues(right);
        return (int) IntStream.range(0, leftValues.size())
                .filter(index -> !leftValues.get(index).equals(rightValues.get(index)))
                .count();
    }

    private static List<Long> limitValues(GraphExecutionLimits limits) {
        return List.of(
                (long) limits.graphMl().maxBytes(),
                (long) limits.graphMl().maxNodes(),
                (long) limits.graphMl().maxEdges(),
                (long) limits.graphMl().maxProperties(),
                (long) limits.graphMl().maxDepth(),
                (long) limits.graphMl().maxStringLength(),
                (long) limits.graphMl().maxKeys(),
                (long) limits.graphMl().maxElements(),
                (long) limits.graphMl().maxAttributes(),
                (long) limits.graphMl().maxNamespaceDeclarations(),
                (long) limits.payload().maxEncodedBytes(),
                (long) limits.payload().maxDepth(),
                (long) limits.payload().maxCollectionSize(),
                (long) limits.payload().maxValueCount(),
                (long) limits.payload().maxTextLength(),
                (long) limits.payload().maxKeyLength(),
                (long) limits.maxFanOut(),
                (long) limits.maxResidentActors(),
                (long) limits.maxLiveActorsPerTraversal(),
                (long) limits.maxInFlightHopsPerTraversal(),
                (long) limits.maxQueuedAdmissionsPerNode(),
                limits.maxTraversalSteps(),
                limits.maxAmplifiedDeliveries(),
                limits.maxCumulativePayloadBytes(),
                (long) limits.maxRecoveryDeliveriesPerAttempt());
    }

    private static Stream<Arguments> structuralBindings() {
        return Stream.of(
                Arguments.of(GraphExecutionLimits.MAX_GRAPHML_DEPTH_VARIABLE, 65,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.graphMl().maxDepth()),
                Arguments.of(GraphExecutionLimits.MAX_GRAPHML_STRING_LENGTH_VARIABLE, 1_048_577,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.graphMl().maxStringLength()),
                Arguments.of(GraphExecutionLimits.MAX_GRAPHML_KEYS_VARIABLE, 4_097,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.graphMl().maxKeys()),
                Arguments.of(GraphExecutionLimits.MAX_GRAPHML_ELEMENTS_VARIABLE, 250_001,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.graphMl().maxElements()),
                Arguments.of(GraphExecutionLimits.MAX_GRAPHML_ATTRIBUTES_VARIABLE, 500_001,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.graphMl().maxAttributes()),
                Arguments.of(GraphExecutionLimits.MAX_GRAPHML_NAMESPACE_DECLARATIONS_VARIABLE, 10_001,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.graphMl().maxNamespaceDeclarations()),
                Arguments.of(GraphExecutionLimits.MAX_PAYLOAD_DEPTH_VARIABLE, 33,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.payload().maxDepth()),
                Arguments.of(GraphExecutionLimits.MAX_PAYLOAD_COLLECTION_SIZE_VARIABLE, 1_001,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.payload().maxCollectionSize()),
                Arguments.of(GraphExecutionLimits.MAX_PAYLOAD_VALUE_COUNT_VARIABLE, 10_001,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.payload().maxValueCount()),
                Arguments.of(GraphExecutionLimits.MAX_PAYLOAD_TEXT_LENGTH_VARIABLE, 32_769,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.payload().maxTextLength()),
                Arguments.of(GraphExecutionLimits.MAX_PAYLOAD_KEY_LENGTH_VARIABLE, 257,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.payload().maxKeyLength()));
    }
}
