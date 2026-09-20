package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.PropertyConditionOperator;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BigIntOpAdmissionTest {
    private final BehaviorRegistry registry = BehaviorRegistry.standard();

    @Test
    void standardCatalogPublishesClosedPureInProcessContract() {
        var descriptor = registry.descriptor("bigint-op").orElseThrow();
        assertEquals(SetLike.of("deterministic", "pure", "in-process"), SetLike.of(descriptor.capabilities()));
        assertEquals(List.of("copy", "add", "subtract", "multiply", "floor-divide", "modulo",
                        "equal", "less-than"), property(descriptor.properties(), "operation").allowedValues());
        NodePropertyDescriptor right = property(descriptor.properties(), "right");
        assertEquals("operation", right.requiredWhen().property());
        assertEquals(PropertyConditionOperator.ONE_OF, right.requiredWhen().operator());
        assertTrue(right.requiredWhen().operands().contains("add"));
        assertTrue(right.visibleWhen().holds("less-than"));
        assertTrue(!right.visibleWhen().holds("copy"));
    }

    @Test
    void admissionRejectsUnknownOperationsMalformedOperandsTargetsAndWrongArity() {
        assertRejected(properties("power", "literal:2", "literal:8", "answer"), "operation");
        assertRejected(properties("copy", "2", null, "answer"), "left");
        assertRejected(properties("copy", "literal:1.5", null, "answer"), "left");
        assertRejected(properties("copy", "field:", null, "answer"), "left");
        assertRejected(properties("copy", "literal:1", null, ""), "target");
        assertRejected(properties("copy", "literal:1", null, "ravenroot.security.tenantId"), "target");
        assertRejected(properties("copy", "literal:1", "literal:2", "answer"), "right");
        assertRejected(properties("add", "literal:1", null, "answer"), "right");
    }

    @Test
    void admissionAcceptsEveryOperationAndFieldOrLiteralReferences() {
        assertDoesNotThrow(() -> admit(properties("copy", "field:counter", null, "counter")));
        for (String operation : List.of("add", "subtract", "multiply", "floor-divide", "modulo",
                "equal", "less-than")) {
            assertDoesNotThrow(() -> admit(properties(operation, "field:left", "literal:-7", "result")),
                    operation);
        }
    }

    @Test
    void oversizedUnknownChoiceFailsBeforeItsValueCanReachTheDiagnostic() {
        String untrusted = "x".repeat(100_000);
        var failure = assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> admit(properties(untrusted, "literal:1", null, "answer")));
        assertTrue(failure.getMessage().length() < 512, "diagnostic length=" + failure.getMessage().length());
        assertTrue(!failure.getMessage().contains(untrusted));
    }

    private void assertRejected(Map<String, Object> properties, String namedProperty) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> admit(properties));
        assertTrue(failure.getMessage().contains("'" + namedProperty + "'"), failure.getMessage());
        assertTrue(failure.getMessage().length() < 1_024, "diagnostic must be bounded");
    }

    private void admit(Map<String, Object> properties) {
        GraphDefinition graph = graph(properties);
        new BehaviorPropertySchema(registry).validate(graph);
        new BehaviorCapabilityPreflight(registry).validate(graph);
    }

    private static GraphDefinition graph(Map<String, Object> properties) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("math", NodeKind.BEHAVIOR, "bigint-op", properties),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "math"), GraphEdge.to("math", "end")));
    }

    private static Map<String, Object> properties(String operation, String left, String right, String target) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("operation", operation);
        properties.put("left", left);
        if (right != null) properties.put("right", right);
        properties.put("target", target);
        return properties;
    }

    private static NodePropertyDescriptor property(List<NodePropertyDescriptor> properties, String name) {
        return properties.stream().filter(property -> property.name().equals(name)).findFirst().orElseThrow();
    }

    /** Order-insensitive assertion helper without exposing mutable sets in the test. */
    private record SetLike(java.util.Set<String> values) {
        static SetLike of(java.util.Collection<String> values) {
            return new SetLike(java.util.Set.copyOf(values));
        }

        static SetLike of(String... values) {
            return new SetLike(java.util.Set.of(values));
        }
    }
}
