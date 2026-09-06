package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.GraphVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upgrade an existing deployment actually performs: a database already carrying executions taken
 * forward by the deployment-registry migration (schema version 22) that lands behind them.
 *
 * <p>Modeled directly on {@link SqliteExecutionManifestMigrationUpgradeTest}, whose two load-bearing
 * properties apply unchanged here: the step must apply exactly once and leave every earlier table
 * untouched, and the upgraded file must be usable rather than merely well-shaped. This class adds a
 * third property that migration 22 states as a hard requirement in {@link SqliteSchema}: the four new
 * tables are purely additive, so every pre-existing row in a table that predates this migration must
 * be byte-identical after it runs, not merely present.</p>
 */
class SqliteDeploymentMigrationUpgradeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void aDatabaseLeftAtTheVersionBeforeTheRegistryUpgradesByApplyingOnlyThatStep(@TempDir Path directory)
            throws Exception {
        Path databaseFile = directory.resolve("upgrade.db");
        List<SchemaMigration> beforeRegistry = SqliteSchema.migrations().stream()
                .filter(migration -> migration.version() < registryMigrationVersion())
                .toList();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            assertEquals(registryMigrationVersion() - 1, SqliteSchema.migrate(connection, beforeRegistry, CLOCK),
                    "the file starts one version below the registry step");
            assertFalse(tableExists(connection, "deployment"),
                    "no deployment table exists yet, which is what makes the next step a real upgrade");
            assertTrue(tableExists(connection, "process_instance"),
                    "and the execution-store tables are present, so this is a populated deployment's file");
            assertTrue(tableExists(connection, "execution_manifest"));
        }

        // Populate execution-store rows before the upgrade, so the upgrade has real pre-existing state
        // to leave alone rather than an empty file that would upgrade trivially.
        insertExecutionInstance(databaseFile, "acme", "11111111-0000-0000-0000-000000000001");
        Object[] beforeRow = readInstanceRow(databaseFile, "acme", "11111111-0000-0000-0000-000000000001");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            assertEquals(SqliteSchema.currentVersion(), SqliteSchema.migrate(connection, CLOCK));
            assertTrue(tableExists(connection, "deployment"));
            assertTrue(tableExists(connection, "deployment_version"));
            assertTrue(tableExists(connection, "deployment_command"));
            assertTrue(tableExists(connection, "deployment_lease"));
            assertEquals(1, historyRowsFor(connection, registryMigrationVersion()),
                    "the registry step is recorded once in the schema history an operator reads");
            assertTrue(tableExists(connection, "process_instance"), "every earlier table survives");
            assertTrue(tableExists(connection, "execution_manifest"));
        }

        // The load-bearing assertion migration 22 promises in SqliteSchema: nothing about the
        // pre-existing row changed, not even incidentally.
        Object[] afterRow = readInstanceRow(databaseFile, "acme", "11111111-0000-0000-0000-000000000001");
        assertEquals(List.of(beforeRow), List.of(afterRow),
                "a purely additive migration must leave every pre-existing row byte-identical");

        // Re-running is a no-op, which is what an ordinary restart does.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            assertEquals(SqliteSchema.currentVersion(), SqliteSchema.migrate(connection, CLOCK));
            assertEquals(1, historyRowsFor(connection, registryMigrationVersion()));
        }
    }

    @Test
    void anUpgradedDatabaseServesTheRegistryOverTheSameFile(@TempDir Path directory) throws Exception {
        Path databaseFile = directory.resolve("upgraded-then-used.db");
        List<SchemaMigration> beforeRegistry = SqliteSchema.migrations().stream()
                .filter(migration -> migration.version() < registryMigrationVersion())
                .toList();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            SqliteSchema.migrate(connection, beforeRegistry, CLOCK);
        }
        insertExecutionInstance(databaseFile, "acme", "22222222-0000-0000-0000-000000000002");

        DeploymentId deploymentId;
        try (DeploymentRegistry registry = new SqliteDeploymentRegistry(databaseFile, CLOCK,
                tenant -> DeploymentId.of("dep-1"))) {
            byte[] bytes = "graph".getBytes(StandardCharsets.UTF_8);
            var content = new GraphVersion.Content(1, bytes, "alice", CLOCK.instant());
            var create = new DeploymentRegistry.CreateCommand("acme", "create", "a".repeat(64));
            DeploymentRegistry.Record made = registry.create(content, create).toCompletableFuture().join();
            deploymentId = made.deploymentId();
            assertEquals("graph", new String(registry.version("acme", deploymentId, 1)
                    .toCompletableFuture().join().orElseThrow().canonicalSnapshot(), StandardCharsets.UTF_8));
        }

        // A separate open, so the assertion is about the file rather than about one instance's memory,
        // and the pre-existing execution row is still exactly where it was.
        try (DeploymentRegistry registry = new SqliteDeploymentRegistry(databaseFile, CLOCK,
                tenant -> DeploymentId.of("dep-2"))) {
            assertTrue(registry.get("acme", deploymentId).toCompletableFuture().join().isPresent());
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM process_instance "
                     + "WHERE tenant_id = 'acme'")) {
            assertTrue(rows.next() && rows.getInt(1) == 1);
        }
    }

    /**
     * The downgrade guard {@link SqliteSchema#migrate} enforces is not specific to the execution-store
     * steps; it is evaluated against {@code PRAGMA user_version} directly, so this proves it still
     * refuses a database stamped past the highest version this binary understands after migration 22
     * has been added to the known set.
     */
    @Test
    void aDatabaseStampedNewerThanThisBuildKnowsIsRefused(@TempDir Path directory) throws Exception {
        Path databaseFile = directory.resolve("from-the-future.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            SqliteSchema.migrate(connection, CLOCK);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = " + (SqliteSchema.currentVersion() + 1));
            }
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> SqliteSchema.migrate(connection, CLOCK));
            assertTrue(refused.getMessage().contains("newer than this build understands"),
                    refused.getMessage());
        }
    }

    private static void insertExecutionInstance(Path databaseFile, String tenant, String instanceId)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO process_instance (tenant_id, process_instance_id, status, "
                    + "graph_version_pin, revision, fencing_token, updated_at_epoch_second, "
                    + "updated_at_nano) VALUES ('" + tenant + "', '" + instanceId + "', 'RUNNING', '"
                    + "a".repeat(64) + "', 1, 1, 1000, 0)");
        }
    }

    private static Object[] readInstanceRow(Path databaseFile, String tenant, String instanceId)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             PreparedStatementHolder holder = new PreparedStatementHolder(connection,
                     "SELECT status, graph_version_pin, revision, fencing_token, updated_at_epoch_second, "
                             + "updated_at_nano FROM process_instance WHERE tenant_id = ? AND "
                             + "process_instance_id = ?")) {
            holder.statement.setString(1, tenant);
            holder.statement.setString(2, instanceId);
            try (ResultSet rows = holder.statement.executeQuery()) {
                assertTrue(rows.next(), "the pre-existing instance row must still be readable");
                return new Object[] {rows.getString(1), rows.getString(2), rows.getLong(3), rows.getLong(4),
                        rows.getLong(5), rows.getInt(6)};
            }
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

    private static int registryMigrationVersion() {
        return SqliteSchema.migrations().stream()
                .filter(migration -> migration.statements().stream()
                        .anyMatch(statement -> statement.contains("CREATE TABLE deployment (")))
                .map(SchemaMigration::version)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no migration creates the deployment table"));
    }

    /** A tiny closeable pairing so the connection and its prepared statement close together. */
    private static final class PreparedStatementHolder implements AutoCloseable {
        private final Connection connection;
        final java.sql.PreparedStatement statement;

        PreparedStatementHolder(Connection connection, String sql) throws SQLException {
            this.connection = connection;
            this.statement = connection.prepareStatement(sql);
        }

        @Override
        public void close() throws SQLException {
            try {
                statement.close();
            } finally {
                connection.close();
            }
        }
    }
}
