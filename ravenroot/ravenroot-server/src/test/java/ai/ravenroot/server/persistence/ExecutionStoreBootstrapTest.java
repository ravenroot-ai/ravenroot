package ai.ravenroot.server.persistence;

import ai.ravenroot.core.graph.GraphMlLimits;
import ai.ravenroot.persistence.sqlite.SqliteStoreLocation;
import ai.ravenroot.persistence.sqlite.SqliteStoreMaintenanceLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionStoreBootstrapTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void enabledConfigurationIsOpenedAndPreparedBeforeStartupCanContinue() {
        var location = SqliteStoreLocation.underDirectory(temporaryDirectory.resolve("store"));

        try (var opened = ExecutionStoreBootstrap.openOwned(
                new ExecutionStoreConfiguration(true, location), Clock.systemUTC())) {
            assertTrue(Files.isRegularFile(location.databaseFile()));
            assertTrue(opened.store().capabilities().contains(ai.ravenroot.api.persistence.StoreCapability.DURABLE));
        }
    }

    @Test
    void graphDefinitionStoreUsesTheExactCompositionRootBudget() {
        var location = SqliteStoreLocation.underDirectory(temporaryDirectory.resolve("bounded-store"));
        var defaults = GraphMlLimits.DEFAULTS;
        var narrow = new GraphMlLimits(4_096, defaults.maxNodes(), defaults.maxEdges(),
                defaults.maxProperties(), defaults.maxDepth(), defaults.maxStringLength(), defaults.maxKeys(),
                defaults.maxElements(), defaults.maxAttributes(), defaults.maxNamespaceDeclarations());

        try (var opened = ExecutionStoreBootstrap.openOwned(
                new ExecutionStoreConfiguration(true, location), Clock.systemUTC(), narrow)) {
            assertEquals(4_096, opened.graphDefinitionStore().maxDefinitionBytes());
        }
    }

    @Test
    void disabledStoreStillExcludesBackupAndRestoreForTheAuditLifetime() {
        var location = SqliteStoreLocation.underDirectory(temporaryDirectory.resolve("disabled"));
        var configuration = new ExecutionStoreConfiguration(false, location);

        try (var opened = ExecutionStoreBootstrap.openOwned(configuration, Clock.systemUTC())) {
            assertNull(opened.store());
            var refused = assertThrows(SqliteStoreMaintenanceLock.MaintenanceLockException.class,
                    () -> SqliteStoreMaintenanceLock.acquire(location));
            assertEquals(SqliteStoreMaintenanceLock.Failure.BUSY, refused.failure(),
                    "backup and restore use this same lease and must be refused while audit is live");
        }
        assertFalse(Files.exists(location.databaseFile()), "disabled mode must not create a SQLite store");
        try (var maintenance = SqliteStoreMaintenanceLock.acquire(location)) {
            assertTrue(Files.isRegularFile(location.directory().resolve(SqliteStoreMaintenanceLock.FILE_NAME)));
        }
    }

    @Test
    void unusableLocationFailsClosedWithAStablePathFreeDiagnostic() throws Exception {
        Path secretConfiguredPath = temporaryDirectory.resolve("customer-secret-volume");
        Files.writeString(secretConfiguredPath, "not a directory");
        var configuration = new ExecutionStoreConfiguration(true,
                SqliteStoreLocation.underDirectory(secretConfiguredPath));

        var failure = assertThrows(ExecutionStoreBootstrap.StartupException.class,
                () -> ExecutionStoreBootstrap.openOwned(configuration, Clock.systemUTC()));

        assertEquals(ExecutionStoreBootstrap.FailureReason.INVALID_LOCATION, failure.reason());
        assertEquals("Execution store startup failed: INVALID_LOCATION", failure.getMessage());
        assertNull(failure.getCause(), "the raw adapter diagnostic must not reappear in an uncaught stack trace");
        assertFalse(failure.toString().contains(secretConfiguredPath.toString()));
    }

    @Test
    void ownerClosesStoreThenMaintenanceLeaseExactlyOnce() {
        var order = new ArrayList<String>();
        var opened = ExecutionStoreBootstrap.Opened.forTest(
                () -> order.add("store"), () -> order.add("lock"));

        opened.close();
        opened.close();

        assertEquals(java.util.List.of("store", "lock"), order);
    }

    @Test
    void partialStartupFailureClosesTheOwnerButTransferredStartupDoesNot() {
        var closes = new AtomicInteger();
        var failedStartup = ExecutionStoreBootstrap.Opened.forTest(closes::incrementAndGet, () -> { });
        assertThrows(IllegalStateException.class, () -> {
            try (var ignored = failedStartup.startupGuard()) {
                throw new IllegalStateException("injected after-open startup failure");
            }
        });
        assertEquals(1, closes.get());

        var transferred = ExecutionStoreBootstrap.Opened.forTest(closes::incrementAndGet, () -> { });
        try (var guard = transferred.startupGuard()) {
            guard.transferToShutdownHook();
        }
        assertEquals(1, closes.get());
        transferred.close();
        assertEquals(2, closes.get());
    }

    @Test
    void ownedStoreCheckpointsClosesAndCanBeReopened() {
        var location = SqliteStoreLocation.underDirectory(temporaryDirectory.resolve("reopen"));
        var configuration = new ExecutionStoreConfiguration(true, location);

        try (var first = ExecutionStoreBootstrap.openOwned(configuration, Clock.systemUTC())) {
            first.store().forgottenBefore("tenant-a").toCompletableFuture().join();
        }
        assertTrue(!Files.exists(location.walFile()) || uncheckedSize(location.walFile()) == 0,
                "graceful ownership close must checkpoint or remove the WAL");

        try (var reopened = ExecutionStoreBootstrap.openOwned(configuration, Clock.systemUTC())) {
            assertEquals(java.time.Instant.MIN,
                    reopened.store().forgottenBefore("tenant-a").toCompletableFuture().join());
        }
    }

    @Test
    void aSecondOwnerFailsClosedWhileTheServerLeaseIsHeld() {
        var location = SqliteStoreLocation.underDirectory(temporaryDirectory.resolve("busy"));
        var configuration = new ExecutionStoreConfiguration(true, location);

        try (var first = ExecutionStoreBootstrap.openOwned(configuration, Clock.systemUTC())) {
            var failure = assertThrows(ExecutionStoreBootstrap.StartupException.class,
                    () -> ExecutionStoreBootstrap.openOwned(configuration, Clock.systemUTC()));
            assertEquals(ExecutionStoreBootstrap.FailureReason.MAINTENANCE_BUSY, failure.reason());
            assertFalse(failure.getMessage().contains(location.directory().toString()));
        }
    }

    @Test
    void pendingRecoveryJournalBlocksStartupAndDoesNotLeakTheMaintenanceLease() throws Exception {
        var location = SqliteStoreLocation.underDirectory(temporaryDirectory.resolve("pending-recovery"));
        location.prepare();
        Files.writeString(location.directory().resolve(
                SqliteStoreMaintenanceLock.RECOVERY_JOURNAL_FILE_NAME), "redacted recovery state");

        var failure = assertThrows(ExecutionStoreBootstrap.StartupException.class,
                () -> ExecutionStoreBootstrap.openOwned(
                        new ExecutionStoreConfiguration(true, location), Clock.systemUTC()));

        assertEquals(ExecutionStoreBootstrap.FailureReason.RECOVERY_PENDING, failure.reason());
        assertFalse(failure.getMessage().contains(location.directory().toString()));
        try (var acquired = SqliteStoreMaintenanceLock.acquire(location)) {
            assertTrue(Files.isRegularFile(location.directory().resolve(SqliteStoreMaintenanceLock.FILE_NAME)),
                    "startup failure must release its lease so offline recovery can proceed");
        }
    }

    private static long uncheckedSize(Path path) {
        try {
            return Files.size(path);
        } catch (java.io.IOException failed) {
            throw new AssertionError(failed);
        }
    }
}
