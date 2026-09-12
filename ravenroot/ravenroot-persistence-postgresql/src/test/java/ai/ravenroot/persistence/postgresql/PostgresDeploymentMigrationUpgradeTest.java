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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * additive, every pre-existing row of every table that predates it is unchanged afterwards — not
 * merely still present.</p>
 *
 * <p>That third property is asserted as broadly as it is stated. {@link #contentOfEveryTable} reads
 * every table the schema holds before the step and every row in each of them, so a table left empty by
 * the fixture is still covered: a step that inserted into one, or that rewrote a column across a table
 * nobody seeded, would change the snapshot. Comparing one hand-listed row of one table would have left
 * the sentence above true only of the row somebody remembered to name.</p>
 */
class PostgresDeploymentMigrationUpgradeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static final UUID INSTANCE = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID TRAVERSAL = UUID.fromString("11111111-0000-0000-0000-000000000002");
    private static final UUID INVOCATION = UUID.fromString("11111111-0000-0000-0000-000000000003");
    private static final String CONTENT_ID = "c".repeat(64);

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
            // empty database that would upgrade trivially. Five tables across two unrelated
            // aggregates and three levels of foreign key, because a step that damaged only a child
            // row or only one aggregate would pass against a single parent row.
            insertExecutionInstance(connection);
            insertGraphDefinition(connection);
            Map<String, List<String>> before = contentOfEveryTable(connection);
            assertTrue(before.size() > 20,
                    "the snapshot must actually cover the schema; a lookup that found no tables would "
                            + "compare two empty maps and pass no matter what the migration did");

            assertEquals(PostgresSchema.currentVersion(), PostgresSchema.migrate(connection, CLOCK));
            assertTrue(tableExists(connection, "deployment"));
            assertTrue(tableExists(connection, "deployment_version"));
            assertTrue(tableExists(connection, "deployment_command"));
            assertTrue(tableExists(connection, "deployment_lease"));
            assertEquals(1, historyRowsFor(connection, registryMigrationVersion()),
                    "the registry step is recorded once in the history an operator reads");
            assertTrue(tableExists(connection, "process_instance"), "every earlier table survives");
            assertTrue(tableExists(connection, "execution_manifest"));

            Map<String, List<String>> after = contentOfEveryTable(connection);
            assertTrue(after.keySet().containsAll(before.keySet()),
                    "an additive step drops no table it found, whatever else it adds");
            for (Map.Entry<String, List<String>> table : before.entrySet()) {
                assertEquals(table.getValue(), after.get(table.getKey()),
                        "a purely additive migration must leave every pre-existing row of every "
                                + "pre-existing table unchanged, and " + table.getKey() + " changed");
            }

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

    /** A traversal and an invocation under the instance, plus an unrelated definition and its binding. */
    private static void insertGraphDefinition(Connection connection) throws SQLException {
        try (PreparedStatement traversal = connection.prepareStatement(
                "INSERT INTO traversal (tenant_id, process_instance_id, traversal_id, position, "
                        + "ingress_node_id, status) VALUES ('acme', ?, ?, 0, 'start', 'RUNNING')");
             PreparedStatement invocation = connection.prepareStatement(
                     "INSERT INTO invocation (tenant_id, process_instance_id, traversal_id, "
                             + "invocation_id, position, node_id, status, node_command) "
                             + "VALUES ('acme', ?, ?, ?, 0, 'work', 'RUNNING', 'PROCESS')");
             PreparedStatement definition = connection.prepareStatement(
                     "INSERT INTO graph_definition (tenant_id, content_id, format_version, "
                             + "definition_bytes, digest, byte_length, first_graph_id, first_version_id, "
                             + "stored_at_epoch_second, stored_at_nano) "
                             + "VALUES ('acme', ?, 1, ?, ?, 5, 'graph', 'v1', 1000, 0)");
             PreparedStatement binding = connection.prepareStatement(
                     "INSERT INTO graph_definition_binding (tenant_id, graph_id, version_id, content_id, "
                             + "bound_at_epoch_second, bound_at_nano) VALUES ('acme', 'graph', 'v1', ?, "
                             + "1000, 0)")) {
            traversal.setObject(1, INSTANCE);
            traversal.setObject(2, TRAVERSAL);
            traversal.executeUpdate();
            invocation.setObject(1, INSTANCE);
            invocation.setObject(2, TRAVERSAL);
            invocation.setObject(3, INVOCATION);
            invocation.executeUpdate();
            definition.setString(1, CONTENT_ID);
            definition.setBytes(2, "graph".getBytes(StandardCharsets.UTF_8));
            definition.setBytes(3, new byte[32]);
            definition.executeUpdate();
            binding.setString(1, CONTENT_ID);
            binding.executeUpdate();
        }
    }

    /**
     * Every table in the schema, mapped to every row it holds rendered as canonical JSON text.
     *
     * <p>{@code to_jsonb} rather than a column list, because the point of the assertion is to cover
     * columns nobody enumerated — including one a future migration adds to a predating table, which is
     * exactly the change that would make the step non-additive without any test noticing. Rows are
     * sorted rather than left in the server's order: these tables carry no row identity the planner is
     * obliged to respect, so an unsorted comparison would be asserting something the schema does not
     * promise and would fail for a reason that is not a defect.</p>
     *
     * <p>{@code store_schema_version} and {@code store_schema_history} are excluded. They are the
     * migration's own bookkeeping and are <em>supposed</em> to change when a step is applied; including
     * them would make the comparison fail on every run and there would then be nothing left to assert.
     * That both are written exactly once is checked separately, by {@link #historyRowsFor}.</p>
     */
    private static Map<String, List<String>> contentOfEveryTable(Connection connection)
            throws SQLException {
        var names = new ArrayList<String>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() "
                        + "AND table_type = 'BASE TABLE' AND table_name NOT IN "
                        + "('store_schema_version', 'store_schema_history') ORDER BY table_name");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        var content = new LinkedHashMap<String, List<String>>();
        for (String name : names) {
            // Quoted, and the name came from the server's own catalogue rather than from any caller,
            // so nothing reaches this string that the database did not just report as a table it owns.
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT to_jsonb(t)::text FROM \"" + name + "\" t")) {
                var values = new ArrayList<String>();
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
                values.sort(Comparator.naturalOrder());
                content.put(name, List.copyOf(values));
            }
        }
        return content;
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
