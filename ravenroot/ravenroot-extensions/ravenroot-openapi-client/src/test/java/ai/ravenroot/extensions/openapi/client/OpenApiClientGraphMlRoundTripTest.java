package ai.ravenroot.extensions.openapi.client;

import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiClientGraphMlRoundTripTest {
    @Test void graphCarriesOnlyOpaqueProfileOperationAndTightening() throws Exception {
        Map<String, Object> properties = Map.of("apiProfile", "pets", "operationId", "getPet",
                "timeoutMs", "1000", "maxRequestBytes", "2048", "maxResponseBytes", "4096", "maxConcurrency", "1");
        var definition = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("call", NodeKind.BEHAVIOR, OpenApiCallNodeBehavior.BEHAVIOR, properties),
                GraphNode.error("error"), GraphNode.end("end")), List.of());
        byte[] xml;
        try (var graph = GraphManager.from(definition); var out = new ByteArrayOutputStream()) {
            graph.writeGraphMl(out); xml = out.toByteArray();
        }
        String text = new String(xml, StandardCharsets.UTF_8);
        assertFalse(text.contains("api.example.test")); assertFalse(text.contains("pets-token"));
        assertFalse(text.matches("(?s).*(credentialBindingId|credentialReference|specBase64|origin).*"));
        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(xml))) {
            assertEquals(properties, reread.definition().node("call").properties());
        }
    }
}
