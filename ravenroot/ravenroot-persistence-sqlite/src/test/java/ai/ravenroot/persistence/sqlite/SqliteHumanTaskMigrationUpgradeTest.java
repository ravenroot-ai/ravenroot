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
        int attentionVersion = SqliteSchema.migrations().stream()
                .filter(migration -> migration.statements().stream()
                        .anyMatch(sql -> sql.contains("human_task_context_attention")))
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
            assertTrue(columnExists(connection, "human_task", "continuation_version"));
            assertTrue(columnExists(connection, "human_task", "continuation"));
            assertTrue(columnExists(connection, "human_task", "continuation_digest"));
            assertTrue(columnExists(connection, "human_task", "decision_body_max_bytes"));
            assertTrue(columnExists(connection, "human_task", "response_max_depth"));
            assertTrue(columnExists(connection, "human_task", "response_max_collection_size"));
            assertTrue(columnExists(connection, "human_task", "response_max_value_count"));
            assertTrue(columnExists(connection, "human_task", "response_max_text_length"));
            assertTrue(columnExists(connection, "human_task", "response_max_key_length"));
            assertTrue(columnExists(connection, "human_task", "write_attempts"));
            assertTrue(columnExists(connection, "human_task", "confirmation_version"));
            assertTrue(columnExists(connection, "human_task", "confirmation_prompt"));
            assertTrue(columnExists(connection, "human_task", "confirmation_comment_requirement"));
            assertTrue(columnExists(connection, "human_task", "confirmation_actions"));
            assertTrue(columnExists(connection, "human_task", "confirmation_resolve_label"));
            assertTrue(columnExists(connection, "human_task", "confirmation_deny_label"));
            assertTrue(columnExists(connection, "human_task", "confirmation_cancel_label"));
            assertTrue(columnExists(connection, "human_task", "confirmation_max_prompt_bytes"));
            assertTrue(columnExists(connection, "human_task", "confirmation_max_action_label_bytes"));
            assertTrue(columnExists(connection, "human_task", "confirmation_max_comment_bytes"));
            assertTrue(columnExists(connection, "human_task", "decision_comment"));
            assertTrue(columnExists(connection, "human_task", "created_at_epoch_second"));
            assertTrue(columnExists(connection, "human_task", "created_at_nano"));
            assertEquals(1, indexCount(connection, "human_task_live_correlation"));
            assertEquals(1, indexCount(connection, "human_task_process_attention"));
            assertEquals(1, indexCount(connection, "human_task_context_attention"));
            assertEquals(1, historyRows(connection, humanTaskVersion));
            assertEquals(1, historyRows(connection, attentionVersion));
            String attentionPlan = attentionQueryPlan(connection);
            assertTrue(attentionPlan.contains("human_task_context_attention"), attentionPlan);
            assertEquals(SqliteSchema.currentVersion(), SqliteSchema.migrate(connection, CLOCK));
            assertEquals(1, indexCount(connection, "human_task_live_correlation"));
            assertEquals(1, indexCount(connection, "human_task_process_attention"));
            assertEquals(1, indexCount(connection, "human_task_context_attention"));
            assertEquals(1, historyRows(connection, attentionVersion));
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

    private static String attentionQueryPlan(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement(
                "EXPLAIN QUERY PLAN SELECT t.task_id FROM human_task t "
                        + "JOIN process_instance p ON p.tenant_id = t.tenant_id "
                        + "AND p.process_instance_id = t.process_instance_id "
                        + "AND p.graph_version_pin = t.graph_version_pin "
                        + "WHERE t.tenant_id = ? AND t.graph_version_pin = ? "
                        + "AND t.status IN ('WAITING', 'ESCALATED') "
                        + "AND t.confirmation_version > 0 "
                        + "ORDER BY t.created_at_epoch_second, t.created_at_nano, t.task_id")) {
            statement.setString(1, "tenant");
            statement.setString(2, "graph");
            try (ResultSet rows = statement.executeQuery()) {
                var plan = new StringBuilder();
                while (rows.next()) plan.append(rows.getString("detail")).append('\n');
                return plan.toString();
            }
        }
    }

    private static boolean columnExists(Connection connection, String table, String column)
            throws Exception {
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                if (column.equals(rows.getString("name"))) return true;
            }
            return false;
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
