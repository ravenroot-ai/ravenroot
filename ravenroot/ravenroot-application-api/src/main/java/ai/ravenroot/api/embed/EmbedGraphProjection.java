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
 */
public record EmbedGraphProjection(String viewerContractVersion, String graphId, String graphVersionId,
                                   String canonicalDigest, List<Node> nodes, List<Edge> edges) {
    public static final String CURRENT_CONTRACT_VERSION = "1.0";
    private static final java.util.Set<String> NODE_KINDS =
            java.util.Set.of("START", "PASSTHROUGH", "BEHAVIOR", "END", "ERROR");

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
    }

/**
 * Render-only node whose kind is limited to the browser viewer vocabulary.
 * @param id graph node identifier
 * @param kind allowlisted viewer node kind
 * @param layout optional finite node bounds supplied by the captured projection
 */
    public record Node(String id, String kind, Layout layout) {
/**
 * Rejects unknown node kinds and missing identifiers.
 */
        public Node {
            id = requireText(id, "node.id");
            kind = requireText(kind, "node.kind");
            if (!NODE_KINDS.contains(kind)) throw new IllegalArgumentException("node.kind is not supported");
        }
    }

/**
 * Directed render-only edge between two projection node IDs.
 * @param source source node identifier
 * @param target target node identifier
 */
    public record Edge(String source, String target) {
/**
 * Rejects blank endpoint identifiers.
 */
        public Edge {
            source = requireText(source, "edge.source");
            target = requireText(target, "edge.target");
        }
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
        String edgeJson = edges.stream().map(edge -> "{\"source\":\"" + escape(edge.source())
                        + "\",\"target\":\"" + escape(edge.target()) + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"viewerContractVersion\":\"" + escape(viewerContractVersion)
                + "\",\"graphId\":\"" + escape(graphId) + "\",\"graphVersionId\":\""
                + escape(graphVersionId) + "\",\"canonicalDigest\":\"" + escape(canonicalDigest)
                + "\",\"nodes\":[" + nodeJson + "],\"edges\":[" + edgeJson + "]}";
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
                + "\",\"layout\":" + layout + "}";
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
}
