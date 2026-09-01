package ai.ravenroot.extensions.jdbc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NamedSqlTest {
    @Test void replacesOnlyNormalStateNamesAndPreservesRepeatedBindingOrder() {
        NamedSql parsed = NamedSql.parse("SELECT ':literal', col::text FROM t -- :comment\n"
                + "WHERE a=:value OR b=:value AND c=:other /* :hidden */");
        assertEquals("SELECT ':literal', col::text FROM t -- :comment\n"
                + "WHERE a=? OR b=? AND c=? /* :hidden */", parsed.jdbcSql());
        assertEquals(List.of("value", "value", "other"), parsed.parameterOrder());
        assertEquals(Set.of("value", "other"), parsed.parameterNames());
    }

    @Test void rejectsMultiStatementUnclosedSyntaxAndUnsupportedStatementKinds() {
        assertThrows(JdbcFailure.class, () -> NamedSql.parse("SELECT 1; DELETE FROM t"));
        assertThrows(JdbcFailure.class, () -> NamedSql.parse("SELECT 'open"));
        assertThrows(JdbcFailure.class, () -> NamedSql.parse("SELECT 1 /* open"));
        assertThrows(JdbcFailure.class, () -> NamedSql.parse("SELECT '\\\'ambiguous'"));
        assertThrows(JdbcFailure.class, () -> new JdbcStatementProfile("x", JdbcStatementProfile.Kind.QUERY,
                NamedSql.parse("CALL dangerous()"), Set.of()));
        assertThrows(JdbcFailure.class, () -> new JdbcStatementProfile("x", JdbcStatementProfile.Kind.INSERT,
                NamedSql.parse("DELETE FROM t"), Set.of()));
    }
}
