package ai.ravenroot.core.graph;

/**
 * Resource budgets applied to every GraphML import, including embedded use.
 *
 * <p>The defaults deliberately bound both the encoded document and the object
 * graph created from it. Applications needing smaller budgets can pass an
 * explicit instance to {@link GraphManager#readGraphMl(java.io.InputStream, GraphMlLimits)}.</p>
 */
public record GraphMlLimits(
        int maxBytes,
        int maxNodes,
        int maxEdges,
        int maxProperties,
        int maxDepth,
        int maxStringLength,
        int maxKeys,
        int maxElements,
        int maxAttributes,
        int maxNamespaceDeclarations) {

    public static final GraphMlLimits DEFAULTS = new GraphMlLimits(
            10 * 1024 * 1024,
            10_000,
            25_000,
            100_000,
            64,
            1024 * 1024,
            4_096,
            250_000,
            500_000,
            10_000);

    public GraphMlLimits(int maxBytes, int maxNodes, int maxEdges, int maxProperties,
                         int maxDepth, int maxStringLength, int maxKeys) {
        this(maxBytes, maxNodes, maxEdges, maxProperties, maxDepth, maxStringLength, maxKeys,
                DEFAULTS.maxElements, DEFAULTS.maxAttributes, DEFAULTS.maxNamespaceDeclarations);
    }

    public GraphMlLimits {
        if (maxBytes < 1 || maxNodes < 1 || maxEdges < 1 || maxProperties < 1
                || maxDepth < 1 || maxStringLength < 1 || maxKeys < 1
                || maxElements < 1 || maxAttributes < 1 || maxNamespaceDeclarations < 1) {
            throw new IllegalArgumentException("GraphML limits must all be positive");
        }
        if (maxBytes > 256 * 1024 * 1024 || maxNodes > 1_000_000 || maxEdges > 5_000_000
                || maxProperties > 10_000_000 || maxDepth > 1_024
                || maxStringLength > 64 * 1024 * 1024 || maxKeys > 100_000
                || maxElements > 10_000_000 || maxAttributes > 20_000_000
                || maxNamespaceDeclarations > 1_000_000) {
            throw new IllegalArgumentException("GraphML limits exceed the supported safety ceiling");
        }
    }
}
