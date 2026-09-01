package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.Retryability;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The failure members ADR 0010 section 12.4 calls adapter-conditional, driven for real.
 *
 * <p>The conformance suite asserts {@code Corrupted}, {@code Unavailable}, {@code NotAuthorized} and
 * {@code OutcomeUnknown} by constructing the records directly, because no in-memory adapter's own
 * operations can reach them and the ADR explicitly prefers that to adding a fault-injection point to
 * the port. A durable adapter changes that for two of them: a database file can genuinely hold rows
 * that no longer reconstruct into a legal aggregate, and a writer can genuinely find the file locked.
 * Those two are exercised here through the port's ordinary operations, with no seam involved.</p>
 *
 * <p>{@code NotAuthorized} is now driven here too. The hazard that previously kept it out — a container
 * running as root, where a read-only file is not read-only at all — is handled by probing the
 * filesystem rather than trusting it: {@link #assumePermissionBitsBite} writes to a file it has just
 * made read-only, and aborts the test by name if the write succeeds. That converts "silently passes
 * under root" into "visibly skipped under root", which is the distinction that made the earlier
 * omission correct and makes its removal safe.</p>
 *
 * <p>{@code OutcomeUnknown} remains constructed rather than driven, and ADR 0014 records the evidence
 * that it is <strong>unreachable</strong> through this adapter's own operations rather than merely
 * untested. It is not simulated here, because a test that fabricates an ambiguous outcome a local
 * SQLite commit cannot produce would assert the adapter does something it does not do.</p>
 */
class SqliteFailureMappingTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path databaseDirectory;

    @Test
    void rowsThatNoLongerReconstructIntoALegalAggregateSurfaceAsCorrupted() throws Exception {
        Path file = databaseDirectory.resolve("corrupt.db");
        var key = new ExecutionKey("acme", UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();

        try (var store = new SqliteExecutionStore(file, new MutableClock(EPOCH))) {
            await(store.apply(Fixtures.creationBatch(key, traversalId)));

            // Edited behind the adapter's back, exactly as a hand-run UPDATE during an incident would.
            // The result is a row set that is individually well formed and collectively illegal: the
            // aggregate forbids a COMPLETED process holding a traversal that is not COMPLETED.
            try (Connection direct = DriverManager.getConnection("jdbc:sqlite:" + file);
                 Statement statement = direct.createStatement()) {
                statement.executeUpdate("UPDATE process_instance SET status = 'COMPLETED'");
            }

            var corrupted = assertInstanceOf(ExecutionStoreFailure.Corrupted.class,
                    failureOf(() -> await(store.load(key))),
                    "reconstruction through the aggregate's own constructors is the only place a "
                            + "durable store can detect this; without it the illegal aggregate escapes "
                            + "into the runtime and fails somewhere with no connection to its cause");
            assertEquals(key, corrupted.key());
            assertEquals(Retryability.DETERMINISTIC_REJECT, corrupted.retryability());
            assertTrue(corrupted.describe().contains(key.processInstanceId().toString()));
        }
    }

    @Test
    void aWriterThatFindsTheFileLockedReportsUnavailableAndNothingIsApplied() throws Exception {
        Path file = databaseDirectory.resolve("locked.db");
        var key = new ExecutionKey("acme", UUID.randomUUID());

        // Zero busy timeout so the contention is immediate and deterministic instead of a five second
        // wait that would then succeed and test nothing.
        var config = SqliteStoreConfig.defaults().withBusyTimeout(Duration.ZERO);
        try (var store = new SqliteExecutionStore(file, new MutableClock(EPOCH), config);
             Connection blocker = DriverManager.getConnection("jdbc:sqlite:" + file)) {

            await(store.apply(Fixtures.creationBatch(key, UUID.randomUUID())));

            try (Statement statement = blocker.createStatement()) {
                statement.execute("PRAGMA busy_timeout=0");
                statement.execute("BEGIN IMMEDIATE");
                statement.execute("UPDATE process_instance SET revision = revision");

                var unavailable = assertInstanceOf(ExecutionStoreFailure.Unavailable.class,
                        failureOf(() -> await(store.apply(Fixtures.creationBatch(
                                new ExecutionKey("acme", UUID.randomUUID()), UUID.randomUUID())))),
                        "a busy database is transient and definitely not applied, which is what "
                                + "separates Unavailable from OutcomeUnknown; reporting the second "
                                + "would assert an absence of effect, reporting the first asserts "
                                + "there was none, and here there provably was none");
                assertEquals(Retryability.RETRYABLE_NO_EFFECT, unavailable.retryability());

                statement.execute("ROLLBACK");
            }

            // The rejected batch left nothing behind, and the store still works once the lock is gone.
            var second = new ExecutionKey("acme", UUID.randomUUID());
            assertEquals(1L, await(store.apply(Fixtures.creationBatch(second, UUID.randomUUID()))).revision());
        }
    }

    @Test
    void aDatabaseFileThePlatformWillNotLetTheProcessWriteIsNotAuthorized() throws Exception {
        Path file = databaseDirectory.resolve("readonly.db");
        try (var store = new SqliteExecutionStore(file, new MutableClock(EPOCH))) {
            await(store.apply(Fixtures.creationBatch(new ExecutionKey("acme", UUID.randomUUID()),
                    UUID.randomUUID())));
        }
        assumePermissionBitsBite(databaseDirectory);

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        try {
            var failure = assertInstanceOf(ExecutionStoreFailure.NotAuthorized.class,
                    assertThrows(ExecutionStoreException.class,
                            () -> new SqliteExecutionStore(file, new MutableClock(EPOCH))).failure(),
                    "a store the process may not write is a deterministic reject; reporting it as "
                            + "Unavailable would be RETRYABLE_NO_EFFECT, and a caller retrying a "
                            + "permission failure retries it until someone changes the permissions");
            assertEquals(Retryability.DETERMINISTIC_REJECT, failure.retryability());
            assertTrue(failure.describe().contains(file.toString()),
                    "an operator needs the path in the message to fix it: " + failure.describe());
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    @Test
    void aDirectoryTheProcessMayNotEnterIsNotAuthorizedRatherThanUnavailable() throws Exception {
        Path enclosing = databaseDirectory.resolve("sealed");
        Path file = enclosing.resolve("store.db");
        try (var store = new SqliteExecutionStore(file, new MutableClock(EPOCH))) {
            await(store.apply(Fixtures.creationBatch(new ExecutionKey("acme", UUID.randomUUID()),
                    UUID.randomUUID())));
        }
        assumePermissionBitsBite(databaseDirectory);

        Files.setPosixFilePermissions(enclosing, PosixFilePermissions.fromString("---------"));
        try {
            // This is the case the adapter used to get wrong. SQLite answers an unreadable directory
            // with SQLITE_CANTOPEN, which also covers a missing path and an exhausted descriptor table,
            // so the mapping table cannot honestly read it as a permission problem and fell through to
            // Unavailable. The location is therefore checked against the filesystem before JDBC is
            // asked, which is the only place the distinction is actually available.
            var failure = assertInstanceOf(ExecutionStoreFailure.NotAuthorized.class,
                    assertThrows(ExecutionStoreException.class,
                            () -> new SqliteExecutionStore(file, new MutableClock(EPOCH))).failure(),
                    "an unreadable directory is a permission failure, and SQLITE_CANTOPEN alone cannot "
                            + "say so; without the pre-flight check this is Unavailable and retryable");
            assertEquals(Retryability.DETERMINISTIC_REJECT, failure.retryability());
        } finally {
            Files.setPosixFilePermissions(enclosing, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /**
     * Aborts the calling test when POSIX permission bits do not restrain this process.
     *
     * <p>Running as root — the default in many CI containers — makes a read-only file writable and an
     * unreadable directory readable. A permissions test there does not fail, it silently stops testing
     * anything, which is precisely why {@code NotAuthorized} was previously left undriven. The probe
     * is an actual write to an actual read-only file, not a {@code uid == 0} check, because what
     * matters is whether the bits bite on this filesystem, and container overlays, mounted volumes and
     * capability sets all get a vote.</p>
     */
    private static void assumePermissionBitsBite(Path scratch) throws Exception {
        Path probe = Files.createTempFile(scratch, "permission-probe", ".tmp");
        Files.setPosixFilePermissions(probe, PosixFilePermissions.fromString("r--r--r--"));
        boolean restrained;
        try (var ignored = Files.newOutputStream(probe, java.nio.file.StandardOpenOption.WRITE)) {
            restrained = false;
        } catch (java.io.IOException denied) {
            restrained = true;
        } finally {
            Files.setPosixFilePermissions(probe, PosixFilePermissions.fromString("rw-r--r--"));
            Files.deleteIfExists(probe);
        }
        Assumptions.assumeTrue(restrained,
                "POSIX permission bits do not restrain this process — almost certainly running as root, "
                        + "where a read-only file is not read-only. Skipping rather than passing "
                        + "vacuously: this assertion can only be made where the platform enforces it.");
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
