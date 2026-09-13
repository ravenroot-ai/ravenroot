package ai.ravenroot.persistence.postgresql;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Upgrade coverage for Human Task rows created before immutable review presentation. */
class PostgresHumanTaskMigrationUpgradeTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void existingSchemaGainsReviewColumnsExactlyOnceWithSafeLegacyDefaults() throws Exception {
        DataSource dataSource = PostgresTestDatabase.dataSourceFor(
                "human-task-review-upgrade-" + UUID.randomUUID());
        int reviewVersion = reviewMigrationVersion();
        List<SchemaMigration> beforeReview = PostgresSchema.migrations().stream()
                .filter(migration -> migration.version() < reviewVersion).toList();

        try (Connection connection = dataSource.getConnection()) {
            assertEquals(reviewVersion - 1, SchemaRunner.migrate(connection, beforeReview, CLOCK));
            assertFalse(columnExists(connection, "human_task", "review_text"));
            assertEquals(PostgresSchema.currentVersion(), PostgresSchema.migrate(connection, CLOCK));
            for (String column : List.of("review_version", "review_content_type", "review_text",
                    "review_digest", "review_max_utf8_bytes")) {
                assertTrue(columnExists(connection, "human_task", column), column);
            }
            assertEquals(1, historyRows(connection, reviewVersion));
            assertEquals(PostgresSchema.currentVersion(), PostgresSchema.migrate(connection, CLOCK));
            assertEquals(1, historyRows(connection, reviewVersion));
        }
    }

    private static int reviewMigrationVersion() {
        return PostgresSchema.migrations().stream()
                .filter(migration -> migration.statements().stream()
                        .anyMatch(statement -> statement.contains("ADD COLUMN review_text")))
                .map(SchemaMigration::version).findFirst().orElseThrow();
    }

    private static boolean columnExists(Connection connection, String table, String column)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = current_schema() "
                        + "AND table_name = ? AND column_name = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) == 1;
            }
        }
    }

    private static int historyRows(Connection connection, int version) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM store_schema_history WHERE version = ?")) {
            statement.setInt(1, version);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }
}
