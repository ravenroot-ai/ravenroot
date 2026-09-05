package ai.ravenroot.server;

import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.core.graph.GraphMlLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServedConfigurationTest {

    @Test
    void derivesTheExactGraphBudgetFromTheTypedCoreLimits() {
        var defaults = GraphMlLimits.DEFAULTS;
        var graphMl = new GraphMlLimits(32 * 1024 * 1024, defaults.maxNodes(), defaults.maxEdges(),
                defaults.maxProperties(), defaults.maxDepth(), defaults.maxStringLength(), defaults.maxKeys(),
                defaults.maxElements(), defaults.maxAttributes(), defaults.maxNamespaceDeclarations());

        assertEquals("{\"schemaVersion\":1,\"graphDocumentMaxBytes\":33554432}",
                ServedConfiguration.from(graphMl).json());
    }

    @Test
    void refusesUnknownSchemasAndBoundsOutsideTheSharedSafetyContract() {
        assertThrows(IllegalArgumentException.class, () -> new ServedConfiguration(2, 1));
        assertThrows(IllegalArgumentException.class, () -> new ServedConfiguration(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ServedConfiguration(
                1, GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES + 1));
    }
}
