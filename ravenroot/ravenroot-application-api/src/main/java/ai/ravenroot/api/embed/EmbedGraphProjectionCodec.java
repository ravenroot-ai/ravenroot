package ai.ravenroot.api.embed;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads back exactly what {@link EmbedGraphProjection#toJson()} writes, and nothing else.
 *
 * <h2>Why a hand-written reader rather than a JSON library</h2>
 * <p>This parser exists so a durable store can fold a captured projection off disk. Its input is
 * therefore not untrusted user JSON in the usual sense — it is this product's own serializer output —
 * but the threat that matters is precisely the case where it is <em>not</em>: a hand-edited row, a
 * half-applied migration, a restored file from another deployment. A general parser accepts all of
 * those and hands back a document; this one accepts one shape and refuses everything else, so a
 * tampered row fails construction instead of becoming a projection with extra members. Field order,
 * key set and value types are all fixed, which is what makes «refuses everything else» checkable.</p>
 *
 * <p>The parsed values still flow through {@link EmbedGraphProjection}'s canonical constructor, so
 * node-kind allowlisting and layout finiteness are enforced by the record and not re-implemented
 * here.</p>
 */
public final class EmbedGraphProjectionCodec {

    private final String source;
    private int cursor;

    private EmbedGraphProjectionCodec(String source) {
        this.source = source;
    }

    /** Serializes a validated projection using the exact durable-store wire shape.
     * @param projection projection to encode
     * @return canonical JSON representation
     */
    public static String encode(EmbedGraphProjection projection) {
        return Objects.requireNonNull(projection, "projection").toJson();
    }

    /** Decodes the strict projection wire shape without accepting extra JSON members.
     * @param json canonical projection JSON written by {@link #encode(EmbedGraphProjection)}
     * @return validated graph projection
     * @throws IllegalArgumentException if {@code json} is not exactly one encoded projection
     */
    public static EmbedGraphProjection decode(String json) {
        Objects.requireNonNull(json, "json");
        var codec = new EmbedGraphProjectionCodec(json);
        EmbedGraphProjection projection = codec.projection();
        if (codec.cursor != json.length()) throw invalid("trailing content after the projection");
        return projection;
    }

    private EmbedGraphProjection projection() {
        expect('{');
        String contractVersion = member("viewerContractVersion");
        expect(',');
        String graphId = member("graphId");
        expect(',');
        String graphVersionId = member("graphVersionId");
        expect(',');
        String canonicalDigest = member("canonicalDigest");
        expect(',');
        key("nodes");
        List<EmbedGraphProjection.Node> nodes = nodes();
        expect(',');
        key("edges");
        List<EmbedGraphProjection.Edge> edges = edges();
        expect('}');
        return new EmbedGraphProjection(contractVersion, graphId, graphVersionId, canonicalDigest,
                nodes, edges);
    }

    private List<EmbedGraphProjection.Node> nodes() {
        var parsed = new ArrayList<EmbedGraphProjection.Node>();
        expect('[');
        if (peek() == ']') {
            cursor++;
            return parsed;
        }
        while (true) {
            expect('{');
            String id = member("id");
            expect(',');
            String kind = member("kind");
            expect(',');
            key("layout");
            EmbedGraphProjection.Layout layout = layout();
            expect('}');
            parsed.add(new EmbedGraphProjection.Node(id, kind, layout));
            if (peek() == ',') {
                cursor++;
                continue;
            }
            expect(']');
            return parsed;
        }
    }

    private EmbedGraphProjection.Layout layout() {
        if (peek() == 'n') {
            literal("null");
            return null;
        }
        expect('{');
        key("x");
        double x = number();
        expect(',');
        key("y");
        double y = number();
        expect(',');
        key("width");
        double width = number();
        expect(',');
        key("height");
        double height = number();
        expect('}');
        return new EmbedGraphProjection.Layout(x, y, width, height);
    }

    private List<EmbedGraphProjection.Edge> edges() {
        var parsed = new ArrayList<EmbedGraphProjection.Edge>();
        expect('[');
        if (peek() == ']') {
            cursor++;
            return parsed;
        }
        while (true) {
            expect('{');
            String source0 = member("source");
            expect(',');
            String target = member("target");
            expect('}');
            parsed.add(new EmbedGraphProjection.Edge(source0, target));
            if (peek() == ',') {
                cursor++;
                continue;
            }
            expect(']');
            return parsed;
        }
    }

    private String member(String name) {
        key(name);
        return string();
    }

    private void key(String name) {
        String read = string();
        if (!read.equals(name)) throw invalid("expected the key " + name);
        expect(':');
    }

    private String string() {
        expect('"');
        var value = new StringBuilder();
        while (true) {
            if (cursor >= source.length()) throw invalid("unterminated string");
            char character = source.charAt(cursor++);
            if (character == '"') return value.toString();
            if (character != '\\') {
                if (character < 0x20) throw invalid("unescaped control character");
                value.append(character);
                continue;
            }
            if (cursor >= source.length()) throw invalid("unterminated escape");
            char escaped = source.charAt(cursor++);
            switch (escaped) {
                case '"' -> value.append('"');
                case '\\' -> value.append('\\');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'u' -> {
                    if (cursor + 4 > source.length()) throw invalid("truncated unicode escape");
                    String hex = source.substring(cursor, cursor + 4);
                    cursor += 4;
                    try {
                        value.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException notHex) {
                        throw invalid("invalid unicode escape");
                    }
                }
                // Deliberately no default that passes the character through: the serializer emits
                // exactly the escapes above, so anything else is a document this codec did not write.
                default -> throw invalid("unsupported escape");
            }
        }
    }

    private double number() {
        int start = cursor;
        while (cursor < source.length() && "+-.eE0123456789".indexOf(source.charAt(cursor)) >= 0) cursor++;
        if (start == cursor) throw invalid("expected a number");
        double value;
        try {
            value = Double.parseDouble(source.substring(start, cursor));
        } catch (NumberFormatException notANumber) {
            throw invalid("expected a number");
        }
        if (!Double.isFinite(value)) throw invalid("numbers must be finite");
        return value;
    }

    private void literal(String expected) {
        if (!source.startsWith(expected, cursor)) throw invalid("expected " + expected);
        cursor += expected.length();
    }

    private char peek() {
        if (cursor >= source.length()) throw invalid("unexpected end of document");
        return source.charAt(cursor);
    }

    private void expect(char expected) {
        if (peek() != expected) throw invalid("expected " + expected);
        cursor++;
    }

    private static IllegalArgumentException invalid(String detail) {
        // No position and no excerpt: this message reaches logs, and the document is a graph.
        return new IllegalArgumentException("the encoded embed projection is malformed: " + detail);
    }
}
