package ai.ravenroot.core.embed;

import ai.ravenroot.api.embed.EmbedGraphProjection;
import ai.ravenroot.api.embed.EmbedProjectionBudget;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded parser for the one allowlisted visual-group metadata property. */
final class EmbedVisualGroups {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_GROUPS = 2000;
    private static final int MAX_NAME = 160;

    private EmbedVisualGroups() { }

    static List<EmbedGraphProjection.Group> parse(Object raw, Set<String> nodeIds,
                                                  EmbedProjectionBudget budget) {
        if (!(raw instanceof String text) || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return List.of();
        }
        try {
            Object value = new Parser(text).parse();
            if (!(value instanceof Map<?, ?> root) || !root.keySet().equals(Set.of("version", "groups"))
                    || !Integer.valueOf(1).equals(root.get("version"))
                    || !(root.get("groups") instanceof List<?> encoded) || encoded.size() > MAX_GROUPS) {
                return List.of();
            }
            var result = new ArrayList<EmbedGraphProjection.Group>();
            var groupIds = new java.util.HashSet<String>();
            var members = new java.util.HashSet<String>();
            for (Object item : encoded) {
                if (!(item instanceof Map<?, ?> group)
                        || !group.keySet().equals(Set.of("id", "name", "memberNodeIds", "anchorNodeId", "collapsed"))
                        || !(group.get("id") instanceof String id) || id.isBlank()
                        || !(group.get("name") instanceof String name) || name.isBlank()
                        || !(group.get("anchorNodeId") instanceof String anchor)
                        || !(group.get("collapsed") instanceof Boolean collapsed)
                        || !(group.get("memberNodeIds") instanceof List<?> rawMembers)
                        || id.length() > budget.maxIdentifierChars() || name.length() > MAX_NAME
                        || !groupIds.add(id) || rawMembers.size() < 2) return List.of();
                var memberIds = new ArrayList<String>();
                for (Object member : rawMembers) {
                    if (!(member instanceof String memberId) || memberId.length() > budget.maxIdentifierChars()
                            || !nodeIds.contains(memberId) || !members.add(memberId)) return List.of();
                    memberIds.add(memberId);
                }
                if (!memberIds.contains(anchor)) return List.of();
                result.add(new EmbedGraphProjection.Group(id, name.trim(), memberIds, anchor, collapsed));
            }
            return List.copyOf(result);
        } catch (RuntimeException malformed) {
            // Optional malformed/future metadata must never suppress an otherwise valid deployed graph.
            return List.of();
        }
    }

    private static final class Parser {
        private final String source;
        private int cursor;
        private int values;

        private Parser(String source) { this.source = source; }

        private Object parse() {
            Object value = value(0);
            whitespace();
            if (cursor != source.length()) throw new IllegalArgumentException();
            return value;
        }

        private Object value(int depth) {
            if (depth > 5 || ++values > 20_000) throw new IllegalArgumentException();
            whitespace();
            if (cursor >= source.length()) throw new IllegalArgumentException();
            return switch (source.charAt(cursor)) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                default -> integer();
            };
        }

        private Map<String, Object> object(int depth) {
            expect('{');
            var result = new LinkedHashMap<String, Object>();
            whitespace();
            if (take('}')) return result;
            while (true) {
                whitespace();
                String key = string();
                whitespace(); expect(':');
                if (result.putIfAbsent(key, value(depth)) != null) throw new IllegalArgumentException();
                whitespace();
                if (take('}')) return result;
                expect(',');
            }
        }

        private List<Object> array(int depth) {
            expect('[');
            var result = new ArrayList<>();
            whitespace();
            if (take(']')) return result;
            while (true) {
                result.add(value(depth));
                whitespace();
                if (take(']')) return result;
                expect(',');
            }
        }

        private String string() {
            expect('"');
            var result = new StringBuilder();
            while (cursor < source.length()) {
                char c = source.charAt(cursor++);
                if (c == '"') return result.toString();
                if (c < 0x20) throw new IllegalArgumentException();
                if (c != '\\') { result.append(c); continue; }
                if (cursor >= source.length()) throw new IllegalArgumentException();
                char escaped = source.charAt(cursor++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (cursor + 4 > source.length()) throw new IllegalArgumentException();
                        result.append((char) Integer.parseInt(source.substring(cursor, cursor + 4), 16));
                        cursor += 4;
                    }
                    default -> throw new IllegalArgumentException();
                }
            }
            throw new IllegalArgumentException();
        }

        private Integer integer() {
            int start = cursor;
            if (take('-') && cursor >= source.length()) throw new IllegalArgumentException();
            while (cursor < source.length() && Character.isDigit(source.charAt(cursor))) cursor++;
            if (start == cursor) throw new IllegalArgumentException();
            return Integer.valueOf(source.substring(start, cursor));
        }

        private Object literal(String token, Object value) {
            if (!source.startsWith(token, cursor)) throw new IllegalArgumentException();
            cursor += token.length();
            return value;
        }

        private boolean take(char expected) {
            if (cursor < source.length() && source.charAt(cursor) == expected) { cursor++; return true; }
            return false;
        }

        private void expect(char expected) { if (!take(expected)) throw new IllegalArgumentException(); }
        private void whitespace() { while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) cursor++; }
    }
}
