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
    void resolvedBusyTimeoutReachesAllThreeOwnedConnections() throws Exception {
        var resolved = ExecutionStoreConfiguration.resolveEnvironment(java.util.Map.of(
                ExecutionStoreConfiguration.DIRECTORY_VARIABLE, temporaryDirectory.resolve("three-connections").toString(),
                ExecutionStoreConfiguration.SQLITE_BUSY_TIMEOUT_MILLIS_VARIABLE, "17"));
        try (var opened = ExecutionStoreBootstrap.openResolved(resolved, Clock.systemUTC(), GraphMlLimits.DEFAULTS,
                ai.ravenroot.api.persistence.HumanTaskPolicy.DEFAULTS)) {
            for (Object store : new Object[] {opened.store(), opened.graphDefinitionStore(),
                    opened.executionManifestStore()}) {
                var connectionField = store.getClass().getDeclaredField("connection");
                var workerField = store.getClass().getDeclaredField("worker");
                connectionField.setAccessible(true);
                workerField.setAccessible(true);
                var connection = (java.sql.Connection) connectionField.get(store);
                var worker = (java.util.concurrent.ExecutorService) workerField.get(store);
                worker.submit(() -> {
                    try (var statement = connection.createStatement()) {
                        try (var rows = statement.executeQuery("PRAGMA busy_timeout")) {
                            assertTrue(rows.next());
                            assertEquals(17, rows.getInt(1));
                        }
                        try (var rows = statement.executeQuery("PRAGMA synchronous")) {
                            assertTrue(rows.next());
                            assertEquals(2, rows.getInt(1));
                        }
                    }
                    return null;
                }).get();
            }
        }
    }

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

    @Test
    void resolvedEnvironmentReachesTheOpenedStoreAndPreservesOtherCompositionBudgets() {
        var resolved = ExecutionStoreConfiguration.resolveEnvironment(java.util.Map.of(
                ExecutionStoreConfiguration.DIRECTORY_VARIABLE, temporaryDirectory.resolve("resolved").toString(),
                ExecutionStoreConfiguration.MAX_LEASE_TTL_SECONDS_VARIABLE, "13",
                ExecutionStoreConfiguration.MAX_PAYLOAD_BYTES_VARIABLE, "257",
                ExecutionStoreConfiguration.MAX_CLOCK_SKEW_MILLIS_VARIABLE, "17",
                ExecutionStoreConfiguration.JOURNAL_RETENTION_SECONDS_VARIABLE, "7200",
                ExecutionStoreConfiguration.MAX_INVENTORY_PAGE_SIZE_VARIABLE, "3",
                ExecutionStoreConfiguration.TERMINAL_RETENTION_SECONDS_VARIABLE, "172800",
                ExecutionStoreConfiguration.RESULT_RETENTION_SECONDS_VARIABLE, "14400",
                ExecutionStoreConfiguration.SQLITE_BUSY_TIMEOUT_MILLIS_VARIABLE, "19"));
        var defaults = GraphMlLimits.DEFAULTS;
        var graph = new GraphMlLimits(4096, defaults.maxNodes(), defaults.maxEdges(), defaults.maxProperties(),
                defaults.maxDepth(), defaults.maxStringLength(), defaults.maxKeys(), defaults.maxElements(),
                defaults.maxAttributes(), defaults.maxNamespaceDeclarations());
        try (var opened = ExecutionStoreBootstrap.openResolved(resolved, Clock.systemUTC(), graph,
                ai.ravenroot.api.persistence.HumanTaskPolicy.DEFAULTS)) {
            var store = opened.store();
            assertEquals(java.time.Duration.ofSeconds(13), store.maxLeaseTtl());
            assertEquals(257, store.maxPayloadBytes());
            assertEquals(257, store.maxExecutionResultPayloadBytes());
            assertEquals(java.time.Duration.ofMillis(17), store.maxClockSkew());
            assertEquals(java.time.Duration.ofHours(2), store.journalRetention());
            assertEquals(3, store.maxInventoryPageSize());
            assertEquals(java.time.Duration.ofDays(2), store.terminalRetention());
            assertEquals(java.time.Duration.ofHours(4), store.executionResultRetention());
            assertEquals(4096, opened.graphDefinitionStore().maxDefinitionBytes());
            assertTrue(store.capabilities().contains(ai.ravenroot.api.persistence.StoreCapability.DURABLE));
            store.listProcessInstances("tenant", ai.ravenroot.api.persistence.ProcessInventoryQuery.everything(3))
                    .toCompletableFuture().join();
            assertThrows(java.util.concurrent.CompletionException.class,
                    () -> store.listProcessInstances("tenant",
                            ai.ravenroot.api.persistence.ProcessInventoryQuery.everything(4)).toCompletableFuture().join());
        }
    }

    @Test
    void malformedResolvedSettingsCannotPrepareTheDirectoryOrAcquireAMaintenanceLease() {
        for (String enabled : new String[]{"true", "false"}) {
            Path directory = temporaryDirectory.resolve("invalid-before-open-" + enabled);
            var environment = java.util.Map.of(ExecutionStoreConfiguration.ENABLED_VARIABLE, enabled,
                    ExecutionStoreConfiguration.DIRECTORY_VARIABLE, directory.toString(),
                    ExecutionStoreConfiguration.MAX_PAYLOAD_BYTES_VARIABLE, "secret-overflow-value");
            var failure = assertThrows(IllegalArgumentException.class, () -> ExecutionStoreBootstrap.openResolved(
                    ExecutionStoreConfiguration.resolveEnvironment(environment), Clock.systemUTC(),
                    GraphMlLimits.DEFAULTS, ai.ravenroot.api.persistence.HumanTaskPolicy.DEFAULTS));
            assertNull(failure.getCause());
            assertFalse(Files.exists(directory));
        }
    }

    @Test
    void resolvedDisabledModeRetainsMaintenanceAuthorityWithoutOpeningSQLite() {
        var location = SqliteStoreLocation.underDirectory(temporaryDirectory.resolve("resolved-disabled"));
        var resolved = new ExecutionStoreConfiguration.Resolved(new ExecutionStoreConfiguration(false, location),
                ai.ravenroot.api.persistence.ExecutionStorePolicy.DEFAULTS, java.time.Duration.ZERO);
        try (var opened = ExecutionStoreBootstrap.openResolved(resolved, Clock.systemUTC(), GraphMlLimits.DEFAULTS,
                ai.ravenroot.api.persistence.HumanTaskPolicy.DEFAULTS)) {
            assertNull(opened.store());
            assertFalse(Files.exists(location.databaseFile()));
            assertThrows(SqliteStoreMaintenanceLock.MaintenanceLockException.class,
                    () -> SqliteStoreMaintenanceLock.acquire(location));
        }
        try (var ignored = SqliteStoreMaintenanceLock.acquire(location)) {
            assertFalse(Files.exists(location.databaseFile()));
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
