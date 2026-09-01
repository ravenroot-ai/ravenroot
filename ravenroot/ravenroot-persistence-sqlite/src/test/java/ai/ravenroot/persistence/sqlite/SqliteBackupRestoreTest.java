package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operational surface of the local adapter: where the database lives, taking a backup of one that
 * is in use, and putting it back.
 *
 * <p>Every backup here is taken from a store holding real committed instances, with a second
 * connection actively writing, because a backup of an empty or quiescent database exercises none of
 * what makes hot backup hard.</p>
 */
class SqliteBackupRestoreTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final String TENANT = "acme";

    @TempDir
    Path root;

    @Test
    void aBackupOfADatabaseInUseHoldsEveryCommittedInstanceAndNoUncommittedOne() throws Exception {
        var location = SqliteStoreLocation.underDirectory(root.resolve("live"));
        Path backup = root.resolve("backups").resolve("hot.db");

        List<ExecutionKey> committed = new ArrayList<>();
        try (var store = new SqliteExecutionStore(location, new MutableClock(EPOCH))) {
            for (int i = 0; i < 25; i++) {
                var key = new ExecutionKey(TENANT, UUID.randomUUID());
                await(store.apply(Fixtures.creationBatch(key, UUID.randomUUID())));
                committed.add(key);
            }

            // A second connection holds an open write transaction across the whole backup, having
            // deleted every instance without committing. This is the case a file copy gets wrong: the
            // deletion is real in the writer's transaction and must be invisible to the snapshot.
            try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + location.databaseFile());
                 Statement statement = writer.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("BEGIN IMMEDIATE");
                assertEquals(committed.size(), statement.executeUpdate("DELETE FROM process_instance"),
                        "the uncommitted transaction really did remove every row it can see");

                assertEquals(backup.toAbsolutePath().normalize(), await(store.backupTo(backup)),
                        "the backup runs to completion while another connection holds the write lock; "
                                + "it never waits for writers to stop");

                statement.execute("ROLLBACK");
            }

            // The live store is untouched and still writable afterwards.
            var afterwards = new ExecutionKey(TENANT, UUID.randomUUID());
            assertEquals(1L, await(store.apply(Fixtures.creationBatch(afterwards, UUID.randomUUID()))).revision());
        }

        assertTrue(Files.size(backup) > 0, "the snapshot is a real file");
        assertFalse(Files.exists(Path.of(backup + "-wal")),
                "VACUUM INTO emits one self-contained file; a sidecar here would mean the artifact an "
                        + "operator copies is not the whole database");
        assertEquals("ok", pragma(backup, "integrity_check"));
        assertEquals(committed.size(), countInstances(backup),
                "every instance committed before the backup is in it, and the concurrent uncommitted "
                        + "DELETE is not — a file copy would have captured a database that never existed");
    }

    @Test
    void aRestoredStoreServesTheInstancesTheBackupHeldAndNotTheOnesWrittenAfterIt() {
        var location = SqliteStoreLocation.underDirectory(root.resolve("restore"));
        Path backup = root.resolve("backups").resolve("point-in-time.db");
        var clock = new MutableClock(EPOCH);

        var beforeBackup = new ExecutionKey(TENANT, UUID.randomUUID());
        var afterBackup = new ExecutionKey(TENANT, UUID.randomUUID());

        try (var store = new SqliteExecutionStore(location, clock)) {
            await(store.apply(Fixtures.creationBatch(beforeBackup, UUID.randomUUID())));
            await(store.backupTo(backup));
            await(store.apply(Fixtures.creationBatch(afterBackup, UUID.randomUUID())));
            assertNotNull(await(store.load(afterBackup)), "written after the snapshot, present before the restore");
        }

        location.restoreFrom(backup);

        try (var restored = new SqliteExecutionStore(location, clock)) {
            assertEquals(beforeBackup.processInstanceId(),
                    await(restored.load(beforeBackup)).state().processInstanceId(),
                    "the restored store serves what the backup held");
            var gone = assertInstanceOf(ExecutionStoreFailure.NotFound.class,
                    failureOf(() -> await(restored.load(afterBackup))),
                    "work committed after the snapshot is not in the snapshot; a restore that still "
                            + "answered for it would mean the file was not actually replaced");
            assertEquals(afterBackup, gone.key());

            // The restored store is a working store, not a read-only artifact.
            var fresh = new ExecutionKey(TENANT, UUID.randomUUID());
            assertEquals(1L, await(restored.apply(Fixtures.creationBatch(fresh, UUID.randomUUID()))).revision());
        }
    }

    @Test
    void aRestoreRemovesTheWriteAheadLogOfTheDatabaseItReplaces() throws Exception {
        var location = SqliteStoreLocation.underDirectory(root.resolve("sidecars"));
        Path backup = root.resolve("backups").resolve("sidecar.db");
        var clock = new MutableClock(EPOCH);

        var inBackup = new ExecutionKey(TENANT, UUID.randomUUID());
        try (var store = new SqliteExecutionStore(location, clock)) {
            await(store.apply(Fixtures.creationBatch(inBackup, UUID.randomUUID())));
            await(store.backupTo(backup));
        }

        // Leave a -wal belonging to a *different* database beside the file about to be restored. If a
        // restore only copied the main file, SQLite would replay this log into the restored database on
        // the next open, and the result would pass an integrity check while being silently wrong.
        var stale = new ExecutionKey(TENANT, UUID.randomUUID());
        try (var store = new SqliteExecutionStore(location, clock)) {
            await(store.apply(Fixtures.creationBatch(stale, UUID.randomUUID())));
        }
        Files.writeString(location.walFile(), "not a real write-ahead log", StandardCharsets.UTF_8);
        Files.writeString(location.shmFile(), "not a real shared-memory index", StandardCharsets.UTF_8);

        location.restoreFrom(backup);

        assertFalse(Files.exists(location.walFile()), "the stale -wal is deleted, not left to be replayed");
        assertFalse(Files.exists(location.shmFile()), "the stale -shm is deleted");

        try (var restored = new SqliteExecutionStore(location, clock)) {
            assertNotNull(await(restored.load(inBackup)));
        }
    }

    @Test
    void aBackupNeverOverwritesAndARestoreRefusesAFileThatIsNotAStore() throws Exception {
        var location = SqliteStoreLocation.underDirectory(root.resolve("guards"));
        Path backup = root.resolve("backups").resolve("guarded.db");

        try (var store = new SqliteExecutionStore(location, new MutableClock(EPOCH))) {
            await(store.apply(Fixtures.creationBatch(new ExecutionKey(TENANT, UUID.randomUUID()),
                    UUID.randomUUID())));
            await(store.backupTo(backup));

            var refused = assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                    failureOf(() -> await(store.backupTo(backup))),
                    "overwriting silently is one mistyped path away from destroying the copy an "
                            + "operator is about to depend on");
            assertTrue(refused.describe().contains("never overwrites"));
        }

        Path notAStore = root.resolve("notes.txt");
        Files.writeString(notAStore, "this is not a database", StandardCharsets.UTF_8);
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                assertThrows(ExecutionStoreException.class, () -> location.restoreFrom(notAStore)).failure(),
                "the check runs before anything is deleted");

        Path missing = root.resolve("absent.db");
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                assertThrows(ExecutionStoreException.class, () -> location.restoreFrom(missing)).failure());

        // The store the failed restores targeted is intact.
        try (var store = new SqliteExecutionStore(location, new MutableClock(EPOCH))) {
            assertEquals(1L, countInstances(location.databaseFile()));
        }
    }

    /**
     * Pins the measurement behind {@code restoreFrom}'s documented refusal to guard itself.
     *
     * <p>An open store is not detectable from outside it: an idle connection in WAL mode holds no
     * file lock, so the obvious probe — take SQLite's own exclusive lock and refuse if it is held —
     * succeeds against a store that is demonstrably live. The guard was implemented, failed exactly
     * here, and was removed rather than shipped, because a check that passes while the hazard is
     * present tells an operator the tool would have warned them. If a future SQLite or driver makes an
     * idle connection hold a lock, this test fails and the guard becomes worth having.</p>
     */
    @Test
    void anOpenStoreCannotBeDetectedByTakingSqlitesOwnExclusiveLock() throws Exception {
        var location = SqliteStoreLocation.underDirectory(root.resolve("liveness"));
        try (var store = new SqliteExecutionStore(location, new MutableClock(EPOCH))) {
            await(store.apply(Fixtures.creationBatch(new ExecutionKey(TENANT, UUID.randomUUID()),
                    UUID.randomUUID())));

            try (Connection probe = DriverManager.getConnection("jdbc:sqlite:" + location.databaseFile());
                 Statement statement = probe.createStatement()) {
                statement.execute("PRAGMA busy_timeout=0");
                statement.execute("BEGIN EXCLUSIVE");
                statement.execute("ROLLBACK");
            }
            // Reached without throwing: the exclusive lock was granted while the store above was open
            // and had just committed. There is therefore no liveness signal to build a guard on.
            assertTrue(true);
        }
    }

    @Test
    void aLocationNamesItsThreeFilesAndCreatesItsDirectoryOnDemand() {
        Path nested = root.resolve("a").resolve("b").resolve("c");
        var location = SqliteStoreLocation.underDirectory(nested);

        assertFalse(java.nio.file.Files.exists(nested), "not created before the store opens");
        assertEquals(List.of(location.databaseFile(), location.walFile(), location.shmFile()),
                location.files());
        assertEquals(location.databaseFile() + "-wal", location.walFile().toString());
        assertEquals(SqliteStoreLocation.DEFAULT_FILE_NAME, location.databaseFile().getFileName().toString());

        try (var store = new SqliteExecutionStore(location, new MutableClock(EPOCH))) {
            assertTrue(java.nio.file.Files.isDirectory(nested),
                    "a configured path that does not exist yet is created rather than refused");
            assertEquals(location, store.location());
        }
    }

    private static long countInstances(Path database) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM process_instance")) {
            return rows.next() ? rows.getLong(1) : -1;
        }
    }

    private static String pragma(Path database, String pragma) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA " + pragma)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static ExecutionStoreFailure failureOf(Runnable operation) {
        CompletionException thrown = assertThrows(CompletionException.class, operation::run);
        ExecutionStoreException failure = ExecutionStoreException.unwrap(thrown);
        assertNotNull(failure, "adapters must not leak non-store exceptions: " + thrown);
        return failure.failure();
    }
}
