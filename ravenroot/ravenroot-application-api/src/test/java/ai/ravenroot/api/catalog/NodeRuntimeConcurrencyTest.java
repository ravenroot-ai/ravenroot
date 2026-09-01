package ai.ravenroot.api.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeRuntimeConcurrencyTest {
    @Test
    void defaultAndCeilingArePositiveAndOrdered() {
        assertTrue(NodeRuntimeConcurrency.DEFAULT.defaultValue() > 0);
        assertTrue(NodeRuntimeConcurrency.DEFAULT.ceiling()
                >= NodeRuntimeConcurrency.DEFAULT.defaultValue());
        assertThrows(IllegalArgumentException.class, () -> new NodeRuntimeConcurrency(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new NodeRuntimeConcurrency(2, 1));
    }

    @Test
    void graphValueIsExactAndIndependentOfNature() {
        NodeTypeDescriptor descriptor = descriptor();
        assertEquals(descriptor.runtimeConcurrency().defaultValue(),
                NodeRuntimeMaxConcurrencyProperty.effectiveValue(descriptor, Map.of()));
        assertEquals(1, NodeRuntimeMaxConcurrencyProperty.effectiveValue(descriptor,
                Map.of(NodeRuntimeMaxConcurrencyProperty.NAME, "1")));
        assertThrows(IllegalArgumentException.class,
                () -> NodeRuntimeMaxConcurrencyProperty.parse("1.0"));
        assertThrows(IllegalArgumentException.class,
                () -> NodeRuntimeMaxConcurrencyProperty.parse("-1"));
    }

    @Test
    void platformOwnedPropertyCannotBeRedefinedByNodePackage() {
        var colliding = new NodeTypeDescriptor("collision", "collision", "Test", "d", "actor", false,
                List.of(NodePropertyDescriptor.optional(NodeRuntimeMaxConcurrencyProperty.NAME,
                        "Concurrency", NodePropertyType.INTEGER, "d", "1")), Set.of());
        assertThrows(IllegalArgumentException.class, () -> NodeTypeDescriptorValidator.validate(colliding));
    }

    private static NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor("test", "test", "Test", "d", "actor", false, List.of(), Set.of())
                .withNature(NodeRuntimeNature.WORKER,
                        Set.of(NodeRuntimeNature.WORKER, NodeRuntimeNature.TRAVERSAL));
    }
}
