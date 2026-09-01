package ai.ravenroot.core.graph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The declaration and fail-closed validation of {@code failure.route}.
 *
 * <p>{@link GraphRunnerFailureRouteTest} covers the runtime mechanism these edges drive; this class
 * covers the two things that must hold before a single node ever runs: the property reads exactly as
 * declared, and an edge that tries to be both a failure route and an outcome edge is refused at load
 * rather than silently resolved one way or the other.</p>
 */
class FailureRouteEdgePropertyTest {

    @Test
    void isNotDeclaredByAnOrdinaryEdge() {
        assertFalse(GraphEdge.to("a", "b").failureRoute());
    }

    @Test
    void isDeclaredByTheExactCanonicalToken() {
        var edge = new GraphEdge("a", "b", GraphEdge.DEFAULT_OUTCOME,
                Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE));

        assertTrue(edge.failureRoute());
    }

    /**
     * Case-sensitive and exact, like {@code RecoveryRepeatabilityProperty}'s own token match (the
     * javadoc cites the precedent directly): an approximate match here would route a failure down an
     * edge nobody declared for it.
     */
    @Test
    void doesNotMatchAnApproximateToken() {
        var edge = new GraphEdge("a", "b", GraphEdge.DEFAULT_OUTCOME,
                Map.of(FailureRouteEdgeProperty.NAME, "True"));

        assertFalse(edge.failureRoute(), "the token is case-sensitive; 'True' is not 'true'");
    }

    @Test
    void anUnrelatedPropertyIsNotMistakenForIt() {
        var edge = new GraphEdge("a", "b", GraphEdge.DEFAULT_OUTCOME, Map.of("owner", "team-a"));

        assertFalse(edge.failureRoute());
    }

    /**
     * The rule the architect's ruling states directly: an edge is a failure route or an outcome
     * edge, never both. {@code GraphEdge}'s canonical constructor collapses "no outcome authored"
     * into {@link GraphEdge#DEFAULT_OUTCOME}, which is the only signal the record shape can carry, so
     * the refusal fires on any outcome other than that default.
     */
    @Test
    void refusesAFailureRouteEdgeThatAlsoDeclaresAnExplicitOutcome() {
        var edge = new GraphEdge("boom", "handler", "error",
                Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE));

        var thrown = assertThrows(GraphValidationException.class, () -> graphWith(edge));

        assertTrue(thrown.violations().stream().anyMatch(v -> v.contains(FailureRouteEdgeProperty.NAME)
                        && v.contains("boom") && v.contains("handler")),
                "the violation must name the offending edge: " + thrown.violations());
    }

    /**
     * The converse of the refusal above: a failure-route edge that carries no outcome at all -- the
     * ordinary way an author would write one -- is accepted. If this regresses alongside the refusal
     * test, the two together prove the boundary was drawn correctly rather than by accident.
     */
    @Test
    void acceptsAFailureRouteEdgeWithNoExplicitOutcome() {
        var edge = new GraphEdge("boom", "handler", null,
                Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE));

        var graph = graphWith(edge);

        assertEquals(List.of(edge), graph.failureEdges("boom"));
        assertEquals(GraphEdge.DEFAULT_OUTCOME, edge.outcome());
    }

    /**
     * An edge that explicitly authors {@code outcome=continue} together with {@code failure.route}
     * is indistinguishable, by the record's own shape, from one that authored no outcome at all --
     * and the ruling is explicit that the record gains no new component to make them distinguishable.
     * So this is accepted too, deliberately: refusing it would refuse the one value the mechanism
     * itself requires a failure-route edge to carry.
     */
    @Test
    void acceptsAFailureRouteEdgeThatExplicitlyAuthorsTheDefaultOutcome() {
        var edge = new GraphEdge("boom", "handler", "continue",
                Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE));

        assertEquals(List.of(edge), graphWith(edge).failureEdges("boom"));
    }

    /** A failure-route edge is excluded from ordinary outcome-based selection (see {@code nextEdges}). */
    @Test
    void isExcludedFromOrdinaryOutcomeSelectionEvenThoughItCarriesTheDefaultOutcome() {
        var failureEdge = new GraphEdge("boom", "handler", null,
                Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE));
        var ordinaryEdge = GraphEdge.to("boom", "next");

        var graph = graphWith(failureEdge, ordinaryEdge);

        assertEquals(List.of(ordinaryEdge), graph.nextEdges("boom", GraphEdge.DEFAULT_OUTCOME),
                "the failure edge carries outcome=continue by construction; nextEdges must not let it "
                        + "ride along with an ordinary continue selection");
        assertEquals(List.of(failureEdge), graph.failureEdges("boom"));
    }

    private static GraphDefinition graphWith(GraphEdge... extra) {
        var nodes = List.of(GraphNode.start("start"), GraphNode.behavior("boom", "boom"),
                GraphNode.behavior("handler", "handler"), GraphNode.behavior("next", "next"),
                GraphNode.error("error"), GraphNode.end("end"));
        var edges = new java.util.ArrayList<GraphEdge>();
        edges.add(GraphEdge.to("start", "boom"));
        edges.add(GraphEdge.to("handler", "end"));
        edges.add(GraphEdge.to("next", "end"));
        edges.addAll(List.of(extra));
        return new GraphDefinition(nodes, edges);
    }
}
