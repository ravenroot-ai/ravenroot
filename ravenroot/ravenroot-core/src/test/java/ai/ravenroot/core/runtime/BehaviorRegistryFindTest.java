package ai.ravenroot.core.runtime;

import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorRegistryFindTest {

    @Test
    void findsAConfigurationIndependentBehavior() {
        assertTrue(BehaviorRegistry.standard().find("log").isPresent());
    }

    @Test
    void returnsEmptyForAnUnregisteredBehavior() {
        assertTrue(BehaviorRegistry.standard().find("not-registered").isEmpty());
    }

    @ParameterizedTest
    @MethodSource("propertyDependentBehaviors")
    void refusesAPropertyDependentBehaviorWithoutItsConfiguration(String behavior, String property,
                                                                    String value) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> BehaviorRegistry.standard().find(behavior));

        assertEquals("Node registry-lookup requires property '" + property + "'", failure.getMessage());
    }

    @ParameterizedTest
    @MethodSource("propertyDependentBehaviors")
    void createsAPropertyDependentBehaviorFromAConfiguredNode(String behavior, String property, String value) {
        var node = new GraphNode("configured-" + behavior, NodeKind.BEHAVIOR, behavior, Map.of(property, value));

        assertTrue(BehaviorRegistry.standard().create(node).isPresent());
    }

    private static Stream<Arguments> propertyDependentBehaviors() {
        return Stream.of(
                Arguments.of("template", "template", "{{payload}}"),
                Arguments.of("cel-transform", "expression", "payload")
        );
    }
}
