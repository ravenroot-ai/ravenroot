package ai.ravenroot.extensions.teams;

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

class TeamsGraphMlRoundTripTest {
    @Test void roundTripsOnlyOpaqueProfileAndTighteningProperties() throws Exception {
        var definition = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("receive", NodeKind.BEHAVIOR, TeamsBehaviorDescriptors.OUTGOING_WEBHOOK,
                        Map.of("teamsProfile", TeamsTestSupport.PROFILE)),
                new GraphNode("send", NodeKind.BEHAVIOR, TeamsBehaviorDescriptors.SEND,
                        Map.of("teamsProfile", TeamsTestSupport.PROFILE, "channelId", TeamsTestSupport.CHANNEL,
                                "requestTimeoutMs", "1500", "maxTextChars", "1000", "maxConcurrency", "1")),
                GraphNode.end("end")), List.of());
        byte[] xml;
        try (var graph = GraphManager.from(definition); var output = new ByteArrayOutputStream()) {
            graph.writeGraphMl(output); xml = output.toByteArray();
        }
        String serialized = new String(xml, StandardCharsets.UTF_8);
        assertFalse(serialized.contains("teams-workflow-token"));
        assertFalse(serialized.contains("teams-signing-secret"));
        assertFalse(serialized.contains("logic.azure.com"));
        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(xml))) {
            assertEquals(TeamsBehaviorDescriptors.OUTGOING_WEBHOOK, reread.definition().node("receive").behavior());
            assertEquals(TeamsBehaviorDescriptors.SEND, reread.definition().node("send").behavior());
            assertEquals(TeamsTestSupport.PROFILE,
                    reread.definition().node("receive").properties().get("teamsProfile"));
            assertEquals(TeamsTestSupport.CHANNEL, reread.definition().node("send").properties().get("channelId"));
        }
    }
}
