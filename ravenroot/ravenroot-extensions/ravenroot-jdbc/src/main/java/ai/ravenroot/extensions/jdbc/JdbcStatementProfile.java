package ai.ravenroot.extensions.jdbc;

import java.util.List;
import java.util.Locale;
import java.util.Set;

record JdbcStatementProfile(String id, Kind kind, NamedSql sql, Set<String> generatedKeys) {
    enum Kind { QUERY, INSERT }

    JdbcStatementProfile {
        generatedKeys = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(
                generatedKeys == null ? Set.of() : generatedKeys));
        if (!identifier(id) || sql == null || generatedKeys.size() > 32
                || generatedKeys.stream().anyMatch(key -> !column(key))
                || kind == Kind.QUERY && !generatedKeys.isEmpty()) throw invalid();
        String leading = stripLeadingComments(sql.jdbcSql()).stripLeading().toUpperCase(Locale.ROOT);
        if (kind == Kind.QUERY && !leading.startsWith("SELECT ") && !leading.startsWith("SELECT\n")) throw invalid();
        if (kind == Kind.INSERT && !leading.startsWith("INSERT ") && !leading.startsWith("INSERT\n")) throw invalid();
    }

    List<String> orderedParameters() { return sql.parameterOrder(); }

    private static String stripLeadingComments(String value) {
        String current = value;
        while (true) {
            current = current.stripLeading();
            if (current.startsWith("--")) {
                int line = current.indexOf('\n'); if (line < 0) return ""; current = current.substring(line + 1);
            } else if (current.startsWith("/*")) {
                int end = current.indexOf("*/", 2); if (end < 0) return ""; current = current.substring(end + 2);
            } else return current;
        }
    }

    private static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"); }
    private static boolean column(String value) { return value != null && value.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}"); }
    private static JdbcFailure invalid() { return new JdbcFailure(JdbcFailure.Code.PROFILE_UNAVAILABLE); }
}
