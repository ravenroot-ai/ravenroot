package ai.ravenroot.core.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic topology/property difference between two canonical definitions. */
public record GraphSemanticDiff(
        Set<String> addedNodeIds,
        Set<String> removedNodeIds,
        Set<String> changedNodeIds,
        List<GraphEdge> addedEdges,
        List<GraphEdge> removedEdges) {

    public GraphSemanticDiff {
        addedNodeIds = immutableSortedSet(addedNodeIds);
        removedNodeIds = immutableSortedSet(removedNodeIds);
        changedNodeIds = immutableSortedSet(changedNodeIds);
        addedEdges = List.copyOf(addedEdges);
        removedEdges = List.copyOf(removedEdges);
    }

    public static GraphSemanticDiff between(GraphDefinition before, GraphDefinition after) {
        GraphDefinition left = GraphCanonicalForm.immutableCopy(before);
        GraphDefinition right = GraphCanonicalForm.immutableCopy(after);
        Map<String, GraphNode> leftNodes = indexNodes(left);
        Map<String, GraphNode> rightNodes = indexNodes(right);

        var addedNodes = new TreeSet<>(rightNodes.keySet());
        addedNodes.removeAll(leftNodes.keySet());
        var removedNodes = new TreeSet<>(leftNodes.keySet());
        removedNodes.removeAll(rightNodes.keySet());
        var changedNodes = new TreeSet<String>();
        leftNodes.forEach((id, node) -> {
            if (rightNodes.containsKey(id) && !node.equals(rightNodes.get(id))) {
                changedNodes.add(id);
            }
        });

        var unmatchedBefore = edgeBuckets(left.edges());
        var addedEdges = new ArrayList<GraphEdge>();
        for (var edge : sortedEdges(right.edges())) {
            var matches = unmatchedBefore.get(edgeIdentity(edge));
            if (matches == null || matches.isEmpty()) {
                addedEdges.add(edge);
            } else {
                matches.removeFirst();
            }
        }
        var removedEdges = unmatchedBefore.values().stream()
                .flatMap(ArrayDeque::stream)
                .sorted((first, second) -> edgeIdentity(first).compareTo(edgeIdentity(second)))
                .toList();
        return new GraphSemanticDiff(addedNodes, removedNodes, changedNodes, addedEdges, removedEdges);
    }

    public boolean isEmpty() {
        return addedNodeIds.isEmpty() && removedNodeIds.isEmpty() && changedNodeIds.isEmpty()
                && addedEdges.isEmpty() && removedEdges.isEmpty();
    }

    static String edgeIdentity(GraphEdge edge) {
        return HexFormat.of().formatHex(GraphCanonicalForm.edgeBytes(edge));
    }

    private static Map<String, GraphNode> indexNodes(GraphDefinition definition) {
        var result = new LinkedHashMap<String, GraphNode>();
        definition.nodes().forEach(node -> result.put(node.id(), node));
        return result;
    }

    private static Map<String, ArrayDeque<GraphEdge>> edgeBuckets(List<GraphEdge> edges) {
        var result = new LinkedHashMap<String, ArrayDeque<GraphEdge>>();
        for (var edge : sortedEdges(edges)) {
            result.computeIfAbsent(edgeIdentity(edge), ignored -> new ArrayDeque<>()).addLast(edge);
        }
        return result;
    }

    private static List<GraphEdge> sortedEdges(List<GraphEdge> edges) {
        return edges.stream().sorted((first, second) ->
                edgeIdentity(first).compareTo(edgeIdentity(second))).toList();
    }

    private static Set<String> immutableSortedSet(Set<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(values)));
    }
}
