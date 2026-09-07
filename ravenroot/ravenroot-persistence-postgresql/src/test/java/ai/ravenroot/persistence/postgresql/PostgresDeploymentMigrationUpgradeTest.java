package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.GraphVersion;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upgrade an existing deployment actually performs: a database already carrying executions, taken
 * forward by the deployment-registry step that lands behind them.
 *
 * <p>This is the first upgrade path this adapter has ever had. Everything before it shipped as
 * migration 1, so every database it had produced was created at the current version and no test could
 * distinguish "the schema is right" from "the schema was built in one go". A database standing at
 * version 1 is now an ordinary state to be found in — every deployment running the previous build is
 * in it — so the step is exercised against one rather than assumed to work.</p>
 *
 * <p>Three properties, and the third is the one migration 2 states as a requirement rather than an
 * expectation: the step applies exactly once and is recorded once; the upgraded database is
 * <em>usable</em> by the registry rather than merely well-shaped; and because the step is purely
 * additive, every pre-existing row in a table that predates it is unchanged afterwards — not merely
 * still present.</p>
 */
class PostgresDeploymentMigrationUpgradeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static final UUID INSTANCE = UUID.fromString("11111111-0000-0000-0000-000000000001");

    @Test
    void aDatabaseAtTheVersionBeforeTheRegistryUpgradesByApplyingOnlyThatStep() throws Exception {
        DataSource dataSource = PostgresTestDatabase.dataSourceFor("deployment-upgrade-" + UUID.randomUUID());
        List<SchemaMigration> beforeRegistry = PostgresSchema.migrations().stream()
                .filter(migration -> migration.version() < registryMigrationVersion())
                .toList();

        try (Connection connection = dataSource.getConnection()) {
            assertEquals(registryMigrationVersion() - 1,
                    SchemaRunner.migrate(connection, beforeRegistry, CLOCK),
                    "the database starts one version below the registry step");
            assertFalse(tableExists(connection, "deployment"),
                    "no deployment table exists yet, which is what makes the next step a real upgrade");
            assertTrue(tableExists(connection, "process_instance"),
                    "and the execution-store tables are present, so this is a populated deployment");
            assertTrue(tableExists(connection, "execution_manifest"));

            // Real pre-existing state, so the upgrade has something to leave alone rather than an
            // empty database that would upgrade trivially.
            insertExecutionInstance(connection);
            List<Object> before = readInstanceRow(connection);

            assertEquals(PostgresSchema.currentVersion(), PostgresSchema.migrate(connection, CLOCK));
            assertTrue(tableExists(connection, "deployment"));
            assertTrue(tableExists(connection, "deployment_version"));
            assertTrue(tableExists(connection, "deployment_command"));
            assertTrue(tableExists(connection, "deployment_lease"));
            assertEquals(1, historyRowsFor(connection, registryMigrationVersion()),
                    "the registry step is recorded once in the history an operator reads");
            assertTrue(tableExists(connection, "process_instance"), "every earlier table survives");
            assertTrue(tableExists(connection, "execution_manifest"));

            assertEquals(before, readInstanceRow(connection),
                    "a purely additive migration must leave every pre-existing row unchanged");

            // Re-running is a no-op, which is what an ordinary restart does.
            assertEquals(PostgresSchema.currentVersion(), PostgresSchema.migrate(connection, CLOCK));
            assertEquals(1, historyRowsFor(connection, registryMigrationVersion()));
        }
    }

    @Test
    void anUpgradedDatabaseServesTheRegistryBesideTheExecutionsItAlreadyHeld() throws Exception {
        String storeId = "deployment-upgrade-then-use-" + UUID.randomUUID();
        DataSource dataSource = PostgresTestDatabase.dataSourceFor(storeId);
        List<SchemaMigration> beforeRegistry = PostgresSchema.migrations().stream()
                .filter(migration -> migration.version() < registryMigrationVersion())
                .toList();
        try (Connection connection = dataSource.getConnection()) {
            SchemaRunner.migrate(connection, beforeRegistry, CLOCK);
            insertExecutionInstance(connection);
        }

        DeploymentId created;
        // Opening the registry is what applies the step here, which is the ordinary path: a deployment
        // upgrades because a process that needs the newer schema started, not because anyone ran a tool.
        try (DeploymentRegistry registry = new PostgresDeploymentRegistry(
                PostgresTestDatabase.dataSourceFor(storeId), CLOCK,
                tenant -> DeploymentId.of("dep-1"))) {
            var content = new GraphVersion.Content(1, "graph".getBytes(StandardCharsets.UTF_8), "alice",
                    CLOCK.instant());
            var create = new DeploymentRegistry.CreateCommand("acme", "create", "a".repeat(64));
            created = registry.create(content, create).toCompletableFuture().join().deploymentId();
            assertEquals("graph", new String(registry.version("acme", created, 1).toCompletableFuture()
                    .join().orElseThrow().canonicalSnapshot(), StandardCharsets.UTF_8));
        }

        // A separate open, so the assertion is about the database rather than one instance's memory.
        try (DeploymentRegistry registry = new PostgresDeploymentRegistry(
                PostgresTestDatabase.dataSourceFor(storeId), CLOCK,
                tenant -> DeploymentId.of("dep-2"))) {
            assertTrue(registry.get("acme", created).toCompletableFuture().join().isPresent());
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM process_instance WHERE tenant_id = 'acme'")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1), "the execution the database already held is untouched");
        }
    }

    /**
     * The downgrade guard is evaluated against {@code store_schema_version} directly rather than
     * against the migration list, so it still refuses a database stamped past the highest version this
     * build understands now that there is more than one step to be past.
     */
    @Test
    void aDatabaseStampedNewerThanThisBuildKnowsIsRefused() throws Exception {
        DataSource dataSource = PostgresTestDatabase.dataSourceFor("deployment-from-the-future-"
                + UUID.randomUUID());
        try (Connection connection = dataSource.getConnection()) {
            PostgresSchema.migrate(connection, CLOCK);
            try (Statement statement = connection.createStatement()) {
                statement.execute("UPDATE store_schema_version SET version = "
                        + (PostgresSchema.currentVersion() + 1) + " WHERE one_row");
            }
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> PostgresSchema.migrate(connection, CLOCK));
            assertTrue(refused.getMessage().contains("newer than this build understands"),
                    refused.getMessage());
        }
    }

    private static void insertExecutionInstance(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO process_instance (tenant_id, process_instance_id, status, "
                        + "graph_version_pin, revision, fencing_token, lifecycle_generation, "
                        + "created_at_epoch_second, created_at_nano, updated_at_epoch_second, "
                        + "updated_at_nano) VALUES ('acme', ?, 'RUNNING', ?, 1, 1, 0, 1000, 0, 1000, 0)")) {
            statement.setObject(1, INSTANCE);
            statement.setString(2, "a".repeat(64));
            statement.executeUpdate();
        }
    }

    private static List<Object> readInstanceRow(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status, graph_version_pin, revision, fencing_token, lifecycle_generation, "
                        + "created_at_epoch_second, created_at_nano, updated_at_epoch_second, "
                        + "updated_at_nano FROM process_instance WHERE tenant_id = 'acme' "
                        + "AND process_instance_id = ?")) {
            statement.setObject(1, INSTANCE);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "the pre-existing instance row must still be readable");
                return List.of(rows.getString(1), rows.getString(2), rows.getLong(3), rows.getLong(4),
                        rows.getLong(5), rows.getLong(6), rows.getInt(7), rows.getLong(8),
                        rows.getInt(9));
            }
        }
    }

    private static boolean tableExists(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = current_schema() "
                        + "AND table_name = ?")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) == 1;
            }
        }
    }

    private static int historyRowsFor(Connection connection, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM store_schema_history WHERE version = ?")) {
            statement.setInt(1, version);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    /** Located by what it creates rather than by a number, so renumbering cannot silently pass. */
    private static int registryMigrationVersion() {
        return PostgresSchema.migrations().stream()
                .filter(migration -> migration.statements().stream()
                        .anyMatch(statement -> statement.contains("CREATE TABLE deployment (")))
                .map(SchemaMigration::version)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no migration creates the deployment table"));
    }
}
