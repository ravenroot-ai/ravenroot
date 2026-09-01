package ai.ravenroot.core.graph;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic semantic encoding for graph definitions.
 *
 * <p>Collection and property insertion order are deliberately excluded. Scalar
 * type, topology, multiplicity, ids and values remain part of the hash.</p>
 */
public final class GraphCanonicalForm {
    private static final byte[] FORMAT = "ravenroot-graph-v1".getBytes(StandardCharsets.UTF_8);

    private GraphCanonicalForm() {
    }

    public static String sha256(GraphDefinition definition) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(definition)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    static GraphDefinition immutableCopy(GraphDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("Graph definition cannot be null");
        }
        var nodes = definition.nodes().stream()
                .map(node -> new GraphNode(node.id(), node.kind(), node.behavior(), copyProperties(node.properties())))
                .toList();
        var edges = definition.edges().stream()
                .map(edge -> new GraphEdge(edge.source(), edge.target(), edge.outcome(),
                        copyProperties(edge.properties()), edge.id()))
                .toList();
        return new GraphDefinition(nodes, edges, copyProperties(definition.properties()));
    }

    static byte[] edgeBytes(GraphEdge edge) {
        return encode(output -> {
            writeString(output, edge.source());
            writeString(output, edge.target());
            writeString(output, edge.outcome());
            writeProperties(output, edge.properties());
        });
    }

    private static byte[] bytes(GraphDefinition definition) {
        GraphDefinition snapshot = immutableCopy(definition);
        return encode(output -> {
            writeBytes(output, FORMAT);
            var nodes = snapshot.nodes().stream().sorted(Comparator.comparing(GraphNode::id)).toList();
            output.writeInt(nodes.size());
            for (var node : nodes) {
                writeString(output, node.id());
                writeString(output, node.kind().name());
                writeNullableString(output, node.behavior());
                writeProperties(output, node.properties());
            }
            var edges = new ArrayList<byte[]>();
            snapshot.edges().forEach(edge -> edges.add(edgeBytes(edge)));
            edges.sort(Arrays::compareUnsigned);
            output.writeInt(edges.size());
            for (var edge : edges) {
                writeBytes(output, edge);
            }
            // Appended, and only when the document actually declared something about itself,
            // so a graph with no graph-level property encodes to exactly the bytes it encoded before
            // this line existed. That is not a nicety: this hash is a graph's address. Writing an
            // unconditional zero-length section here would change the digest of every version already
            // recorded, re-addressing documents that did not change -- the same failure mode as
            // materialising a default into a stored file, arriving through the hash instead.
            //
            // The claim is exactly that, and no wider. What is preserved is the digest of a graph
            // whose property map is EMPTY. A document that already carried some other graph-level
            // <data> -- a title, a format version, anything a graph-scoped key declared -- had that
            // datum previously dropped because nothing read it, and now has it, so its digest
            // moves. That is a real re-addressing and it is measured, not assumed. It is accepted
            // rather than worked around for two reasons: the value being hashed is now a truer
            // description of the document than the one that silently omitted it, and the digest this
            // method produces is process-local -- the persisted version pin is taken over the raw
            // document bytes, which graph-level properties do not alter. Compatibility is limited to
            // that persisted raw-document pin, not every version already recorded.
            //
            // Unambiguous because nothing follows it: an empty map and a stopped encoder produce the
            // same byte string, and any non-empty map produces a strictly longer one. So two graphs
            // that differ only in a graph-level property -- a migrated document and the legacy
            // document it came from, which is exactly the pair this has to separate -- hash
            // differently, and the migration is a new version with a new hash as an authored edit
            // should be.
            if (!snapshot.properties().isEmpty()) {
                writeProperties(output, snapshot.properties());
            }
        });
    }

    private static Map<String, Object> copyProperties(Map<String, Object> properties) {
        var copied = new LinkedHashMap<String, Object>();
        properties.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("Versioned graph property names must be non-blank");
            }
            copied.put(entry.getKey(), immutableScalar(entry.getValue(), entry.getKey()));
        });
        return Map.copyOf(copied);
    }

    private static Object immutableScalar(Object value, String property) {
        if (value instanceof String || value instanceof Boolean || value instanceof Byte
                || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof BigInteger
                || value instanceof BigDecimal || value instanceof Character) {
            return value;
        }
        throw new IllegalArgumentException("Versioned graph property '" + property
                + "' must use an immutable scalar value, found "
                + (value == null ? "null" : value.getClass().getName()));
    }

    private static void writeProperties(DataOutputStream output, Map<String, Object> properties) throws IOException {
        var entries = properties.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        output.writeInt(entries.size());
        for (var entry : entries) {
            writeString(output, entry.getKey());
            writeScalar(output, immutableScalar(entry.getValue(), entry.getKey()));
        }
    }

    private static void writeScalar(DataOutputStream output, Object value) throws IOException {
        writeString(output, value.getClass().getName());
        String encoded = switch (value) {
            case Float number -> Float.toHexString(number);
            case Double number -> Double.toHexString(number);
            case BigDecimal number -> number.toString();
            default -> value.toString();
        };
        writeString(output, encoded);
    }

    private static void writeNullableString(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            writeString(output, value);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] encode(Encoder encoder) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                encoder.write(output);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode an in-memory graph definition", impossible);
        }
    }

    @FunctionalInterface
    private interface Encoder {
        void write(DataOutputStream output) throws IOException;
    }
}
