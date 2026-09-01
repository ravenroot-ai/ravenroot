package ai.ravenroot.core.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpecializedMiniGraphsTest {

    @Test
    void linearPassthroughGraph() {
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.passthrough("pass"), GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "pass"), GraphEdge.to("pass", "end")));

        assertEquals(List.of(GraphNode.passthrough("pass")), graph.next("start", "continue"));
        assertEquals(List.of(GraphNode.end("end")), graph.next("pass", "continue"));
    }

    @Test
    void behaviorGraph() {
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.behavior("normalize", "normalize-text"),
                        GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "normalize"), GraphEdge.to("normalize", "end")));

        assertEquals("normalize-text", graph.next("start", "continue").getFirst().behavior());
    }

    @Test
    void branchGraphSelectsEdgesByOutcome() {
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.behavior("choice", "choose"),
                        GraphNode.passthrough("accepted"), GraphNode.passthrough("rejected"), GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "choice"), new GraphEdge("choice", "accepted", "accepted"),
                        new GraphEdge("choice", "rejected", "rejected"), GraphEdge.to("accepted", "end"),
                        GraphEdge.to("rejected", "end")));

        assertEquals(List.of(GraphNode.passthrough("accepted")), graph.next("choice", "accepted"));
        assertEquals(List.of(GraphNode.passthrough("rejected")), graph.next("choice", "rejected"));
    }

    @Test
    void fanOutAndFanInGraph() {
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.passthrough("left"), GraphNode.passthrough("right"),
                        GraphNode.behavior("join", "join"), GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "left"), GraphEdge.to("start", "right"),
                        GraphEdge.to("left", "join"), GraphEdge.to("right", "join"), GraphEdge.to("join", "end")));

        assertEquals(List.of(GraphNode.passthrough("left"), GraphNode.passthrough("right")),
                graph.next("start", "continue"));
        assertEquals(List.of(GraphNode.behavior("join", "join")), graph.next("left", "continue"));
        assertEquals(List.of(GraphNode.behavior("join", "join")), graph.next("right", "continue"));
    }

    @Test
    void invalidGraphReportsAllStructuralViolations() {
        var error = assertThrows(GraphValidationException.class,
                () -> new GraphDefinition(List.of(GraphNode.start("one"), GraphNode.start("two")),
                        List.of(GraphEdge.to("missing", "also-missing"))));

        // Listed rather than counted; the list makes the zero-or-one rule legible here: a count
        // would have gone 4 -> 5 -> 4 and said nothing, while the list shows the error rule arriving
        // beside the start and end rules and then losing its floor -- a graph with no error terminal
        // is a supported design choice, so it contributes no violation.
        // A graph with two still does; that half of the rule is asserted in ErrorTerminalStructureTest.
        assertEquals(List.of(
                        "A graph must contain exactly one start node",
                        "A graph must contain exactly one end node",
                        "Edge source does not exist: missing",
                        "Edge target does not exist: also-missing"),
                error.violations());
    }
}
