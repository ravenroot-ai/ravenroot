package ai.ravenroot.server;

import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.core.graph.GraphMlLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServedConfigurationTest {

    @Test
    void derivesTheExactGraphBudgetFromTheTypedCoreLimits() {
        var defaults = GraphMlLimits.DEFAULTS;
        var graphMl = new GraphMlLimits(32 * 1024 * 1024, defaults.maxNodes(), defaults.maxEdges(),
                defaults.maxProperties(), defaults.maxDepth(), defaults.maxStringLength(), defaults.maxKeys(),
                defaults.maxElements(), defaults.maxAttributes(), defaults.maxNamespaceDeclarations());

        assertEquals("{\"schemaVersion\":1,\"graphDocumentMaxBytes\":33554432}",
                ServedConfiguration.from(graphMl).json());
        assertEquals("{\"schemaVersion\":1,\"graphDocumentMaxBytes\":33554432,\"workspace\":{\"tenantId\":\"tenant-a\"}}",
                ServedConfiguration.from(graphMl).json("tenant-a"));
    }

    @Test
    void serializesTheAuthenticatedWorkspaceTenantAsAJsonString() {
        var configuration = ServedConfiguration.from(GraphMlLimits.DEFAULTS);

        assertEquals("{\"schemaVersion\":1,\"graphDocumentMaxBytes\":10485760,\"workspace\":{\"tenantId\":\"tenant-\\\"\\\\\\n\\u0001\"}}",
                configuration.json("tenant-\"\\\n\u0001"));
        assertTrue(configuration.json(ai.ravenroot.api.persistence.HumanTaskPolicy.DEFAULTS, "tenant-a")
                .contains("\"workspace\":{\"tenantId\":\"tenant-a\"}"));
    }

    @Test
    void refusesUnknownSchemasAndBoundsOutsideTheSharedSafetyContract() {
        assertThrows(IllegalArgumentException.class, () -> new ServedConfiguration(2, 1));
        assertThrows(IllegalArgumentException.class, () -> new ServedConfiguration(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ServedConfiguration(
                1, GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES + 1));
    }
}
