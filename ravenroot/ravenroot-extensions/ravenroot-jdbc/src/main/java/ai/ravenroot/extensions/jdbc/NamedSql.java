package ai.ravenroot.extensions.jdbc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

record NamedSql(String jdbcSql, List<String> parameterOrder, Set<String> parameterNames) {
    NamedSql {
        parameterOrder = List.copyOf(parameterOrder);
        parameterNames = Set.copyOf(parameterNames);
    }

    static NamedSql parse(String source) {
        if (source == null || source.isBlank() || source.length() > 65_536) throw invalid();
        StringBuilder out = new StringBuilder(source.length());
        List<String> order = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        State state = State.NORMAL;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            switch (state) {
                case NORMAL -> {
                    if (c == ';') throw invalid();
                    if (c == '\'') { state = State.SINGLE; out.append(c); }
                    else if (c == '"') { state = State.DOUBLE; out.append(c); }
                    else if (c == '-' && next == '-') { state = State.LINE_COMMENT; out.append(c).append(next); i++; }
                    else if (c == '/' && next == '*') { state = State.BLOCK_COMMENT; out.append(c).append(next); i++; }
                    else if (c == ':' && next == ':') { out.append(c).append(next); i++; }
                    else if (c == ':' && identifierStart(next)) {
                        int end = i + 2;
                        while (end < source.length() && identifierPart(source.charAt(end))) end++;
                        String name = source.substring(i + 1, end);
                        if (name.length() > 64 || order.size() >= 256) throw invalid();
                        order.add(name); names.add(name); out.append('?'); i = end - 1;
                    } else out.append(c);
                }
                case SINGLE -> {
                    if (c == '\\') throw invalid();
                    out.append(c);
                    if (c == '\'' && next == '\'') { out.append(next); i++; }
                    else if (c == '\'') state = State.NORMAL;
                }
                case DOUBLE -> {
                    if (c == '\\') throw invalid();
                    out.append(c);
                    if (c == '"' && next == '"') { out.append(next); i++; }
                    else if (c == '"') state = State.NORMAL;
                }
                case LINE_COMMENT -> { out.append(c); if (c == '\n') state = State.NORMAL; }
                case BLOCK_COMMENT -> {
                    out.append(c);
                    if (c == '*' && next == '/') { out.append(next); i++; state = State.NORMAL; }
                }
            }
        }
        if (state == State.SINGLE || state == State.DOUBLE || state == State.BLOCK_COMMENT) throw invalid();
        return new NamedSql(out.toString(), order, names);
    }

    private static boolean identifierStart(char c) { return Character.isLetter(c) || c == '_'; }
    private static boolean identifierPart(char c) { return Character.isLetterOrDigit(c) || c == '_'; }
    private static JdbcFailure invalid() { return new JdbcFailure(JdbcFailure.Code.PROFILE_UNAVAILABLE); }
    private enum State { NORMAL, SINGLE, DOUBLE, LINE_COMMENT, BLOCK_COMMENT }
}
