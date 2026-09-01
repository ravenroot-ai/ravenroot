package ai.ravenroot.api.embed;

/**
 * Explicit browser-projection budgets, deliberately separate from GraphML ingest limits.
 * @param maxNodes positive maximum number of projected nodes
 * @param maxEdges positive maximum number of projected edges
 * @param maxJsonBytes positive encoded-JSON byte ceiling
 * @param maxIdentifierChars positive character ceiling for graph identifiers
 * @param maxCoordinateMagnitude finite absolute coordinate ceiling
 * @param maxDimension finite positive width and height ceiling
 */
public record EmbedProjectionBudget(int maxNodes, int maxEdges, int maxJsonBytes, int maxIdentifierChars,
                                    double maxCoordinateMagnitude, double maxDimension) {
    public static final EmbedProjectionBudget DEFAULTS =
            new EmbedProjectionBudget(2_000, 5_000, 2 * 1024 * 1024, 256, 1_000_000d, 10_000d);

/**
 * Rejects non-positive and non-finite ceilings before they reach a projection reader.
 */
    public EmbedProjectionBudget {
        if (maxNodes < 1 || maxEdges < 1 || maxJsonBytes < 1 || maxIdentifierChars < 1
                || !Double.isFinite(maxCoordinateMagnitude) || maxCoordinateMagnitude <= 0
                || !Double.isFinite(maxDimension) || maxDimension <= 0) {
            throw new IllegalArgumentException("embed projection budgets must be finite and positive");
        }
    }

/**
 * Re-enforces every projection budget at the application boundary, independent of the adapter.
 * @param projection captured render-only graph projection to check
 * @return whether every count, identifier, coordinate, dimension and JSON budget is satisfied
 */
    public boolean allows(EmbedGraphProjection projection) {
        if (projection == null || projection.nodes().size() > maxNodes || projection.edges().size() > maxEdges
                || tooLong(projection.graphId()) || tooLong(projection.graphVersionId())
                || tooLong(projection.canonicalDigest())) {
            return false;
        }
        for (var node : projection.nodes()) {
            if (tooLong(node.id())) return false;
            if (node.layout() != null && (Math.abs(node.layout().x()) > maxCoordinateMagnitude
                    || Math.abs(node.layout().y()) > maxCoordinateMagnitude
                    || node.layout().width() > maxDimension || node.layout().height() > maxDimension)) {
                return false;
            }
        }
        for (var edge : projection.edges()) {
            if (tooLong(edge.source()) || tooLong(edge.target())) return false;
        }
        return projection.jsonBytes() <= maxJsonBytes;
    }

    private boolean tooLong(String value) {
        return value.length() > maxIdentifierChars;
    }
}
