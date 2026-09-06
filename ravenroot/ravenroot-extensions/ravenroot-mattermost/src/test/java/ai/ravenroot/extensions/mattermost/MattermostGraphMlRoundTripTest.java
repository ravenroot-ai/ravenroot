package ai.ravenroot.extensions.mattermost;

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

class MattermostGraphMlRoundTripTest {
    @Test void roundTripsOnlyOpaqueProfileAndTighteningProperties() throws Exception {
        var definition = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("source", NodeKind.BEHAVIOR, MattermostBehaviorDescriptors.OUTGOING_WEBHOOK,
                        Map.of("mattermostProfile", "operations")),
                new GraphNode("send", NodeKind.BEHAVIOR, MattermostBehaviorDescriptors.SEND,
                        Map.of("mattermostProfile", "operations", "channelId", MattermostTestSupport.CHANNEL,
                                "requestTimeoutMs", "1500", "maxTextChars", "1000",
                                "maxConcurrency", "1", "retries", "0")), GraphNode.end("end")), List.of());
        byte[] xml;
        try (var graph = GraphManager.from(definition); var output = new ByteArrayOutputStream()) {
            graph.writeGraphMl(output); xml = output.toByteArray();
        }
        String serialized = new String(xml, StandardCharsets.UTF_8);
        assertFalse(serialized.contains("mattermost-bot-token")); assertFalse(serialized.contains("outgoing-token"));
        assertFalse(serialized.contains("mattermost.example.test"));
        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(xml))) {
            assertEquals(MattermostBehaviorDescriptors.OUTGOING_WEBHOOK,
                    reread.definition().node("source").behavior());
            assertEquals("operations", reread.definition().node("source").properties().get("mattermostProfile"));
            assertEquals(MattermostTestSupport.CHANNEL,
                    reread.definition().node("send").properties().get("channelId"));
        }
    }
}
