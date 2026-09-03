package ai.ravenroot.persistence.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ProcessInventoryQuery;

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
import java.util.Optional;
import java.util.UUID;

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
     * <p>Everything else in the migration is additive, so the interesting question is not whether the
     * columns appear — it is whether a row written under version 4 comes out of the upgrade with a
     * usable creation instant, because {@code created_at} is half of the inventory's sort key and a
     * row left at the DEFAULT would collapse onto epoch zero. The backfill copies {@code updated_at},
     * which for an old row is its <em>last write</em> and not its creation; that is a truthful upper
     * bound rather than a fabrication, and this test pins it so the caveat cannot quietly become a
     * claim of accuracy it never had.</p>
     */
    @Test
    void theInventoryMigrationBackfillsCreatedAtFromUpdatedAtAndLeavesTheGenerationAtItsFloor()
            throws Exception {
        List<SchemaMigration> throughFour = SqliteSchema.migrations().subList(0, 4);
        try (Connection connection = open("inventory-upgrade.db")) {
            assertEquals(4, SqliteSchema.migrate(connection, throughFour, CLOCK));
            insertLegacyInstance(connection, "acme", "11111111-1111-1111-1111-111111111111",
                    "RUNNING", 1700, 250);

            // Straight from 4 to the head, so the row passes through every migration that landed
            // between version 4 and this one -- two of them at the time of writing -- on its way here.
            assertEquals(SqliteSchema.highestKnownVersion(), SqliteSchema.migrate(connection, CLOCK));

            // The inventory migration is identified by what it does, not by the number it happens to
            // hold. That number has already moved twice, each time because another feature merged
            // ahead of it and took it; a literal here would fail on the next such merge for a reason
            // that has nothing to do with what this test is about. What must stay true is that it is
            // the LAST migration -- our columns are added to tables earlier migrations create -- and
            // that the sequence has no gaps, because the downgrade guard compares integers and
            // nothing else, so two structures sharing one version are indistinguishable to it.
            assertEquals(SqliteSchema.highestKnownVersion(), inventoryMigration().version(),
                    "the inventory migration must be the head of the sequence");
            assertEquals(java.util.stream.IntStream.rangeClosed(1, SqliteSchema.highestKnownVersion())
                            .boxed().toList(),
                    SqliteSchema.migrations().stream().map(SchemaMigration::version).sorted().toList(),
                    "versions must be 1..n with no gaps and no duplicates");
            assertEquals(SqliteSchema.highestKnownVersion(), historyVersions(connection).size(),
                    "every migration between 4 and the head records its own history row");

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
            assertTrue(indexNames(connection).contains("idx_process_instance_pin"),
                    "the definition store's own index on process_instance must survive being followed "
                            + "by another migration that alters the same table");
        }
    }

    /**
     * The upgrade path as a caller meets it: migrate a version-4 database, then open a real store on
     * the upgraded file and <em>read</em> it.
     *
     * <p>The structural assertions above prove the columns exist and carry the values the backfill
     * intended. They cannot prove the thing that actually matters, which is that
     * {@code readInventoryRow} makes sense of a migrated row — a row whose {@code created_at} came from
     * a backfill and whose {@code retained_until} is NULL because no migration could know the
     * configured retention. Those are precisely the values no row written through the port ever has, so
     * every other test in this suite reads rows the migration never touched.</p>
     *
     * <p>Both branches of the deadline resolution are exercised deliberately: the terminal row has to
     * come back with a deadline computed from {@code updatedAt + terminalRetention()}, and the
     * non-terminal one has to come back with none.</p>
     */
    @Test
    void aMigratedDatabaseIsReadableThroughARealStoreAndItsRowsResolveTheirRetention() throws Exception {
        Path file = databaseDirectory.resolve("inventory-upgrade-readable.db");
        var terminal = UUID.fromString("22222222-2222-2222-2222-222222222222");
        var live = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Instant terminalUpdatedAt = Instant.parse("2025-12-24T09:30:00Z");
        Instant liveUpdatedAt = Instant.parse("2025-12-25T09:30:00Z");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            assertEquals(4, SqliteSchema.migrate(connection, SqliteSchema.migrations().subList(0, 4), CLOCK));
            insertLegacyInstance(connection, "acme", terminal.toString(), "FAILED",
                    terminalUpdatedAt.getEpochSecond(), 0);
            insertLegacyInstance(connection, "acme", live.toString(), "RUNNING",
                    liveUpdatedAt.getEpochSecond(), 0);
        }

        try (var store = new SqliteExecutionStore(file, CLOCK)) {
            var page = store.listProcessInstances("acme", ProcessInventoryQuery.everything(10))
                    .toCompletableFuture().join();
            assertEquals(2, page.items().size(),
                    "a migrated row must be listable, not merely present in the file");
            // created_at was backfilled from updated_at, so the newest-first ordering the listing
            // promises is the one the backfill produced rather than an accident of insertion order.
            assertEquals(List.of(live, terminal),
                    page.items().stream().map(item -> item.key().processInstanceId()).toList());

            var terminalEntry = store.findProcessInstance(new ExecutionKey("acme", terminal))
                    .toCompletableFuture().join().orElseThrow();
            assertEquals(ProcessInstanceStatus.FAILED, terminalEntry.status());
            assertEquals(terminalUpdatedAt, terminalEntry.createdAt(),
                    "the backfilled created_at is the row's last write, and the store reports it as "
                            + "created_at without pretending to know better");
            assertEquals(1L, terminalEntry.lifecycleGeneration());
            assertEquals(terminalUpdatedAt.plus(store.terminalRetention()),
                    terminalEntry.retainedUntil().orElseThrow(),
                    "a migrated terminal row carries no stored deadline, so the read must resolve one "
                            + "the same way the purge does");

            var liveEntry = store.findProcessInstance(new ExecutionKey("acme", live))
                    .toCompletableFuture().join().orElseThrow();
            assertEquals(ProcessInstanceStatus.RUNNING, liveEntry.status());
            assertEquals(Optional.empty(), liveEntry.retainedUntil(),
                    "retention has not started for a non-terminal row, migrated or not");
        }
    }

    /**
     * Every migration description is operator-facing text that ships inside the database, so none of
     * them may carry an internal tracker identifier.
     *
     * <p>Nothing asserted these strings until now, which is how five of them came to be prefixed with
     * work-item codes that mean nothing to the person reading {@code store_schema_history} to find out
     * what a schema version did. The strings are not otherwise load-bearing -- only the history insert
     * reads them -- so there was no failing test to notice, and a sixth would have arrived the same
     * way. This is the guard that stops the class returning rather than a re-check of the five.</p>
     *
     * <p>The pattern is deliberately shape-based rather than a list of known prefixes: a list would
     * have to be extended by whoever introduces the next tracker, which is exactly the person not
     * thinking about it.</p>
     */
    @Test
    void noMigrationDescriptionCarriesAnInternalTrackerIdentifier() {
        // LETTERS-DIGITS (PERS-05, CORE-317, ABC-1), or the words "issue"/"ticket"/"story" followed by
        // a number. Case-insensitive, anywhere in the string.
        var tracker = java.util.regex.Pattern.compile(
                "\\b([A-Z]{2,}-\\d+|(issue|ticket|story|bug)\\s*#?\\d+)\\b",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        for (SchemaMigration migration : SqliteSchema.migrations()) {
            var matcher = tracker.matcher(migration.description());
            assertTrue(!matcher.find(), () -> "migration " + migration.version() + " describes itself as \""
                    + migration.description() + "\", which carries the tracker identifier \""
                    + matcher.group() + "\". This string is written into store_schema_history in every "
                    + "database this ships and is read by operators with no sight of how the change was "
                    + "tracked; describe the change on its own terms instead.");
        }
    }

    /**
     * The inventory migration, found by what it does. Its number is a merge outcome rather than an
     * identity -- it has already moved from 5 to 6 to 7 as other features landed ahead of it -- so
     * every assertion about it resolves it this way, following the handler migration's own test.
     */
    private static SchemaMigration inventoryMigration() {
        return SqliteSchema.migrations().stream()
                .filter(migration -> migration.description().contains("process and traversal inventory"))
                .reduce((first, second) -> {
                    throw new AssertionError("more than one migration claims to be the inventory");
                })
                .orElseThrow(() -> new AssertionError("no migration describes the inventory"));
    }

    /** A row in the shape a version-4 database holds: no created_at, no generation, no retention. */
    private static void insertLegacyInstance(Connection connection, String tenant, String id,
                                             String status, long second, int nano) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO process_instance (tenant_id, "
                + "process_instance_id, status, graph_version_pin, revision, fencing_token, "
                + "updated_at_epoch_second, updated_at_nano) VALUES (?, ?, ?, 'graph-v1', 3, 0, ?, ?)")) {
            statement.setString(1, tenant);
            statement.setString(2, id);
            statement.setString(3, status);
            statement.setLong(4, second);
            statement.setInt(5, nano);
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
