package ai.ravenroot.core.graph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The default failure route: <em>"connecting a node
 * to Error means that, unless I handle it differently, an UNHANDLED error goes directly to that node.
 * A handled error goes where it is routed."</em>
 *
 * <h2>Precedence is the whole feature, so it is pinned in both directions</h2>
 * <p>{@link #resolvesABareEdgeToAnErrorNodeAsAFailureRoute()} and
 * {@link #leavesAnEdgeThatDeclaresAnOutcomeAnOutcomeEdgeEvenWhenItPointsAtError()} are the two halves.
 * The second is the one that fails under the obvious wrong implementation — "target is ERROR, so this
 * is a failure route" — which would silently convert every {@code outcome=failed} edge in a recorded
 * document into a route and take the routing decision away from the author who made it.</p>
 *
 * <h2>Hooked to the target's kind and to nothing else</h2>
 * <p>{@link #leavesABareEdgeToAnOrdinaryNodeAnOutcomeEdge()} is the third direction and the one that
 * says what the rule is <em>about</em>: an edge that declares nothing is an ordinary
 * {@link GraphEdge#DEFAULT_OUTCOME} edge everywhere except into an error terminal. Without it, an
 * implementation that made every bare edge a failure route would pass the first two tests.</p>
 *
 * <h2>And it must not have moved the load-time refusal</h2>
 * <p>{@link #stillAcceptsAnExplicitOutcomeEdgeIntoAnErrorNode()} and
 * {@link #stillRefusesADeclaredFailureRouteThatAlsoCarriesAnOutcome()} are the declaration boundary from both
 * sides: a graph that loaded previously still loads, while the explicitly excluded declaration
 * remains refused.</p>
 */
class DefaultFailureRouteToErrorNodeTest {

    private static final List<GraphNode> NODES = List.of(
            GraphNode.start("start"),
            GraphNode.behavior("work", "work"),
            GraphNode.passthrough("next"),
            GraphNode.error("error"),
            GraphNode.end("end"));

    private static GraphDefinition graphWith(GraphEdge... extra) {
        var edges = new java.util.ArrayList<GraphEdge>(List.of(
                GraphEdge.to("start", "work"),
                GraphEdge.to("next", "end")));
        edges.addAll(List.of(extra));
        return new GraphDefinition(NODES, edges);
    }

    /** Direction 1: nothing declared, target is the error terminal, so the edge carries failure. */
    @Test
    void resolvesABareEdgeToAnErrorNodeAsAFailureRoute() {
        var bare = GraphEdge.to("work", "error");
        var graph = graphWith(bare, GraphEdge.to("work", "next"));

        assertTrue(graph.failureRouted(bare), "a bare edge into ERROR is the unhandled-failure route");
        assertTrue(graph.defaultedFailureRoute(bare),
                "and it is the defaulted kind: the author wrote no property an inspector could offer to clear");
        assertFalse(bare.failureRoute(),
                "the edge itself still declares nothing -- the resolution belongs to the graph, not the record");

        assertEquals(List.of(bare), graph.failureEdges("work"),
                "it must be selected when work fails");
        assertEquals(List.of(GraphEdge.to("work", "next")),
                graph.nextEdges("work", GraphEdge.DEFAULT_OUTCOME),
                "and must NOT be selected when work succeeds: both halves, or the error terminal fires "
                        + "on a clean run");
    }

    /**
     * Direction 2: an author who wrote an outcome made a
     * routing decision, and the default fills a vacuum rather than overruling one. This is the shape
     * the two shipped examples previously carried.
     */
    @Test
    void leavesAnEdgeThatDeclaresAnOutcomeAnOutcomeEdgeEvenWhenItPointsAtError() {
        var declared = new GraphEdge("work", "error", "failed");
        var graph = graphWith(declared, GraphEdge.to("work", "next"));

        assertFalse(graph.failureRouted(declared),
                "an explicit outcome into ERROR stays an outcome edge: the default must not overrule it");
        assertFalse(graph.defaultedFailureRoute(declared));

        assertEquals(List.of(), graph.failureEdges("work"),
                "so work has no failure route at all, and an exception there still stops the traversal");
        assertEquals(List.of(declared), graph.nextEdges("work", "failed"),
                "and it behaves exactly as it did before: selected by the outcome its author named");
    }

    /**
     * Direction 3: the default is a statement about the target's kind. A bare edge anywhere else is an
     * ordinary {@code continue} edge and is completely untouched.
     */
    @Test
    void leavesABareEdgeToAnOrdinaryNodeAnOutcomeEdge() {
        var bare = GraphEdge.to("work", "next");
        var graph = graphWith(bare);

        assertFalse(graph.failureRouted(bare));
        assertFalse(graph.defaultedFailureRoute(bare));
        assertEquals(GraphEdge.DEFAULT_OUTCOME, bare.outcome());

        assertEquals(List.of(), graph.failureEdges("work"));
        assertEquals(List.of(bare), graph.nextEdges("work", GraphEdge.DEFAULT_OUTCOME),
                "a bare edge to a non-ERROR node is selected on success, preserving prior behavior");
    }

    /**
     * A declared route into ERROR keeps working and is reported as declared, not defaulted, so an
     * inspector can still show the author the property they wrote.
     */
    @Test
    void keepsADeclaredFailureRouteDistinguishableFromADefaultedOne() {
        var declaredRoute = new GraphEdge("work", "error", null,
                Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE));
        var graph = graphWith(declaredRoute, GraphEdge.to("work", "next"));

        assertTrue(graph.failureRouted(declaredRoute));
        assertFalse(graph.defaultedFailureRoute(declaredRoute),
                "declared, not defaulted: the two are different things for an author to see");
        assertEquals(List.of(declaredRoute), graph.failureEdges("work"));
    }

    /**
     * The compatibility trap: a document that loaded before default failure routing must still load.
     * An {@code outcome=failed} edge into ERROR is the exact shape recorded documents carry, and it
     * must reach {@link GraphDefinition}'s refusal only if its author declared the property too.
     */
    @Test
    void stillAcceptsAnExplicitOutcomeEdgeIntoAnErrorNode() {
        var graph = graphWith(new GraphEdge("work", "error", "failed"), GraphEdge.to("work", "next"));
        assertEquals(5, graph.nodes().size(), "the graph loaded rather than throwing");
    }

    /** And the refusal itself is untouched: declaring both is still refused at load. */
    @Test
    void stillRefusesADeclaredFailureRouteThatAlsoCarriesAnOutcome() {
        var both = new GraphEdge("work", "error", "failed",
                Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE));
        var violation = org.junit.jupiter.api.Assertions.assertThrows(GraphValidationException.class,
                () -> graphWith(both, GraphEdge.to("work", "next")));
        assertTrue(violation.getMessage().contains("never both"),
                "the declaration-conflict message, unchanged: " + violation.getMessage());
    }
}
