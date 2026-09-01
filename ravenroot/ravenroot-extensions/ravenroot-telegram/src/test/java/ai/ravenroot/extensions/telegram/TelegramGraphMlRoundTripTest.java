package ai.ravenroot.extensions.telegram;

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

class TelegramGraphMlRoundTripTest {
    @Test void roundTripsOnlyTheOpaqueProfileAndTighteningProperties() throws Exception {
        String secret = TelegramTestSupport.TOKEN;
        var definition = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("telegram", NodeKind.BEHAVIOR, "telegram.send", Map.of(
                        "botProfile", "production-alerts", "requestTimeoutMs", "1500", "maxTextChars", "512",
                        "maxMediaBytes", "4096", "maxButtons", "4", "maxConcurrency", "2", "retries", "1")),
                new GraphNode("answer", NodeKind.BEHAVIOR, "telegram.answer.callback", Map.of(
                        "botProfile", "production-alerts", "requestTimeoutMs", "1200")),
                new GraphNode("edit", NodeKind.BEHAVIOR, "telegram.edit.message", Map.of(
                        "botProfile", "production-alerts", "maxTextChars", "512", "maxButtons", "4")),
                new GraphNode("delete", NodeKind.BEHAVIOR, "telegram.delete.message", Map.of(
                        "botProfile", "production-alerts", "maxConcurrency", "2")),
                GraphNode.error("error"), GraphNode.end("end")), List.of());
        byte[] xml;
        try (var graph = GraphManager.from(definition); var output = new ByteArrayOutputStream()) {
            graph.writeGraphMl(output);
            xml = output.toByteArray();
        }
        String serialized = new String(xml, StandardCharsets.UTF_8);
        assertFalse(serialized.contains(secret));
        assertFalse(serialized.contains("api.telegram.org"));
        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(xml))) {
            var node = reread.definition().node("telegram");
            assertEquals("telegram.send", node.behavior());
            assertEquals("production-alerts", node.properties().get("botProfile"));
            assertEquals("1", node.properties().get("retries"));
            assertEquals("2", node.properties().get("maxConcurrency"));
            assertEquals("telegram.answer.callback", reread.definition().node("answer").behavior());
            assertEquals("telegram.edit.message", reread.definition().node("edit").behavior());
            assertEquals("telegram.delete.message", reread.definition().node("delete").behavior());
            assertTrue(reread.definition().node("answer").properties().keySet().stream()
                    .noneMatch(name -> name.matches("(?i).*(token|secret|credential|url|host|method|chat).*")));
        }
    }
}
