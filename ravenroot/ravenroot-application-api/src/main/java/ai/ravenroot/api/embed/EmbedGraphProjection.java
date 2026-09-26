package ai.ravenroot.api.embed;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Versioned, render-only and allowlisted browser DTO. It is not a persisted graph snapshot.
 * @param viewerContractVersion browser-viewer contract version used to interpret this DTO
 * @param graphId stable identifier of the captured graph
 * @param graphVersionId stable identifier of the captured graph version
 * @param canonicalDigest digest that binds the projection to the captured graph content
 * @param nodes allowlisted render-only nodes in the projection
 * @param edges render-only edges between the listed nodes
 * @param designArrangement optional persisted semantic Design arrangement
 */
public record EmbedGraphProjection(String viewerContractVersion, String graphId, String graphVersionId,
                                   String canonicalDigest, List<Node> nodes, List<Edge> edges,
                                   String designArrangement) {
    public static final String CURRENT_CONTRACT_VERSION = "1.0";
    private static final java.util.Set<String> NODE_KINDS =
            java.util.Set.of("START", "PASSTHROUGH", "BEHAVIOR", "END", "ERROR");
    private static final java.util.Set<String> DESIGN_ARRANGEMENTS = java.util.Set.of(
            "hierarchical", "flow", "organic", "keep", "hierarchical-new", "layered-down");

    /**
     * Compatibility shape for projections captured before Design arrangement was projected.
     * @param viewerContractVersion browser-viewer contract version used to interpret this DTO
     * @param graphId stable identifier of the captured graph
     * @param graphVersionId stable identifier of the captured graph version
     * @param canonicalDigest digest binding this projection to captured graph content
     * @param nodes allowlisted render-only nodes
     * @param edges render-only edges between the listed nodes
     */
    public EmbedGraphProjection(String viewerContractVersion, String graphId, String graphVersionId,
                                String canonicalDigest, List<Node> nodes, List<Edge> edges) {
        this(viewerContractVersion, graphId, graphVersionId, canonicalDigest, nodes, edges, null);
    }

/**
 * Validates required capture coordinates and makes the rendered collections immutable.
 */
    public EmbedGraphProjection {
        viewerContractVersion = requireText(viewerContractVersion, "viewerContractVersion");
        graphId = requireText(graphId, "graphId");
        graphVersionId = requireText(graphVersionId, "graphVersionId");
        canonicalDigest = requireText(canonicalDigest, "canonicalDigest");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        designArrangement = optionalText(designArrangement, "designArrangement");
        if (designArrangement != null && !DESIGN_ARRANGEMENTS.contains(designArrangement)) {
            throw new IllegalArgumentException("designArrangement is not supported");
        }
    }

/**
 * Render-only node whose kind is limited to the browser viewer vocabulary.
 * @param id graph node identifier
 * @param kind allowlisted viewer node kind
 * @param layout optional finite node bounds supplied by the captured projection
 * @param label optional author-facing label
 * @param visualType optional allowlisted presentation classification
 * @param bypassed whether runtime execution bypasses this node
 */
    public record Node(String id, String kind, Layout layout, String label, String visualType,
                       boolean bypassed) {
        /**
         * Compatibility shape used by the original static projection contract.
         * @param id graph node identifier
         * @param kind allowlisted viewer node kind
         * @param layout optional finite node bounds
         */
        public Node(String id, String kind, Layout layout) {
            this(id, kind, layout, null, null, false);
        }

/**
 * Rejects unknown node kinds and missing identifiers.
 */
        public Node {
            id = requireText(id, "node.id");
            kind = requireText(kind, "node.kind");
            if (!NODE_KINDS.contains(kind)) throw new IllegalArgumentException("node.kind is not supported");
            label = optionalText(label, "node.label");
            visualType = optionalText(visualType, "node.visualType");
        }
    }

/**
 * Directed render-only edge between two projection node IDs.
 * @param source source node identifier
 * @param target target node identifier
 * @param id optional stable edge identifier
 * @param label optional author-facing edge label
 * @param visualType optional allowlisted presentation classification
 * @param routing optional computed routing classification
 */
    public record Edge(String source, String target, String id, String label, String visualType,
                       Routing routing) {
        /**
         * Compatibility shape used by the original static projection contract.
         * @param source source node identifier
         * @param target target node identifier
         */
        public Edge(String source, String target) {
            this(source, target, null, null, null, null);
        }

/**
 * Rejects blank endpoint identifiers.
 */
        public Edge {
            source = requireText(source, "edge.source");
            target = requireText(target, "edge.target");
            id = optionalText(id, "edge.id");
            label = optionalText(label, "edge.label");
            visualType = optionalText(visualType, "edge.visualType");
        }
    }

    /** Closed routing classification computed from executable graph semantics. */
    public enum Routing {
        /** Ordinary named or default outcome route. */
        OUTCOME,
        /** Failure route into an error node. */
        FAILURE
    }

/**
 * Finite browser layout bounds expressed in viewer coordinate units.
 * @param x horizontal origin
 * @param y vertical origin
 * @param width positive rendered width
 * @param height positive rendered height
 */
    public record Layout(double x, double y, double width, double height) {
/**
 * Rejects non-finite coordinates and non-positive dimensions.
 */
        public Layout {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width)
                    || !Double.isFinite(height) || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("layout values must be finite and dimensions positive");
            }
        }
    }

