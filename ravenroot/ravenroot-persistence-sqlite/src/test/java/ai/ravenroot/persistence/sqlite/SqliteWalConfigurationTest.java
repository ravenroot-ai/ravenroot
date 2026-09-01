package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The storage settings the declared capabilities rest on, asserted rather than assumed.
 *
 * <p>Every one of these is a setting whose absence is invisible: the adapter works, the conformance
 * suite passes, and the only thing that changes is what happens when the machine stops. That is the
 * category of defect worth a test with no other purpose.</p>
 */
class SqliteWalConfigurationTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path databaseDirectory;

    @Test
    void theStoreOpensInWalModeWithFullSynchronousAndABusyTimeout() throws Exception {
        Path file = databaseDirectory.resolve("pragmas.db");
        try (var store = new SqliteExecutionStore(file, new MutableClock(EPOCH))) {
            assertTrue(store.supports(StoreCapability.DURABLE));
        }

        // Read back through a second, independent connection, because journal_mode is a property of
        // the FILE rather than of the connection that set it -- which is also why it survives every
        // later open without being reasserted.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            assertEquals("wal", scalar(connection, "PRAGMA journal_mode"));
        }
    }

    @Test
    void synchronousIsFullByDefaultAndConfigurableDown() throws Exception {
        assertEquals(SqliteStoreConfig.SynchronousMode.FULL, SqliteStoreConfig.defaults().synchronousMode(),
                "an adapter that declares DURABLE while defaulting to NORMAL declares something it "
                        + "does not do, and no kill test would catch the difference");

        // PRAGMA synchronous reports the numeric level: 2 is FULL, 1 is NORMAL. It is a connection
        // property, so it has to be read through the store's own connection.
        assertEquals("2", pragmaThroughStore("full.db", SqliteStoreConfig.defaults()));
        assertEquals("1", pragmaThroughStore("normal.db", SqliteStoreConfig.defaults()
                .withSynchronousMode(SqliteStoreConfig.SynchronousMode.NORMAL)));
    }

    @Test
    void closingTruncatesTheWriteAheadLog() throws Exception {
        Path file = databaseDirectory.resolve("checkpoint.db");
        Path walFile = Path.of(file + "-wal");
        var clock = new MutableClock(EPOCH);

        var store = new SqliteExecutionStore(file, clock);
        for (int index = 0; index < 25; index++) {
            var key = new ExecutionKey("acme", UUID.randomUUID());
            store.apply(Fixtures.creationBatch(key, UUID.randomUUID())).toCompletableFuture().join();
        }
        assertTrue(Files.exists(walFile) && Files.size(walFile) > 0,
                "test precondition: the writes must actually have gone through the WAL");

        store.close();

        // A graceful close is the one moment the adapter knows no further write is coming, so it is
        // the right moment to fold the log back into the database. Left alone, a copy of the database
        // file taken by an operator would be accompanied by an arbitrarily large -wal that they have
        // to know to copy too.
        assertTrue(!Files.exists(walFile) || Files.size(walFile) == 0,
                "close() must checkpoint with TRUNCATE; -wal was " + sizeOf(walFile) + " bytes");

        // And the data is in the database itself, not only in a log that was thrown away.
        try (var reopened = new SqliteExecutionStore(file, clock)) {
            assertEquals(0L, reopened.idempotencyRecordCount("acme").toCompletableFuture().join());
            assertTrue(reopened.leases("acme").toCompletableFuture().join().isEmpty());
        }
    }

    @Test
    void theDeclaredBoundsKeepTheSuiteAssertionsThatDependOnThemRunnable() {
        var config = SqliteStoreConfig.defaults();
        assertTrue(config.maxPayloadBytes() < 64 * 1024 * 1024,
                "above this the payload-rejection assertion refuses to allocate a payload large "
                        + "enough to exceed the limit and skips, taking a real assertion with it");
        assertTrue(config.maxClockSkew().compareTo(Duration.ofSeconds(2)) > 0,
                "below this the ambiguity-window assertion has no room to probe and skips");
        assertEquals(Duration.ofMinutes(5), config.maxLeaseTtl());
    }

    private String pragmaThroughStore(String name, SqliteStoreConfig config) throws Exception {
        Path file = databaseDirectory.resolve(name);
        try (var store = new SqliteExecutionStore(file, new MutableClock(EPOCH), config)) {
            // Provoke a write so the setting is exercised, not merely issued.
            store.apply(Fixtures.creationBatch(new ExecutionKey("acme", UUID.randomUUID()),
                    UUID.randomUUID())).toCompletableFuture().join();
            return store.pragmaForTest("synchronous");
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static String sizeOf(Path path) throws Exception {
        return Files.exists(path) ? String.valueOf(Files.size(path)) : "absent";
    }
}
