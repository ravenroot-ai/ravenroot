package ai.ravenroot.core.graph;

import ai.ravenroot.api.application.StableEdgeId;

import java.util.Map;
import java.util.Objects;

public record GraphEdge(String source, String target, String outcome, Map<String, Object> properties, String id) {

    /**
     * The outcome an edge carries when its author selected none. Also the only {@code outcome} a
     * {@link FailureRouteEdgeProperty#NAME} edge may carry: the canonical constructor below
     * collapses "nothing was authored" and "the author wrote {@code continue}" into this same value,
     * so it is the one signal available to tell a failure route from an edge that also claims an
     * explicit outcome.
     */
    public static final String DEFAULT_OUTCOME = "continue";

    public GraphEdge {
        if (source == null || source.isBlank() || target == null || target.isBlank()) {
            throw new IllegalArgumentException("A graph edge must have source and target ids");
        }
        if (outcome == null || outcome.isBlank()) {
            outcome = DEFAULT_OUTCOME;
        }
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        id = id == null ? null : StableEdgeId.requireValid(id);
    }

    /** Compatibility constructor for the graph edge shape published before runtime edge identity. */
    public GraphEdge(String source, String target, String outcome, Map<String, Object> properties) {
        this(source, target, outcome, properties, null);
    }

    public GraphEdge(String source, String target, String outcome) {
        this(source, target, outcome, Map.of(), null);
    }

    public static GraphEdge to(String source, String target) {
        return new GraphEdge(source, target, DEFAULT_OUTCOME);
    }

    /** Returns this edge with the stable identity assigned by its graph definition. */
    GraphEdge identifiedAs(String stableId) {
        return new GraphEdge(source, target, outcome, properties, stableId);
    }

    /**
     * Edge identity is an address, not routing semantics. Preserve the equality contract of the
     * pre-identity four-component record so adding a handle cannot change canonical graph behavior,
     * duplicate-route collapsing or callers comparing an authored edge with its definition copy.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof GraphEdge edge
                && source.equals(edge.source)
                && target.equals(edge.target)
                && outcome.equals(edge.outcome)
                && properties.equals(edge.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, target, outcome, properties);
    }

    /** Whether this edge declares itself a failure route. See {@link FailureRouteEdgeProperty}. */
    public boolean failureRoute() {
        return FailureRouteEdgeProperty.declared(this);
    }

    /** Explicit target command, or empty when this edge preserves the incoming command. */
    public java.util.Optional<ai.ravenroot.api.execution.NodeCommand> command() {
        Object value = properties.get(GraphManager.COMMAND);
        return value == null || value.toString().isBlank() ? java.util.Optional.empty()
                : java.util.Optional.of(ai.ravenroot.api.execution.NodeCommand.parse(value.toString()));
    }
}
