package ai.ravenroot.core.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * Conservative structural compatibility result.
 *
 * <p>Additions are backward-compatible; removals are forward-compatible.
 * Changed existing nodes or routes satisfy neither direction.</p>
 */
public record GraphCompatibilityReport(
        GraphSemanticDiff diff,
        boolean backwardCompatible,
        boolean forwardCompatible,
        List<GraphMigrationDiagnostic> diagnostics) {

    public GraphCompatibilityReport {
        diagnostics = List.copyOf(diagnostics);
    }

    public static GraphCompatibilityReport analyze(GraphDefinition before, GraphDefinition after) {
        GraphSemanticDiff diff = GraphSemanticDiff.between(before, after);
        boolean backward = diff.removedNodeIds().isEmpty()
                && diff.changedNodeIds().isEmpty()
                && diff.removedEdges().isEmpty();
        boolean forward = diff.addedNodeIds().isEmpty()
                && diff.changedNodeIds().isEmpty()
                && diff.addedEdges().isEmpty();
        var diagnostics = new ArrayList<GraphMigrationDiagnostic>();
        diff.removedNodeIds().forEach(id -> diagnostics.add(error(
                "BACKWARD_NODE_REMOVED", "Backward compatibility removes node '" + id + "'")));
        diff.addedNodeIds().forEach(id -> diagnostics.add(error(
                "FORWARD_NODE_ADDED", "Forward compatibility adds node '" + id + "'")));
        diff.changedNodeIds().forEach(id -> diagnostics.add(error(
                "NODE_CHANGED", "Node '" + id + "' changed kind, behavior or properties")));
        if (!diff.removedEdges().isEmpty()) {
            diagnostics.add(error("BACKWARD_ROUTE_REMOVED",
                    "Backward compatibility removes " + diff.removedEdges().size() + " route(s)"));
        }
        if (!diff.addedEdges().isEmpty()) {
            diagnostics.add(error("FORWARD_ROUTE_ADDED",
                    "Forward compatibility adds " + diff.addedEdges().size() + " route(s)"));
        }
        return new GraphCompatibilityReport(diff, backward, forward, diagnostics);
    }

    public void require(GraphCompatibilityPolicy policy) {
        if (!policy.accepts(this)) {
            throw new IllegalStateException("Graph definition does not satisfy " + policy
                    + " compatibility: " + diagnostics);
        }
    }

    private static GraphMigrationDiagnostic error(String code, String message) {
        return new GraphMigrationDiagnostic(GraphMigrationDiagnostic.Severity.ERROR, code, message);
    }
}
