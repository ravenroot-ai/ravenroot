package ai.ravenroot.extensions.slack;

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

class SlackGraphMlRoundTripTest {
    @Test void roundTripsOnlyOpaqueProfileAndTighteningProperties() throws Exception {
        var definition = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("events", NodeKind.BEHAVIOR, SlackBehaviorDescriptors.EVENTS,
                        Map.of("slackProfile", "operations")),
                new GraphNode("commands", NodeKind.BEHAVIOR, SlackBehaviorDescriptors.COMMANDS,
                        Map.of("slackProfile", "operations")),
                new GraphNode("send", NodeKind.BEHAVIOR, SlackBehaviorDescriptors.POST_MESSAGE,
                        Map.of("slackProfile", "operations", "channelId", SlackTestSupport.CHANNEL,
                                "requestTimeoutMs", "1500", "maxTextChars", "1000",
                                "maxConcurrency", "1", "retries", "0")), GraphNode.end("end")), List.of());
        byte[] xml;
        try (var graph = GraphManager.from(definition); var output = new ByteArrayOutputStream()) {
            graph.writeGraphMl(output); xml = output.toByteArray();
        }
        String serialized = new String(xml, StandardCharsets.UTF_8);
        assertFalse(serialized.contains("slack-bot-token")); assertFalse(serialized.contains("signing-secret"));
        assertFalse(serialized.contains("https://slack.com"));
        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(xml))) {
            assertEquals(SlackBehaviorDescriptors.EVENTS, reread.definition().node("events").behavior());
            assertEquals(SlackBehaviorDescriptors.COMMANDS, reread.definition().node("commands").behavior());
            assertEquals("operations", reread.definition().node("events").properties().get("slackProfile"));
            assertEquals(SlackTestSupport.CHANNEL, reread.definition().node("send").properties().get("channelId"));
        }
    }
}
