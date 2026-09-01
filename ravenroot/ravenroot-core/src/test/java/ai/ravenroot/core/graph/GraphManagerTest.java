package ai.ravenroot.core.graph;

import ai.ravenroot.api.execution.NodeCommand;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphManagerTest {
    @Test
    void queriesTopologyWithGremlinAndPreservesArbitraryProperties() {
        var graph = new GraphDefinition(List.of(
                new GraphNode("start", NodeKind.START, null, Map.of("ui.color", "green")),
                new GraphNode("worker", NodeKind.BEHAVIOR, "future-worker", Map.of("customNumber", 42)),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                new GraphEdge("start", "worker", "continue", Map.of("routeLabel", "primary")),
                GraphEdge.to("worker", "end")));

        try (var manager = GraphManager.from(graph)) {
            assertEquals(List.of("worker"), manager.query(g -> g.V("start").out().id().toList()));
            assertEquals(42, manager.definition().node("worker").properties().get("customNumber"));
            assertEquals("primary", manager.definition().edges().getFirst().properties().get("routeLabel"));
        }
    }

    @Test
    void roundTripsGraphMlWithoutDiscardingUnknownAttributes() {
        var graph = new GraphDefinition(List.of(
                new GraphNode("start", NodeKind.START, null, Map.of("externalToolTag", "kept")),
                GraphNode.error("error"), GraphNode.end("end")), List.of(GraphEdge.to("start", "end")));
        var output = new ByteArrayOutputStream();

        try (var manager = GraphManager.from(graph)) {
            manager.writeGraphMl(output);
        }
        String serialized = output.toString(StandardCharsets.UTF_8);
        assertTrue(serialized.contains("externalToolTag"));

        try (var reloaded = GraphManager.readGraphMl(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals("kept", reloaded.definition().node("start").properties().get("externalToolTag"));
        }
    }

    @Test
    void roundTripsEdgeCommandsWhileLegacyEdgesPreserveTheIncomingCommand() {
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), GraphNode.passthrough("middle"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                new GraphEdge("start", "middle", "continue", Map.of(GraphManager.COMMAND, "correggi")),
                GraphEdge.to("middle", "end")));
        var output = new ByteArrayOutputStream();

        try (var manager = GraphManager.from(graph)) {
            manager.writeGraphMl(output);
        }
        try (var reloaded = GraphManager.readGraphMl(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(NodeCommand.application("correggi"),
                    reloaded.definition().edges().getFirst().command().orElseThrow());
            assertTrue(reloaded.definition().edges().get(1).command().isEmpty(),
                    "an absent legacy command must preserve the incoming command");
        }
    }

    @Test
    void preservesWhitespaceSignificantProgrammaticBehaviorAndOutcome() {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("worker", NodeKind.BEHAVIOR, "  custom behavior  "),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                new GraphEdge("start", "worker", "  selected  "),
                GraphEdge.to("worker", "end")));

        try (var manager = GraphManager.from(graph)) {
            assertEquals("  custom behavior  ", manager.definition().node("worker").behavior());
            assertEquals("  selected  ", manager.definition().edges().getFirst().outcome());
            assertEquals(List.of("worker"), manager.next("start", "  selected  ").stream()
                    .map(GraphNode::id).toList());
            assertTrue(manager.next("start", "selected").isEmpty());
        }
    }
}
