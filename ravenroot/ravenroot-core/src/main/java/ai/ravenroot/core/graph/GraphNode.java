package ai.ravenroot.core.graph;

import java.util.Objects;
import java.util.Map;

public record GraphNode(String id, NodeKind kind, String behavior, Map<String, Object> properties) {

    public GraphNode {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A graph node must have a non-blank id");
        }
        Objects.requireNonNull(kind, "kind");
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        if (kind == NodeKind.BEHAVIOR && (behavior == null || behavior.isBlank())) {
            throw new IllegalArgumentException("A behavior node must declare a behavior name");
        }
        if (kind != NodeKind.BEHAVIOR && behavior != null) {
            throw new IllegalArgumentException("Only behavior nodes may declare a behavior name");
        }
    }

    public GraphNode(String id, NodeKind kind, String behavior) {
        this(id, kind, behavior, Map.of());
    }

    public static GraphNode start(String id) {
        return new GraphNode(id, NodeKind.START, null);
    }

    public static GraphNode passthrough(String id) {
        return new GraphNode(id, NodeKind.PASSTHROUGH, null);
    }

    public static GraphNode behavior(String id, String behavior) {
        return new GraphNode(id, NodeKind.BEHAVIOR, behavior);
    }

    public static GraphNode end(String id) {
        return new GraphNode(id, NodeKind.END, null);
    }

    /** The graph's error terminal. See {@link NodeKind#ERROR} for why it is a kind of its own. */
    public static GraphNode error(String id) {
        return new GraphNode(id, NodeKind.ERROR, null);
    }
}
