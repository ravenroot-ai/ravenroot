package ai.ravenroot.core.runtime;

import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.core.graph.GraphMlLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphExecutionLimitsTest {

    @Test
    void graphDocumentBytesDefaultAndMayBeNarrowedOrRaisedThroughTheCanonicalVariable() {
        assertEquals(GraphDefinitionStore.DEFAULT_MAX_DEFINITION_BYTES,
                GraphExecutionLimits.fromEnvironment(Map.of()).graphMl().maxBytes());
        assertEquals(4_096, GraphExecutionLimits.fromEnvironment(Map.of(
                GraphExecutionLimits.MAX_GRAPHML_BYTES_VARIABLE, "4096")).graphMl().maxBytes());
        assertEquals(GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES,
                GraphExecutionLimits.fromEnvironment(Map.of(
                        GraphExecutionLimits.MAX_GRAPHML_BYTES_VARIABLE,
                        Integer.toString(GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES)))
                        .graphMl().maxBytes());
    }

    @Test
    void environmentCanNarrowOrRaiseExistingLimitsOnlyWithinSupportedCeilings() {
        GraphExecutionLimits limits = GraphExecutionLimits.fromEnvironment(Map.of(
                GraphExecutionLimits.MAX_NODES_VARIABLE, "17",
                GraphExecutionLimits.MAX_FAN_OUT_VARIABLE, "7",
                GraphExecutionLimits.MAX_TRAVERSAL_STEPS_VARIABLE, "200000",
                GraphExecutionLimits.MAX_RECOVERY_DELIVERIES_VARIABLE, "3"));

        assertEquals(17, limits.graphMl().maxNodes());
        assertEquals(7, limits.maxFanOut());
        assertEquals(200_000, limits.maxTraversalSteps());
        assertEquals(3, limits.maxRecoveryDeliveriesPerAttempt());
    }

    @ParameterizedTest
    @MethodSource("structuralBindings")
    void everyStructuralLimitDefaultsWhenAbsentOrBlankAndAcceptsItsCanonicalVariable(
            String name, int configured, ToIntFunction<GraphExecutionLimits> value) {
        int defaultValue = value.applyAsInt(GraphExecutionLimits.DEFAULTS);
        assertEquals(defaultValue, value.applyAsInt(GraphExecutionLimits.fromEnvironment(Map.of())));
        assertEquals(defaultValue, value.applyAsInt(GraphExecutionLimits.fromEnvironment(Map.of(name, " \t "))));

        GraphExecutionLimits resolved = GraphExecutionLimits.fromEnvironment(
                Map.of(name, " " + configured + " "));
        assertEquals(configured, value.applyAsInt(resolved));
        assertNotEquals(GraphExecutionLimits.DEFAULTS, resolved);
    }

    @ParameterizedTest
    @MethodSource("structuralCeilings")
    void everyStructuralLimitAcceptsItsExactSafetyCeiling(String name, int ceiling,
                                                           ToIntFunction<GraphExecutionLimits> value) {
        assertEquals(ceiling, value.applyAsInt(GraphExecutionLimits.fromEnvironment(
                Map.of(name, Integer.toString(ceiling)))));
    }

    @ParameterizedTest
    @MethodSource("structuralCeilings")
    void everyStructuralLimitRejectsValuesOutsideItsSupportedRange(String name, int ceiling,
                                                                    ToIntFunction<GraphExecutionLimits> ignored) {
        assertInvalid(name, "0", ceiling);
        assertInvalid(name, "-1", ceiling);
        assertInvalid(name, Long.toString((long) ceiling + 1), ceiling);
    }

    @Test
    void integerValuesAreValidatedBeforeNarrowingAndDiagnosticsRetainNoRawInputOrCause() {
        assertInvalid(GraphExecutionLimits.MAX_FAN_OUT_VARIABLE, "-4294967295",
                GraphExecutionLimits.HARD_MAX_FAN_OUT);
        assertInvalid(GraphExecutionLimits.MAX_FAN_OUT_VARIABLE, "9223372036854775808",
                GraphExecutionLimits.HARD_MAX_FAN_OUT);

        String raw = "operator-secret-not-a-number";
        var failure = assertInvalid(GraphExecutionLimits.MAX_FAN_OUT_VARIABLE, raw,
                GraphExecutionLimits.HARD_MAX_FAN_OUT);
        assertTrue(!failure.getMessage().contains(raw));
        assertNull(failure.getCause());
    }

    @Test
    void existingLongAndIntegerBindingsUseTheSameBoundedCauseFreeParser() {
        assertInvalid(GraphExecutionLimits.MAX_GRAPHML_BYTES_VARIABLE, "0",
                GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES);
        assertInvalid(GraphExecutionLimits.MAX_TRAVERSAL_STEPS_VARIABLE,
                Long.toString(GraphExecutionLimits.HARD_MAX_TRAVERSAL_STEPS + 1),
                GraphExecutionLimits.HARD_MAX_TRAVERSAL_STEPS);
        assertInvalid(GraphExecutionLimits.MAX_CUMULATIVE_PAYLOAD_BYTES_VARIABLE, "not-a-number",
                GraphExecutionLimits.HARD_MAX_CUMULATIVE_PAYLOAD_BYTES);
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

    private static Stream<Arguments> structuralCeilings() {
        return Stream.of(
                Arguments.of(GraphExecutionLimits.MAX_GRAPHML_DEPTH_VARIABLE, GraphMlLimits.HARD_MAX_DEPTH,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.graphMl().maxDepth()),
                Arguments.of(GraphExecutionLimits.MAX_GRAPHML_STRING_LENGTH_VARIABLE,
                        GraphMlLimits.HARD_MAX_STRING_LENGTH,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.graphMl().maxStringLength()),
                Arguments.of(GraphExecutionLimits.MAX_GRAPHML_KEYS_VARIABLE, GraphMlLimits.HARD_MAX_KEYS,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.graphMl().maxKeys()),
                Arguments.of(GraphExecutionLimits.MAX_GRAPHML_ELEMENTS_VARIABLE, GraphMlLimits.HARD_MAX_ELEMENTS,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.graphMl().maxElements()),
                Arguments.of(GraphExecutionLimits.MAX_GRAPHML_ATTRIBUTES_VARIABLE,
                        GraphMlLimits.HARD_MAX_ATTRIBUTES,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.graphMl().maxAttributes()),
                Arguments.of(GraphExecutionLimits.MAX_GRAPHML_NAMESPACE_DECLARATIONS_VARIABLE,
                        GraphMlLimits.HARD_MAX_NAMESPACE_DECLARATIONS,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.graphMl().maxNamespaceDeclarations()),
                Arguments.of(GraphExecutionLimits.MAX_PAYLOAD_DEPTH_VARIABLE, PayloadLimits.HARD_MAX_DEPTH,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.payload().maxDepth()),
                Arguments.of(GraphExecutionLimits.MAX_PAYLOAD_COLLECTION_SIZE_VARIABLE,
                        PayloadLimits.HARD_MAX_COLLECTION_SIZE,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.payload().maxCollectionSize()),
                Arguments.of(GraphExecutionLimits.MAX_PAYLOAD_VALUE_COUNT_VARIABLE,
                        PayloadLimits.HARD_MAX_VALUE_COUNT,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.payload().maxValueCount()),
                Arguments.of(GraphExecutionLimits.MAX_PAYLOAD_TEXT_LENGTH_VARIABLE,
                        PayloadLimits.HARD_MAX_TEXT_LENGTH,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.payload().maxTextLength()),
                Arguments.of(GraphExecutionLimits.MAX_PAYLOAD_KEY_LENGTH_VARIABLE,
                        PayloadLimits.HARD_MAX_KEY_LENGTH,
                        (ToIntFunction<GraphExecutionLimits>) limits -> limits.payload().maxKeyLength()));
    }

    private static IllegalArgumentException assertInvalid(String name, String raw, long ceiling) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> GraphExecutionLimits.fromEnvironment(Map.of(name, raw)));
        assertEquals(name + " must be a whole number from 1 through " + ceiling, failure.getMessage());
        assertNull(failure.getCause());
        return failure;
    }
}
