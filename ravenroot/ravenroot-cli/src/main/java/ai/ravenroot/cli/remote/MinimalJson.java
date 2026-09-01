package ai.ravenroot.cli.remote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader for exactly the flat response shapes {@code RavenrootServer} emits
 * (API-05) — not a general-purpose parser, and not meant to become one. Objects, arrays, strings (with
 * the standard escapes), numbers and {@code true}/{@code false}/{@code null}. No streaming, no
 * incremental parsing, no comments or trailing commas: the server never emits any of those, so reading
 * them was never a requirement.
 */
final class MinimalJson {
    private final String text;
    private int position;

    private MinimalJson(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        var parser = new MinimalJson(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.position != text.length()) {
            throw new IllegalArgumentException("Trailing content after JSON value at offset " + parser.position);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Expected a JSON object, got: " + value);
    }

    @SuppressWarnings("unchecked")
    static List<Object> asArray(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new IllegalArgumentException("Expected a JSON array, got: " + value);
    }

    static String asString(Object value) {
        if (value instanceof String string) {
            return string;
        }
        throw new IllegalArgumentException("Expected a JSON string, got: " + value);
    }

    static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("Expected a JSON number, got: " + value);
    }

    /** {@code RemoteBackend.inspect} reads {@code "valid"} through this boolean conversion. */
    static boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalArgumentException("Expected a JSON boolean, got: " + value);
    }

    /**
     * The write side of this reader: re-serialises exactly the model {@link #parse} produces
     * ({@code Map}, {@code List}, {@code String}, {@code Long}/{@code Double}, {@code Boolean}, or
     * {@code null}) as compact JSON. Needed so {@link RemoteBackend#result} can hand back the
     * {@code payload} sub-value it parsed out of the response body as JSON text again, without this
     * class growing a second, JSON-object-shaped public contract.
     */
    static String write(Object value) {
        var out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String string) {
            writeString(string, out);
        } else if (value instanceof Boolean bool) {
            out.append(bool);
        } else if (value instanceof Long || value instanceof Integer) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            out.append(value);
        } else if (value instanceof Number number) {
            out.append(number);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            boolean first = true;
            for (Object element : list) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(element, out);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Not a JSON-model value: " + value.getClass());
        }
    }

    private static void writeString(String string, StringBuilder out) {
        out.append('"');
        for (int index = 0; index < string.length(); index++) {
            char character = string.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        out.append('"');
    }

    private Object readValue() {
        skipWhitespace();
        if (position >= text.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON input");
        }
        char next = text.charAt(position);
        return switch (next) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        var result = new LinkedHashMap<String, Object>();
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return result;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            Object value = readValue();
            result.put(key, value);
            skipWhitespace();
            char delimiter = text.charAt(position++);
            if (delimiter == '}') {
                return result;
            }
            if (delimiter != ',') {
                throw new IllegalArgumentException("Expected ',' or '}' at offset " + (position - 1));
            }
        }
    }

    private List<Object> readArray() {
        expect('[');
        var result = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return result;
        }
        while (true) {
            result.add(readValue());
            skipWhitespace();
            char delimiter = text.charAt(position++);
            if (delimiter == ']') {
                return result;
            }
            if (delimiter != ',') {
                throw new IllegalArgumentException("Expected ',' or ']' at offset " + (position - 1));
            }
        }
    }

    private String readString() {
        expect('"');
        var builder = new StringBuilder();
        while (true) {
            char character = text.charAt(position++);
            if (character == '"') {
                return builder.toString();
            }
            if (character == '\\') {
                char escape = text.charAt(position++);
                switch (escape) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'u' -> {
                        int code = Integer.parseInt(text.substring(position, position + 4), 16);
                        builder.append((char) code);
                        position += 4;
                    }
                    default -> throw new IllegalArgumentException("Unknown escape '\\" + escape + "'");
                }
            } else {
                builder.append(character);
            }
        }
    }

    private Object readNumber() {
        int start = position;
        while (position < text.length() && "-+.eE0123456789".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
        String literal = text.substring(start, position);
        if (literal.isEmpty()) {
            throw new IllegalArgumentException("Expected a JSON value at offset " + start);
        }
        return literal.contains(".") || literal.contains("e") || literal.contains("E")
                ? (Object) Double.parseDouble(literal) : (Object) Long.parseLong(literal);
    }

    private Object readLiteral(String literal, Object value) {
        if (!text.regionMatches(position, literal, 0, literal.length())) {
            throw new IllegalArgumentException("Expected '" + literal + "' at offset " + position);
        }
        position += literal.length();
        return value;
    }

    private void expect(char character) {
        if (position >= text.length() || text.charAt(position) != character) {
            throw new IllegalArgumentException("Expected '" + character + "' at offset " + position);
        }
        position++;
    }

    private char peek() {
        return text.charAt(position);
    }

    private void skipWhitespace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }
}
