package ai.ravenroot.core.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Validated mapping used to move execution state between immutable snapshots. */
public final class GraphMigrationPlan {
    private final GraphVersionSnapshot source;
    private final GraphVersionSnapshot target;
    private final Map<String, String> nodeMapping;
    private final List<GraphMigrationDiagnostic> diagnostics;

    private GraphMigrationPlan(GraphVersionSnapshot source, GraphVersionSnapshot target,
                               Map<String, String> nodeMapping,
                               List<GraphMigrationDiagnostic> diagnostics) {
        this.source = source;
        this.target = target;
        this.nodeMapping = Collections.unmodifiableMap(new LinkedHashMap<>(nodeMapping));
        this.diagnostics = List.copyOf(diagnostics);
    }

    /**
     * Builds a conservative plan. Unspecified nodes map by stable id; renamed
     * nodes require an explicit mapping.
     */
    public static GraphMigrationPlan analyze(GraphVersionSnapshot source, GraphVersionSnapshot target,
                                             Map<String, String> explicitMapping) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        explicitMapping = explicitMapping == null ? Map.of() : Map.copyOf(explicitMapping);
        var diagnostics = new ArrayList<GraphMigrationDiagnostic>();
        if (!source.key().graphId().equals(target.key().graphId())) {
            diagnostics.add(error("GRAPH_ID_MISMATCH", "Cannot migrate between logical graphs '"
                    + source.key().graphId() + "' and '" + target.key().graphId() + "'"));
        }
        if (source.metadata().schemaVersion() != target.metadata().schemaVersion()) {
            diagnostics.add(error("SCHEMA_VERSION_MISMATCH", "No schema migration exists from "
                    + source.metadata().schemaVersion() + " to " + target.metadata().schemaVersion()));
        }

        Map<String, GraphNode> sourceNodes = index(source.definition());
        Map<String, GraphNode> targetNodes = index(target.definition());
        var mapping = new TreeMap<String, String>();
        explicitMapping.forEach((from, to) -> {
            if (!sourceNodes.containsKey(from)) {
                diagnostics.add(error("UNKNOWN_SOURCE_NODE", "Migration mapping references unknown source node '"
                        + from + "'"));
            } else if (!targetNodes.containsKey(to)) {
                diagnostics.add(error("UNKNOWN_TARGET_NODE", "Migration mapping references unknown target node '"
                        + to + "'"));
            } else {
                mapping.put(from, to);
            }
        });
        sourceNodes.keySet().stream().sorted().forEach(id -> {
            if (!mapping.containsKey(id)) {
                if (targetNodes.containsKey(id)) {
                    mapping.put(id, id);
                } else {
                    diagnostics.add(error("UNMAPPED_SOURCE_NODE",
                            "Source node '" + id + "' has no target mapping"));
                }
            }
        });

        var targetOwners = new HashMap<String, String>();
        mapping.forEach((from, to) -> {
            String previous = targetOwners.putIfAbsent(to, from);
            if (previous != null) {
                diagnostics.add(error("NON_INJECTIVE_NODE_MAPPING", "Source nodes '" + previous + "' and '"
                        + from + "' both map to target node '" + to + "'"));
            }
            GraphNode left = sourceNodes.get(from);
            GraphNode right = targetNodes.get(to);
            if (left != null && right != null && !sameExecutableNode(left, right)) {
                diagnostics.add(error("NODE_SEMANTICS_CHANGED", "Node '" + from + "' cannot migrate to '"
                        + to + "' because kind, behavior or properties changed"));
            }
        });

        var availableRoutes = routeCounts(target.definition().edges());
        for (var edge : source.definition().edges()) {
            String mappedSource = mapping.get(edge.source());
            String mappedTarget = mapping.get(edge.target());
            if (mappedSource == null || mappedTarget == null) {
                continue;
            }
            String route = routeIdentity(mappedSource, mappedTarget, edge);
            int remaining = availableRoutes.getOrDefault(route, 0);
            if (remaining == 0) {
                diagnostics.add(error("ROUTE_NOT_MIGRATABLE", "Route " + edge.source() + " --"
                        + edge.outcome() + "--> " + edge.target() + " has no semantic target"));
            } else {
                availableRoutes.put(route, remaining - 1);
            }
        }
        return new GraphMigrationPlan(source, target, mapping, diagnostics);
    }

    public GraphVersionSnapshot source() {
        return source;
    }

    public GraphVersionSnapshot target() {
        return target;
    }

    public Map<String, String> nodeMapping() {
        return nodeMapping;
    }

    public List<GraphMigrationDiagnostic> diagnostics() {
        return diagnostics;
    }

    public boolean migratable() {
        return diagnostics.stream().noneMatch(item ->
                item.severity() == GraphMigrationDiagnostic.Severity.ERROR);
    }

    public void requireMigratable() {
        if (!migratable()) {
            throw new IllegalStateException("Graph execution cannot migrate: " + diagnostics);
        }
    }

    private static Map<String, GraphNode> index(GraphDefinition definition) {
        var result = new LinkedHashMap<String, GraphNode>();
        definition.nodes().forEach(node -> result.put(node.id(), node));
        return result;
    }

    private static boolean sameExecutableNode(GraphNode source, GraphNode target) {
        return source.kind() == target.kind()
                && Objects.equals(source.behavior(), target.behavior())
                && source.properties().equals(target.properties());
    }

    private static Map<String, Integer> routeCounts(List<GraphEdge> edges) {
        var result = new HashMap<String, Integer>();
        edges.forEach(edge -> result.merge(routeIdentity(edge.source(), edge.target(), edge), 1, Integer::sum));
        return result;
    }

    private static String routeIdentity(String source, String target, GraphEdge edge) {
        var mapped = new GraphEdge(source, target, edge.outcome(), edge.properties(), edge.id());
        return GraphSemanticDiff.edgeIdentity(mapped);
    }

    private static GraphMigrationDiagnostic error(String code, String message) {
        return new GraphMigrationDiagnostic(GraphMigrationDiagnostic.Severity.ERROR, code, message);
    }
}
