package ai.ravenroot.server.embed;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Minimal strict parser for the three closed, string-only embed request schemas. */
final class EmbedRequestJson {
    private EmbedRequestJson() { }

    static Map<String, String> parse(byte[] bytes, Set<String> expected) {
        var parser = new Parser(new String(bytes, StandardCharsets.UTF_8));
        Map<String, String> result = parser.object();
        parser.space();
        if (!parser.end() || !result.keySet().equals(expected)) throw new IllegalArgumentException("schema");
        return Map.copyOf(result);
    }

    private static final class Parser {
        private final String input;
        private int index;
        private Parser(String input) { this.input = input; }
        private Map<String, String> object() {
            space(); require('{'); space();
            var result = new LinkedHashMap<String, String>();
            if (take('}')) return result;
            while (true) {
                String key = string(); space(); require(':'); space(); String value = string();
                if (result.putIfAbsent(key, value) != null) throw new IllegalArgumentException("duplicate");
                space();
                if (take('}')) return result;
                require(','); space();
            }
        }
        private String string() {
            require('"');
            var value = new StringBuilder();
            while (index < input.length()) {
                char current = input.charAt(index++);
                if (current == '"') return value.toString();
                if (current < 0x20) throw new IllegalArgumentException("control");
                if (current != '\\') { value.append(current); continue; }
                if (index == input.length()) throw new IllegalArgumentException("escape");
                char escaped = input.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        if (index + 4 > input.length()) throw new IllegalArgumentException("unicode");
                        try { value.append((char) Integer.parseInt(input.substring(index, index + 4), 16)); }
                        catch (NumberFormatException invalid) { throw new IllegalArgumentException("unicode"); }
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("escape");
                }
            }
            throw new IllegalArgumentException("unterminated");
        }
        private void space() { while (index < input.length() && Character.isWhitespace(input.charAt(index))) index++; }
        private boolean take(char expected) { if (index < input.length() && input.charAt(index) == expected) { index++; return true; } return false; }
        private void require(char expected) { if (!take(expected)) throw new IllegalArgumentException("syntax"); }
        private boolean end() { return index == input.length(); }
    }
}
