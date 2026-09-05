package ai.ravenroot.extensions.discord;

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

class DiscordGraphMlRoundTripTest {
    @Test void roundTripsOnlyOpaqueProfileAndTighteningProperties() throws Exception {
        var definition = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("receive", NodeKind.BEHAVIOR, DiscordBehaviorDescriptors.INTERACTIONS,
                        Map.of("discordProfile", "operations")),
                new GraphNode("send", NodeKind.BEHAVIOR, DiscordBehaviorDescriptors.SEND,
                        Map.of("discordProfile", "operations", "channelId", "323456789012345678",
                                "requestTimeoutMs", "1500", "maxAttachmentBytes", "4096",
                                "maxAttachments", "1", "maxConcurrency", "1", "retries", "0")),
                GraphNode.end("end")), List.of());
        byte[] xml;
        try (var graph = GraphManager.from(definition); var output = new ByteArrayOutputStream()) {
            graph.writeGraphMl(output); xml = output.toByteArray();
        }
        String serialized = new String(xml, StandardCharsets.UTF_8);
        assertFalse(serialized.contains("discord-bot-token")); assertFalse(serialized.contains("publicKey"));
        assertFalse(serialized.contains("https://discord.com"));
        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(xml))) {
            assertEquals(DiscordBehaviorDescriptors.INTERACTIONS, reread.definition().node("receive").behavior());
            assertEquals("operations", reread.definition().node("receive").properties().get("discordProfile"));
            assertEquals("323456789012345678", reread.definition().node("send").properties().get("channelId"));
            assertEquals("0", reread.definition().node("send").properties().get("retries"));
        }
    }
}
