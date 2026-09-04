package ai.ravenroot.persistence.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteHumanTaskMigrationUpgradeTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void anExistingDatabaseUpgradesByApplyingTheHumanTaskMigrationExactlyOnce(
            @TempDir Path directory) throws Exception {
        Path database = directory.resolve("human-task-upgrade.db");
        int humanTaskVersion = SqliteSchema.migrations().stream()
                .filter(migration -> migration.statements().stream()
                        .anyMatch(sql -> sql.contains("CREATE TABLE human_task")))
                .mapToInt(SchemaMigration::version).findFirst().orElseThrow();
        List<SchemaMigration> previous = SqliteSchema.migrations().stream()
                .filter(migration -> migration.version() < humanTaskVersion).toList();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            assertEquals(humanTaskVersion - 1, SqliteSchema.migrate(connection, previous, CLOCK));
            assertFalse(tableExists(connection, "human_task"));
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            assertEquals(SqliteSchema.currentVersion(), SqliteSchema.migrate(connection, CLOCK));
            assertTrue(tableExists(connection, "human_task"));
            assertEquals(1, indexCount(connection, "human_task_live_correlation"));
            assertEquals(1, historyRows(connection, humanTaskVersion));
            assertEquals(SqliteSchema.currentVersion(), SqliteSchema.migrate(connection, CLOCK));
            assertEquals(1, indexCount(connection, "human_task_live_correlation"));
        }
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) == 1;
            }
        }
    }

    private static int indexCount(Connection connection, String index) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?")) {
            statement.setString(1, index);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private static int historyRows(Connection connection, int version) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM store_schema_history WHERE version = ?")) {
            statement.setInt(1, version);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }
}
