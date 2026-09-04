package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifest;
import ai.ravenroot.api.persistence.ExecutionManifestReferences;
import ai.ravenroot.api.persistence.ExecutionManifestStoreException;
import ai.ravenroot.api.persistence.ExecutionManifestStoreFailure;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.PinnedNodePackage;
import ai.ravenroot.api.persistence.ResolvedRuntimeProfile;
import ai.ravenroot.api.persistence.StoredExecutionManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upgrade an existing deployment actually performs: a database already carrying executions,
 * definitions and holds, taken forward by the manifest migration that lands behind them.
 *
 * <p>Two properties matter and neither fails loudly if it is wrong. The step must apply exactly once
 * and leave every earlier table in place, because {@code CREATE TABLE} without {@code IF NOT EXISTS}
 * aborts an upgrade and the symptom is a database that refuses to open at start-up. And the upgraded
 * file must be usable rather than merely well-shaped, which is what the second and third tests
 * assert by pinning, reading back and refusing a real manifest through the adapter.</p>
 *
 * <p>The third test is the compatibility half of the migration story stated as an assertion: an
 * execution that was already durable before this migration ran has <em>no</em> manifest afterwards,
 * and reading one reports absence rather than inventing a manifest from the environment the upgraded
 * process happens to compose. Backfilling would produce exactly the substitution the manifest exists
 * to prevent, so it is not done, and this test is where that decision is pinned rather than left as
 * prose.</p>
 */
class SqliteExecutionManifestMigrationUpgradeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void aDatabaseLeftAtTheVersionBeforeManifestsUpgradesByApplyingOnlyTheManifestStep(
            @TempDir Path directory) throws Exception {
        Path databaseFile = directory.resolve("upgrade.db");
        List<SchemaMigration> beforeManifests = SqliteSchema.migrations().stream()
                .filter(migration -> migration.version() < manifestMigrationVersion())
                .toList();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            assertEquals(manifestMigrationVersion() - 1,
                    SqliteSchema.migrate(connection, beforeManifests, CLOCK),
                    "the file starts one version below the manifest step");
            assertFalse(tableExists(connection, "execution_manifest"),
                    "no manifest table exists yet, which is what makes the next step a real upgrade");
            assertTrue(tableExists(connection, "graph_definition"),
                    "and the earlier features' tables are present, so this is a populated deployment's "
                            + "file and not an empty one that would upgrade trivially");
            assertTrue(tableExists(connection, "execution_pause"));
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            assertEquals(SqliteSchema.currentVersion(), SqliteSchema.migrate(connection, CLOCK));
            assertTrue(tableExists(connection, "execution_manifest"));
            assertTrue(tableExists(connection, "execution_manifest_package"));
            assertEquals(1, historyRowsFor(connection, manifestMigrationVersion()),
                    "the manifest step is recorded once in the schema history an operator reads");
            assertTrue(tableExists(connection, "graph_definition"),
                    "every earlier table survives the step that follows it");
            assertTrue(tableExists(connection, "process_instance"));
        }

        // Re-running is a no-op, which is what an ordinary restart does.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            assertEquals(SqliteSchema.currentVersion(), SqliteSchema.migrate(connection, CLOCK));
            assertEquals(1, historyRowsFor(connection, manifestMigrationVersion()));
        }
    }

    @Test
    void anUpgradedDatabasePinsReadsAndRefusesAManifest(@TempDir Path directory) throws Exception {
        Path databaseFile = directory.resolve("upgraded-then-used.db");
        List<SchemaMigration> beforeManifests = SqliteSchema.migrations().stream()
                .filter(migration -> migration.version() < manifestMigrationVersion())
                .toList();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            SqliteSchema.migrate(connection, beforeManifests, CLOCK);
        }

        var key = new ExecutionKey("acme", UUID.randomUUID());
        ExecutionManifest manifest = manifest(key, "STANDARD",
                List.of(new PinnedNodePackage("alpha.nodes", "1.4.2", "node-sdk-1")));
        try (var store = new SqliteExecutionManifestStore(
                databaseFile, CLOCK, ExecutionManifestReferences.NONE)) {
            StoredExecutionManifest pinned = store.pin(manifest).toCompletableFuture().join();
            assertEquals(manifest, pinned.manifest());

            ExecutionManifestStoreException conflict = assertThrows(
                    ExecutionManifestStoreException.class,
                    () -> unwrap(() -> store.pin(manifest(key, "TEST_PASSTHROUGH", List.of()))));
            assertInstanceOf(ExecutionManifestStoreFailure.ManifestConflict.class, conflict.failure());
        }

        // A separate open, so the assertion is about the file rather than about one instance's memory.
        try (var store = new SqliteExecutionManifestStore(
                databaseFile, CLOCK, ExecutionManifestReferences.NONE)) {
            assertEquals(manifest, store.load(key).toCompletableFuture().join().manifest());
        }
    }

    @Test
    void anExecutionThatPredatesTheMigrationHasNoManifestAndIsNotBackfilled(@TempDir Path directory)
            throws Exception {
        Path databaseFile = directory.resolve("pre-existing.db");
        List<SchemaMigration> beforeManifests = SqliteSchema.migrations().stream()
                .filter(migration -> migration.version() < manifestMigrationVersion())
                .toList();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            SqliteSchema.migrate(connection, beforeManifests, CLOCK);
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO process_instance (tenant_id, process_instance_id, status, "
                        + "graph_version_pin, revision, fencing_token, updated_at_epoch_second, "
                        + "updated_at_nano) VALUES ('acme', 'aaaaaaaa-0000-0000-0000-000000000001', "
                        + "'RUNNING', '" + "a".repeat(64) + "', 1, 1, 0, 0)");
            }
        }

        var key = new ExecutionKey("acme",
                UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"));
        try (var store = new SqliteExecutionManifestStore(
                databaseFile, CLOCK, ExecutionManifestReferences.NONE)) {
            ExecutionManifestStoreException absent = assertThrows(ExecutionManifestStoreException.class,
                    () -> unwrap(() -> store.load(key)));
            assertInstanceOf(ExecutionManifestStoreFailure.NotFound.class, absent.failure(),
                    "an execution accepted before manifests existed has none, and the upgrade must "
                            + "not invent one from the environment this process happens to compose");
        }
    }

    @Test
    void aManifestMayNotBeRemovedWhileItsInstanceStillExists(@TempDir Path directory) {
        Path databaseFile = directory.resolve("retention.db");
        var key = new ExecutionKey("acme", UUID.randomUUID());
        try (var store = new SqliteExecutionManifestStore(
                databaseFile, CLOCK, ExecutionManifestReferences.NONE)) {
            store.pin(manifest(key, "STANDARD", List.of())).toCompletableFuture().join();
            // Removable while the acceptance has not landed: this is the unreferenced row the
            // commit ordering deliberately allows.
            store.remove(key).toCompletableFuture().join();

            store.pin(manifest(key, "STANDARD", List.of())).toCompletableFuture().join();
            insertInstance(databaseFile, key);
            ExecutionManifestStoreException refused = assertThrows(ExecutionManifestStoreException.class,
                    () -> unwrap(() -> store.remove(key)));
            assertInstanceOf(ExecutionManifestStoreFailure.StillReferenced.class, refused.failure(),
                    "retention must not remove a manifest an existing instance still needs");
            assertTrue(store.contains(key).toCompletableFuture().join());
        }
    }

    private static void insertInstance(Path databaseFile, ExecutionKey key) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO process_instance (tenant_id, process_instance_id, status, "
                    + "graph_version_pin, revision, fencing_token, updated_at_epoch_second, "
                    + "updated_at_nano) VALUES ('" + key.tenantId() + "', '" + key.processInstanceId()
                    + "', 'RUNNING', '" + "a".repeat(64) + "', 1, 1, 0, 0)");
        } catch (SQLException failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static ExecutionManifest manifest(ExecutionKey key, String policy,
                                              List<PinnedNodePackage> packages) {
        var profile = new ResolvedRuntimeProfile(1, 1, policy, "pass-through",
                "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64));
        return new ExecutionManifest(ExecutionManifest.CURRENT_FORMAT_VERSION, key,
                new GraphContentId("a".repeat(64)),
                new GraphDefinitionIdentity(GraphDefinitionIdentity.SUBMISSION_GRAPH_ID, "a".repeat(64)),
                profile, packages, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static int manifestMigrationVersion() {
        return SqliteSchema.migrations().stream()
                .filter(migration -> migration.statements().stream()
                        .anyMatch(statement -> statement.contains("CREATE TABLE execution_manifest")))
                .map(SchemaMigration::version)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no migration creates the manifest table"));
    }

    private static void unwrap(java.util.function.Supplier<java.util.concurrent.CompletionStage<?>> call) {
        try {
            call.get().toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException wrapped) {
            ExecutionManifestStoreException failure = ExecutionManifestStoreException.unwrap(wrapped);
            throw failure == null ? wrapped : failure;
        }
    }

    private static boolean tableExists(Connection connection, String name) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) == 1;
            }
        }
    }

    private static int historyRowsFor(Connection connection, int version) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM store_schema_history WHERE version = " + version)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }
}
