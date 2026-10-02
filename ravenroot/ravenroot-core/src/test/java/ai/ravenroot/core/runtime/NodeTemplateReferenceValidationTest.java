package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.NodeTemplateReferenceUnavailableException;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodeTemplateReferenceValidationTest {
    @Test
    void classifiesAnAbsentBehaviorAsAnUnavailableDestinationReference() {
        var failure = assertThrows(NodeTemplateReferenceUnavailableException.class,
                () -> new BehaviorRegistry().validateTemplateReferences(node("missing"), "tenant-a"));

        assertEquals("node template reference is unavailable", failure.getMessage());
    }

    @Test
    void preservesMalformedNodeConfigurationAsAnInvalidAuthoredPayload() {
        var registry = new BehaviorRegistry().registerFactory(new ProbeFactory(true, false));

        var failure = assertThrows(IllegalArgumentException.class,
                () -> registry.validateTemplateReferences(node("test.palette-reference"), "tenant-a"));

        assertEquals("malformed-node-canary", failure.getMessage());
    }

    @Test
    void classifiesOnlyDestinationLookupFailureAndKeepsItsDetailPrivate() {
        var registry = new BehaviorRegistry().registerFactory(new ProbeFactory(false, true));

        var failure = assertThrows(NodeTemplateReferenceUnavailableException.class,
                () -> registry.validateTemplateReferences(node("test.palette-reference"), "tenant-a"));

        assertEquals("node template reference is unavailable", failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals("private-reference-canary", failure.getCause().getMessage());
    }

    private static GraphNode node(String behavior) {
        return new GraphNode("saved", NodeKind.BEHAVIOR, behavior, Map.of());
    }

    private record ProbeFactory(boolean malformed, boolean unavailable) implements NodeBehaviorFactory {
        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.palette-reference", "Palette reference", "General",
                    "Test-only reference validator", "actor", false, List.of(), Set.of());
        }

        @Override
        public void validate(GraphNode node) {
            if (malformed) throw new IllegalArgumentException("malformed-node-canary");
        }

        @Override
        public void validateTemplateReferences(GraphNode node, String tenantId) {
            if (unavailable) throw new IllegalArgumentException("private-reference-canary");
        }

        @Override
        public NodeHandler create(GraphNode node) {
            return message -> CompletableFuture.completedFuture(
                    new NodeResult("continue", message.payload(), Map.of()));
        }
    }
}
