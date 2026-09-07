package ai.ravenroot.core.graph;

import ai.ravenroot.api.persistence.GraphDefinitionStore;

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

    /** Safety ceilings applied to every supported GraphML admission configuration. */
    public static final int HARD_MAX_NODES = 1_000_000;
    public static final int HARD_MAX_EDGES = 5_000_000;
    public static final int HARD_MAX_PROPERTIES = 10_000_000;
    public static final int HARD_MAX_DEPTH = 1_024;
    public static final int HARD_MAX_STRING_LENGTH = 64 * 1024 * 1024;
    public static final int HARD_MAX_KEYS = 100_000;
    public static final int HARD_MAX_ELEMENTS = 10_000_000;
    public static final int HARD_MAX_ATTRIBUTES = 20_000_000;
    public static final int HARD_MAX_NAMESPACE_DECLARATIONS = 1_000_000;

    public static final GraphMlLimits DEFAULTS = new GraphMlLimits(
            GraphDefinitionStore.DEFAULT_MAX_DEFINITION_BYTES,
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
        if (maxBytes > GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES
                || maxNodes > HARD_MAX_NODES || maxEdges > HARD_MAX_EDGES
                || maxProperties > HARD_MAX_PROPERTIES || maxDepth > HARD_MAX_DEPTH
                || maxStringLength > HARD_MAX_STRING_LENGTH || maxKeys > HARD_MAX_KEYS
                || maxElements > HARD_MAX_ELEMENTS || maxAttributes > HARD_MAX_ATTRIBUTES
                || maxNamespaceDeclarations > HARD_MAX_NAMESPACE_DECLARATIONS) {
            throw new IllegalArgumentException("GraphML limits exceed the supported safety ceiling");
        }
    }
}
