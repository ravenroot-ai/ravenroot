package ai.ravenroot.extensions.openapi.server;

import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OpenApiServerGraphMlRoundTripTest {
    @ParameterizedTest
    @ValueSource(strings = {OpenApiReceiveNodeBehavior.BEHAVIOR, OpenApiRequestReplyNodeBehavior.BEHAVIOR})
    void graphCarriesOnlyOpaqueProfileOperationSubsetAndLowerCeilings(String behavior) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>(Map.of("apiProfile", "orders",
                "operations", "createOrder", "deadlineMs", "750", "maxRequestBytes", "2048",
                "maxIdempotencyBytes", "64", "maxConcurrency", "1"));
        if (behavior.equals(OpenApiRequestReplyNodeBehavior.BEHAVIOR)) values.put("maxResponseBytes", "512");
        Map<String, Object> properties = Map.copyOf(values);
        var definition = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("receive", NodeKind.BEHAVIOR, behavior, properties),
                GraphNode.error("error"), GraphNode.end("end")), List.of());
        byte[] graphMl;
        try (var graph = GraphManager.from(definition); var output = new ByteArrayOutputStream()) {
            graph.writeGraphMl(output); graphMl = output.toByteArray();
        }
        String text = new String(graphMl, StandardCharsets.UTF_8);
        assertFalse(text.contains("specBase64")); assertFalse(text.contains("specSha256"));
        assertFalse(text.contains("/managed/openapi")); assertFalse(text.contains("requiredScopes"));
        assertFalse(text.contains("idempotency-key")); assertFalse(text.contains("graph:execute"));
        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(graphMl))) {
            assertEquals(properties, reread.definition().node("receive").properties());
        }
    }
}
