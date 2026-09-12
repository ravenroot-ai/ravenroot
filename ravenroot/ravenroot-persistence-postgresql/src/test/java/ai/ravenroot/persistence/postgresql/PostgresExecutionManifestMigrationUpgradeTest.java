package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifest;
import ai.ravenroot.api.persistence.ExecutionManifestReferences;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.ResolvedRuntimeProfile;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compatibility checks for rows written before operational policy became manifest format 2. */
class PostgresExecutionManifestMigrationUpgradeTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void aFixedVersionOneManifestSurvivesTheOperationalPolicySchemaUpgrade() throws Exception {
        String storeId = "manifest-v1-to-v2-" + UUID.randomUUID();
        DataSource dataSource = PostgresTestDatabase.dataSourceFor(storeId);
        List<SchemaMigration> beforePolicyV2 = PostgresSchema.migrations().stream()
                .filter(migration -> migration.version() < policyV2MigrationVersion()).toList();
        var key = new ExecutionKey("acme",
                UUID.fromString("aaaaaaaa-0000-0000-0000-000000000316"));
        ExecutionManifest legacy = legacyManifest(key);
        assertEquals("a1092d3b81ed16f38cab383c9e7e3aa0900ac1549732319cacb8f643f597afbf",
                legacy.digest().value());

        try (Connection connection = dataSource.getConnection()) {
            assertEquals(policyV2MigrationVersion() - 1,
                    SchemaRunner.migrate(connection, beforePolicyV2, CLOCK));
            assertFalse(columnExists(connection, "execution_manifest", "operational_policy"));
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO execution_manifest (
                        tenant_id, process_instance_id, format_version, digest, graph_content_id,
                        graph_id, version_id, graph_schema_version, definition_format_version,
                        execution_policy, unknown_behavior_mode, engine_digest, store_digest,
                        limits_digest, program_runtime_digest, pinned_at_epoch_second, pinned_at_nano,
                        committed_at_epoch_second, committed_at_nano)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, key.tenantId());
                statement.setObject(2, key.processInstanceId());
                statement.setInt(3, legacy.formatVersion());
                statement.setString(4, legacy.digest().value());
                statement.setString(5, legacy.graphContentId().value());
                statement.setString(6, legacy.graphIdentity().graphId());
                statement.setString(7, legacy.graphIdentity().versionId());
                statement.setInt(8, legacy.runtime().graphSchemaVersion());
                statement.setInt(9, legacy.runtime().definitionFormatVersion());
                statement.setString(10, legacy.runtime().executionPolicy());
                statement.setString(11, legacy.runtime().unknownBehaviorMode());
                statement.setString(12, legacy.runtime().engineDigest());
                statement.setString(13, legacy.runtime().storeDigest());
                statement.setString(14, legacy.runtime().executionLimitsDigest());
                statement.setString(15, legacy.runtime().programRuntimeDigest());
                statement.setLong(16, legacy.pinnedAt().getEpochSecond());
                statement.setInt(17, legacy.pinnedAt().getNano());
                statement.setLong(18, CLOCK.instant().getEpochSecond());
                statement.setInt(19, CLOCK.instant().getNano());
                statement.executeUpdate();
            }
        }

        try (var store = new PostgresExecutionManifestStore(
                dataSource, CLOCK, ExecutionManifestReferences.NONE)) {
            var loaded = store.load(key).toCompletableFuture().join().manifest();
            assertEquals(legacy, loaded);
            assertNull(loaded.operationalPolicy());
        }
        try (var reopened = new PostgresExecutionManifestStore(
                PostgresTestDatabase.dataSourceFor(storeId), CLOCK, ExecutionManifestReferences.NONE)) {
            var loaded = reopened.load(key).toCompletableFuture().join().manifest();
            assertEquals(legacy, loaded);
            assertNull(loaded.operationalPolicy());
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT operational_policy FROM execution_manifest WHERE tenant_id = ? "
                             + "AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            statement.setObject(2, key.processInstanceId());
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertNull(row.getString(1), "migration must preserve absence rather than backfill defaults");
            }
        }
    }

    private static ExecutionManifest legacyManifest(ExecutionKey key) {
        var profile = new ResolvedRuntimeProfile(1, 1, "STANDARD", "pass-through",
                "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64));
        return new ExecutionManifest(ExecutionManifest.FORMAT_VERSION_1, key,
                new GraphContentId("a".repeat(64)),
                new GraphDefinitionIdentity(
                        GraphDefinitionIdentity.SUBMISSION_GRAPH_ID, "a".repeat(64)),
                profile, List.of(), CLOCK.instant());
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

    private static int policyV2MigrationVersion() {
        return PostgresSchema.migrations().stream()
                .filter(migration -> migration.statements().stream()
                        .anyMatch(statement -> statement.contains("ADD COLUMN operational_policy")))
                .map(SchemaMigration::version).findFirst().orElseThrow();
    }
}
