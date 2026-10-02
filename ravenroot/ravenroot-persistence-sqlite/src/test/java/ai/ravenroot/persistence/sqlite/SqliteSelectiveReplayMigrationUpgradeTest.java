package ai.ravenroot.persistence.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Upgrade coverage for the selective replay evidence schema. */
class SqliteSelectiveReplayMigrationUpgradeTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void anExistingDatabaseGainsReplayTablesExactlyOnce(@TempDir Path directory) throws Exception {
        Path database = directory.resolve("replay-upgrade.db");
        int replayVersion = replayMigrationVersion();
        List<SchemaMigration> beforeReplay = SqliteSchema.migrations().stream()
                .filter(migration -> migration.version() < replayVersion).toList();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            assertEquals(replayVersion - 1, SqliteSchema.migrate(connection, beforeReplay, CLOCK));
            assertFalse(tableExists(connection, "replay_invocation_evidence"));
            assertFalse(tableExists(connection, "replay_source_settlement"));
            assertFalse(tableExists(connection, "derived_execution_ancestry"));
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            assertEquals(SqliteSchema.currentVersion(), SqliteSchema.migrate(connection, CLOCK));
            assertTrue(tableExists(connection, "replay_invocation_evidence"));
            assertTrue(tableExists(connection, "replay_source_settlement"));
            assertTrue(tableExists(connection, "derived_execution_ancestry"));
            assertEquals(1, historyRows(connection, replayVersion));
            assertEquals(SqliteSchema.currentVersion(), SqliteSchema.migrate(connection, CLOCK));
            assertEquals(1, historyRows(connection, replayVersion));
        }
    }

    private static int replayMigrationVersion() {
        return SqliteSchema.migrations().stream()
                .filter(migration -> migration.statements().stream()
                        .anyMatch(statement -> statement.contains("CREATE TABLE replay_invocation_evidence")))
                .map(SchemaMigration::version).findFirst().orElseThrow();
    }

    private static boolean tableExists(Connection connection, String name) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?")) {
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
