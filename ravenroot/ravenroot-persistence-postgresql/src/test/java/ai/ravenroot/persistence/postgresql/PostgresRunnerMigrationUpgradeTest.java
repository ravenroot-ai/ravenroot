package ai.ravenroot.persistence.postgresql;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runner tables append to the already integrated lifecycle schema, without renumbering it. */
class PostgresRunnerMigrationUpgradeTest {
    @Test
    void runnerMigrationPreservesLifecycleSchemaAndPreviouslyWrittenData() throws Exception {
        var source = PostgresTestDatabase.dataSourceFor("lifecycle-to-runner-" + UUID.randomUUID());
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            var lifecycle = PostgresSchema.migrations().stream().filter(step -> step.version() <= 7).toList();
            assertEquals(7, SchemaRunner.migrate(connection, lifecycle, Clock.systemUTC()));
            statement.execute("CREATE TABLE migration_sentinel (value TEXT NOT NULL)");
            statement.execute("INSERT INTO migration_sentinel VALUES ('preserved')");
            assertEquals(8, SchemaRunner.migrate(connection, PostgresSchema.migrations(), Clock.systemUTC()));
            assertEquals(8, SchemaRunner.migrate(connection, PostgresSchema.migrations(), Clock.systemUTC()));
            for (String table : new String[] {"human_task", "session_memory", "session_memory_command",
                    "runner_workspace", "runner_catalog", "runner_catalog_tenant"}) {
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
            }
            statement.executeQuery("SELECT last_lifecycle_reason FROM deployment").close();
            statement.executeQuery("SELECT review_text FROM human_task").close();
            try (var row = statement.executeQuery("SELECT value FROM migration_sentinel")) {
                assertTrue(row.next());
                assertEquals("preserved", row.getString(1));
            }
        }
    }
}
