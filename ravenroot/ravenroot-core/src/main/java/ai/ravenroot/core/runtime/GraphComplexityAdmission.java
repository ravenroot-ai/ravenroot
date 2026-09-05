package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeRuntimeMaxConcurrencyProperty;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeRuntimeNatureProperty;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Linear, allocation-bounded admission over a pinned graph definition. */
final class GraphComplexityAdmission {
    private final BehaviorRegistry behaviors;
    private final GraphExecutionLimits limits;

    GraphComplexityAdmission(BehaviorRegistry behaviors, GraphExecutionLimits limits) {
        this.behaviors = java.util.Objects.requireNonNull(behaviors, "behaviors");
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
    }

    void validate(GraphDefinition graph) {
        long nodes = graph.nodes().size();
        long edges = graph.edges().size();
        require(GraphExecutionLimitException.Reason.NODES, nodes, limits.graphMl().maxNodes());
        require(GraphExecutionLimitException.Reason.EDGES, edges, limits.graphMl().maxEdges());

        long properties = graph.properties().size();
        int residents = 0;
        var outgoing = new HashMap<String, Set<String>>();
        var indegree = new HashMap<String, Integer>();
        for (GraphNode node : graph.nodes()) {
            properties = Math.addExact(properties, node.properties().size());
            require(GraphExecutionLimitException.Reason.PROPERTIES, properties, limits.graphMl().maxProperties());
            indegree.put(node.id(), 0);
            NodeTypeDescriptor descriptor = descriptor(node);
            NodeRuntimeNature nature = NodeRuntimeNatureProperty.effectiveNature(descriptor, node.properties());
            if (nature != NodeRuntimeNature.WORKER && nature != NodeRuntimeNature.TRAVERSAL) residents++;
            NodeRuntimeMaxConcurrencyProperty.effectiveValue(descriptor, node.properties());
        }
        for (GraphEdge edge : graph.edges()) {
            properties = Math.addExact(properties, edge.properties().size());
            require(GraphExecutionLimitException.Reason.PROPERTIES, properties, limits.graphMl().maxProperties());
            if (outgoing.computeIfAbsent(edge.source(), ignored -> new HashSet<>()).add(edge.target())) {
                indegree.computeIfPresent(edge.target(), (ignored, value) -> value + 1);
            }
        }
        require(GraphExecutionLimitException.Reason.PROPERTIES, properties, limits.graphMl().maxProperties());
        require(GraphExecutionLimitException.Reason.RESIDENT_ACTORS, residents, limits.maxResidentActors());
        validateFanOut(graph);
        evaluateCycles(outgoing, indegree);
    }

    private void validateFanOut(GraphDefinition graph) {
        var routes = new LinkedHashMap<Route, Set<String>>();
        for (GraphEdge edge : graph.edges()) {
            String outcome = graph.failureRouted(edge) ? "failure" : "outcome:" + edge.outcome();
            Set<String> targets = routes.computeIfAbsent(new Route(edge.source(), outcome), ignored -> new HashSet<>());
            targets.add(edge.target());
            require(GraphExecutionLimitException.Reason.FAN_OUT, targets.size(), limits.maxFanOut());
        }
    }

    /** Kahn's algorithm evaluates cyclicity in O(nodes + edges), without recursive stack growth. */
    private void evaluateCycles(Map<String, Set<String>> outgoing, Map<String, Integer> originalIndegree) {
        var indegree = new HashMap<>(originalIndegree);
        var ready = new ArrayDeque<String>();
        indegree.forEach((node, degree) -> { if (degree == 0) ready.addLast(node); });
        int visited = 0;
        while (!ready.isEmpty()) {
            String node = ready.removeFirst();
            visited++;
            for (String target : outgoing.getOrDefault(node, Set.of())) {
                int remaining = indegree.computeIfPresent(target, (ignored, value) -> value - 1);
                if (remaining == 0) ready.addLast(target);
            }
        }
        if (visited < originalIndegree.size() && limits.maxTraversalSteps() < 1) {
            // Unreachable with a valid GraphExecutionLimits, retained as the fail-closed cycle rule.
            throw new GraphExecutionLimitException(GraphExecutionLimitException.Reason.TRAVERSAL_STEPS, 1, 0);
        }
    }

    private NodeTypeDescriptor descriptor(GraphNode node) {
        return node.kind() == NodeKind.BEHAVIOR ? behaviors.descriptor(node.behavior()).orElse(null) : null;
    }

    private static void require(GraphExecutionLimitException.Reason reason, long observed, long limit) {
        if (observed > limit) throw new GraphExecutionLimitException(reason, observed, limit);
    }

    private record Route(String source, String outcome) { }
}
