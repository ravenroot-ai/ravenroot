package ai.ravenroot.persistence.postgresql;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Upgrade coverage for the selective replay evidence schema. */
class PostgresSelectiveReplayMigrationUpgradeTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void anExistingDatabaseGainsReplayTablesExactlyOnce() throws Exception {
        DataSource dataSource = PostgresTestDatabase.dataSourceFor(
                "replay-upgrade-" + UUID.randomUUID());
        int replayVersion = replayMigrationVersion();
        List<SchemaMigration> beforeReplay = PostgresSchema.migrations().stream()
                .filter(migration -> migration.version() < replayVersion).toList();

        try (Connection connection = dataSource.getConnection()) {
            assertEquals(replayVersion - 1, SchemaRunner.migrate(connection, beforeReplay, CLOCK));
            assertFalse(tableExists(connection, "replay_invocation_evidence"));
            assertFalse(tableExists(connection, "replay_source_settlement"));
            assertFalse(tableExists(connection, "derived_execution_ancestry"));
            assertEquals(PostgresSchema.currentVersion(), PostgresSchema.migrate(connection, CLOCK));
            assertTrue(tableExists(connection, "replay_invocation_evidence"));
            assertTrue(tableExists(connection, "replay_source_settlement"));
            assertTrue(tableExists(connection, "derived_execution_ancestry"));
            assertEquals(1, historyRows(connection, replayVersion));
            assertEquals(PostgresSchema.currentVersion(), PostgresSchema.migrate(connection, CLOCK));
            assertEquals(1, historyRows(connection, replayVersion));
        }
    }

    private static int replayMigrationVersion() {
        return PostgresSchema.migrations().stream()
                .filter(migration -> migration.statements().stream()
                        .anyMatch(statement -> statement.contains("CREATE TABLE replay_invocation_evidence")))
                .map(SchemaMigration::version).findFirst().orElseThrow();
    }

    private static boolean tableExists(Connection connection, String name) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = current_schema() "
                        + "AND table_name = ?")) {
            statement.setString(1, name);
            try (var rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) == 1;
            }
        }
    }

    private static int historyRows(Connection connection, int version) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM store_schema_history WHERE version = ?")) {
            statement.setInt(1, version);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }
}
