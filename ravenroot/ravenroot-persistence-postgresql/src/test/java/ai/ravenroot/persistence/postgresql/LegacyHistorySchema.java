package ai.ravenroot.persistence.postgresql;

import java.sql.Connection;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Removes only the empty additive history step when reconstructing an older released fixture. */
final class LegacyHistorySchema {
    private LegacyHistorySchema() { }
    static void removeEmptyHistoryStep(Connection connection) throws Exception {
        var step = PostgresSchema.migrations().stream().filter(value -> value.version() == 21).findFirst().orElseThrow();
        var create = Pattern.compile("CREATE (TABLE|INDEX) ([a-z_]+) .+", Pattern.DOTALL);
        try (var statement = connection.createStatement()) {
            for (String ddl : step.statements().reversed()) {
                var match = create.matcher(ddl);
                assertTrue(match.matches(), "A changed history migration requires a new reviewed legacy fixture");
                if (match.group(1).equals("TABLE")) try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + match.group(2))) {
                    assertTrue(rows.next()); assertEquals(0, rows.getLong(1), "Legacy fixture must not discard any history or custody rows");
                }
                statement.execute("DROP " + match.group(1) + " " + match.group(2));
            }
            assertEquals(1, statement.executeUpdate("DELETE FROM store_schema_history WHERE version = 21"));
            assertEquals(1, statement.executeUpdate("UPDATE store_schema_version SET version = 20"));
        }
    }
}
