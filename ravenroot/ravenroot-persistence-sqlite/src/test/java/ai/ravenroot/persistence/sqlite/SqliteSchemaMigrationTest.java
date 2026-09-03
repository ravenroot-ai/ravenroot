package ai.ravenroot.persistence.sqlite;

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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The forward-migration runner, exercised against synthetic migrations rather than the real one.
 *
 * <p>Shipping the whole schema as version 1 leaves nothing to migrate <em>from</em>, so a test that
 * only ran {@link SqliteSchema#migrations()} would assert that a table was created and call that
 * migration coverage. It is not: the properties that matter are what the runner does when a database
 * is part-way up, when it is already current, and when it is <em>ahead</em> of the code, and none of
 * those can be observed with a single version. Synthetic migrations make all four reachable, and the
 * fourth — data written under version 1 surviving version 2 — is the one an upgrade actually depends
 * on.</p>
 */
class SqliteSchemaMigrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static final SchemaMigration ADD_TABLE = new SchemaMigration(1, "create widget",
            List.of("CREATE TABLE widget (id TEXT NOT NULL PRIMARY KEY, label TEXT NOT NULL)"));
    private static final SchemaMigration ADD_COLUMN = new SchemaMigration(2, "widget gains a colour",
            List.of("ALTER TABLE widget ADD COLUMN colour TEXT"));
    private static final SchemaMigration ADD_INDEX = new SchemaMigration(3, "index widget labels",
            List.of("CREATE INDEX idx_widget_label ON widget (label)"));

    @TempDir
    Path databaseDirectory;

    @Test
    void theRealSchemaInstallsOnAnEmptyFileAndReportsItsVersion() throws Exception {
        try (Connection connection = open("real.db")) {
            assertEquals(0, SqliteSchema.versionOf(connection), "an empty file is version zero, and "
                    + "says so without needing a table to have been created first");
            assertEquals(SqliteSchema.currentVersion(), SqliteSchema.migrate(connection, CLOCK));
            assertEquals(SqliteSchema.currentVersion(), SqliteSchema.versionOf(connection));
            assertTrue(tableNames(connection).containsAll(List.of("process_instance", "traversal",
                    "invocation", "invocation_parent", "attempt", "timer", "lease", "work_claim",
                    "work_acknowledgement", "idempotency_record", "idempotency_watermark",
                    "inventory_watermark")));
        }
    }

    @Test
    void reRunningTheSameMigrationsChangesNothing() throws Exception {
        try (Connection connection = open("idempotent.db")) {
            SqliteSchema.migrate(connection, CLOCK);
            List<String> after = tableNames(connection);
            // A runner that re-applied an installed migration would fail here on "table already
            // exists" rather than silently doing extra work, which is why this is worth one line.
            assertEquals(SqliteSchema.currentVersion(), SqliteSchema.migrate(connection, CLOCK));
            assertEquals(after, tableNames(connection));
            assertEquals(SqliteSchema.currentVersion(), historyVersions(connection).size());
        }
    }

    @Test
    void aForwardMigrationResumesFromAPartlyUpgradedFileAndPreservesTheRowsAlreadyWritten()
            throws Exception {
        try (Connection connection = open("forward.db")) {
            assertEquals(1, SqliteSchema.migrate(connection, List.of(ADD_TABLE), CLOCK));
            insertWidget(connection, "w-1", "before the upgrade");

            // The file is now at a real, complete intermediate version, which is exactly the state an
            // upgrade interrupted between two steps leaves behind. Resuming must apply only what is
            // missing.
            assertEquals(3, SqliteSchema.migrate(connection, List.of(ADD_TABLE, ADD_COLUMN, ADD_INDEX),
                    CLOCK));
            assertEquals(3, SqliteSchema.versionOf(connection));
            assertEquals(List.of(1, 2, 3), historyVersions(connection));

            assertEquals("before the upgrade", labelOf(connection, "w-1"),
                    "rows written under the old schema must survive the upgrade; a migration that "
                            + "recreated the table instead of altering it would pass every structural "
                            + "assertion and lose every row");
            assertTrue(columnNames(connection, "widget").contains("colour"));
        }
    }

    @Test
    void aDatabaseNewerThanTheCodeIsRefusedRatherThanOpened() throws Exception {
        try (Connection connection = open("newer.db")) {
            SqliteSchema.migrate(connection, List.of(ADD_TABLE, ADD_COLUMN, ADD_INDEX), CLOCK);

            // The same file, now handed to a build that only knows about version 1 -- an older binary
            // rolled back onto a database an upgrade already touched.
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> SqliteSchema.migrate(connection, List.of(ADD_TABLE), CLOCK));
            assertTrue(refused.getMessage().contains("newer than this build understands"), refused.getMessage());
            assertEquals(3, SqliteSchema.versionOf(connection), "a refused open must not rewrite the version");
        }
    }

    @Test
    void migrationsMustBeNumberedWithoutGapsOrDuplicates() throws Exception {
        try (Connection connection = open("numbering.db")) {
            // A gap means some database somewhere is at the missing version and no step describes how
            // to leave it. A duplicate means two different structures claim the same name. Both are
            // authoring mistakes that only surface on somebody else's file, so they are rejected here.
            assertThrows(IllegalStateException.class,
                    () -> SqliteSchema.migrate(connection, List.of(ADD_TABLE, ADD_INDEX), CLOCK));
            assertThrows(IllegalStateException.class, () -> SqliteSchema.migrate(connection,
                    List.of(ADD_TABLE, ADD_COLUMN, new SchemaMigration(2, "clash", List.of("SELECT 1"))),
                    CLOCK));
        }
    }

    @Test
    void aFailingMigrationLeavesTheVersionAndTheStructureUntouched() throws Exception {
        try (Connection connection = open("atomic.db")) {
            var broken = new SchemaMigration(2, "half of this cannot run",
                    List.of("CREATE TABLE gadget (id TEXT NOT NULL PRIMARY KEY)",
                            "CREATE TABLE gadget (id TEXT NOT NULL PRIMARY KEY)"));

            SqliteSchema.migrate(connection, List.of(ADD_TABLE), CLOCK);
            assertThrows(SQLException.class,
                    () -> SqliteSchema.migrate(connection, List.of(ADD_TABLE, broken), CLOCK));

            assertEquals(1, SqliteSchema.versionOf(connection),
                    "the version is written inside the migration's own transaction, so a failure "
                            + "cannot leave the file labelled with a structure it does not have");
            assertTrue(!tableNames(connection).contains("gadget"),
                    "the first statement of the failed migration must have rolled back with it");
            assertEquals(List.of(1), historyVersions(connection));
        }
    }

    /**
     * The one migration in this schema that rewrites rows, and the caveat it leaves behind.
     *
     * <p>Everything else in version 5 is additive, so the interesting question is not whether the
     * columns appear — it is whether a row written under version 4 comes out of the upgrade with a
     * usable creation instant, because {@code created_at} is half of the inventory's sort key and a
     * row left at the DEFAULT would collapse onto epoch zero. The backfill copies {@code updated_at},
     * which for an old row is its <em>last write</em> and not its creation; that is a truthful upper
     * bound rather than a fabrication, and this test pins it so the caveat cannot quietly become a
     * claim of accuracy it never had.</p>
     */
    @Test
    void migrationFiveBackfillsCreatedAtFromUpdatedAtAndLeavesTheGenerationAtItsFloor() throws Exception {
        List<SchemaMigration> throughFour = SqliteSchema.migrations().subList(0, 4);
        try (Connection connection = open("inventory-upgrade.db")) {
            assertEquals(4, SqliteSchema.migrate(connection, throughFour, CLOCK));
            insertLegacyInstance(connection, "acme", "11111111-1111-1111-1111-111111111111", 1700, 250);

            assertEquals(5, SqliteSchema.migrate(connection, CLOCK));

            assertTrue(columnNames(connection, "process_instance").containsAll(List.of(
                    "created_at_epoch_second", "created_at_nano", "lifecycle_generation",
                    "deployment_id", "workload_id", "correlation_id",
                    "retained_until_epoch_second", "retained_until_nano")));
            assertEquals(List.of(1700L, 250L, 1L),
                    legacyInstanceRow(connection, "11111111-1111-1111-1111-111111111111"),
                    "created_at is backfilled from updated_at -- the last write, not the true "
                            + "creation -- and lifecycle_generation is a floor of one rather than a "
                            + "count nobody recorded");
            assertNull(retainedUntilOf(connection, "11111111-1111-1111-1111-111111111111"),
                    "a migration cannot know the configured retention, so it schedules no deletion; "
                            + "the store resolves a terminal row with no deadline against updated_at");
            assertTrue(tableNames(connection).contains("inventory_watermark"));
            assertTrue(indexNames(connection).containsAll(List.of("idx_process_instance_inventory",
                    "idx_process_instance_status", "idx_process_instance_deployment",
                    "idx_lease_worker")));
        }
    }

    private static void insertLegacyInstance(Connection connection, String tenant, String id,
                                             long second, int nano) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO process_instance (tenant_id, "
                + "process_instance_id, status, graph_version_pin, revision, fencing_token, "
                + "updated_at_epoch_second, updated_at_nano) VALUES (?, ?, 'RUNNING', 'graph-v1', 3, 0, ?, ?)")) {
            statement.setString(1, tenant);
            statement.setString(2, id);
            statement.setLong(3, second);
            statement.setInt(4, nano);
            statement.executeUpdate();
        }
    }

    private static List<Long> legacyInstanceRow(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT created_at_epoch_second, "
                + "created_at_nano, lifecycle_generation FROM process_instance "
                + "WHERE process_instance_id = ?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return List.of(rows.getLong(1), rows.getLong(2), rows.getLong(3));
            }
        }
    }

    private static Long retainedUntilOf(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT retained_until_epoch_second "
                + "FROM process_instance WHERE process_instance_id = ?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                long value = rows.getLong(1);
                return rows.wasNull() ? null : value;
            }
        }
    }

    private static List<String> indexNames(Connection connection) throws SQLException {
        var names = new ArrayList<String>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'index' AND name IS NOT NULL "
                             + "ORDER BY name")) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }

    private Connection open(String name) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databaseDirectory.resolve(name));
    }

    private static void insertWidget(Connection connection, String id, String label) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO widget (id, label) VALUES (?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, label);
            statement.executeUpdate();
        }
    }

    private static String labelOf(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT label FROM widget WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static List<String> tableNames(Connection connection) throws SQLException {
        var names = new ArrayList<String>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }

    private static List<String> columnNames(Connection connection, String table) throws SQLException {
        var names = new ArrayList<String>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                names.add(rows.getString("name"));
            }
        }
        return names;
    }

    private static List<Integer> historyVersions(Connection connection) throws SQLException {
        var versions = new ArrayList<Integer>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT version FROM store_schema_history ORDER BY version")) {
            while (rows.next()) {
                versions.add(rows.getInt(1));
            }
        }
        return versions;
    }
}