/**
 * Deterministic serializer for the closed schema; no domain object or property map is reflected.
 * @return canonical JSON for this closed browser projection schema
 */
    public String toJson() {
        String nodeJson = nodes.stream().map(EmbedGraphProjection::nodeJson)
                .collect(java.util.stream.Collectors.joining(","));
        String edgeJson = edges.stream().map(EmbedGraphProjection::edgeJson)
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"viewerContractVersion\":\"" + escape(viewerContractVersion)
                + "\",\"graphId\":\"" + escape(graphId) + "\",\"graphVersionId\":\""
                + escape(graphVersionId) + "\",\"canonicalDigest\":\"" + escape(canonicalDigest)
                + "\",\"nodes\":[" + nodeJson + "],\"edges\":[" + edgeJson + "]"
                + optionalJson("designArrangement", designArrangement) + "}";
    }

/**
 * Counts UTF-8 bytes in the canonical JSON representation for budget enforcement.
 * @return encoded projection size in bytes
 */
    public int jsonBytes() {
        return toJson().getBytes(StandardCharsets.UTF_8).length;
    }

    private static String nodeJson(Node node) {
        String layout = node.layout() == null ? "null" : "{\"x\":" + number(node.layout().x())
                + ",\"y\":" + number(node.layout().y()) + ",\"width\":" + number(node.layout().width())
                + ",\"height\":" + number(node.layout().height()) + "}";
        return "{\"id\":\"" + escape(node.id()) + "\",\"kind\":\"" + escape(node.kind())
                + "\",\"layout\":" + layout + optionalJson("label", node.label())
                + optionalJson("visualType", node.visualType())
                + (node.bypassed() ? ",\"bypassed\":true" : "") + "}";
    }

    private static String edgeJson(Edge edge) {
        return "{\"source\":\"" + escape(edge.source()) + "\",\"target\":\""
                + escape(edge.target()) + "\"" + optionalJson("id", edge.id())
                + optionalJson("label", edge.label()) + optionalJson("visualType", edge.visualType())
                + (edge.routing() == null ? "" : ",\"routing\":\"" + edge.routing() + "\"") + "}";
    }

    private static String optionalJson(String name, String value) {
        return value == null ? "" : ",\"" + name + "\":\"" + escape(value) + "\"";
    }

    private static String number(double value) {
        return Double.toString(value);
    }

    private static String escape(String value) {
        var escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.toString();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static String optionalText(String value, String name) {
        if (value == null) return null;
        return requireText(value, name);
    }
}
