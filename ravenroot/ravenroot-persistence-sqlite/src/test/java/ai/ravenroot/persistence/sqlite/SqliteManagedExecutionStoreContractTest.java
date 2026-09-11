package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.ExecutionManifestReferences;
import ai.ravenroot.api.persistence.ExecutionManifestStoreException;
import ai.ravenroot.api.persistence.ExecutionManifestStoreFailure;
import ai.ravenroot.api.persistence.ExecutionPersistenceAuthority;
import ai.ravenroot.testkit.persistence.ManagedExecutionStoreContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the managed persistence authority contract against the actual SQLite adapters. */
class SqliteManagedExecutionStoreContractTest extends ManagedExecutionStoreContract {
    @TempDir Path directory;

    @Override
    protected Bundle open(String id, Clock clock) {
        Path file = directory.resolve(id + ".db");
        return new Bundle(new SqliteExecutionStore(file, clock),
                new SqliteExecutionManifestStore(file, clock, ExecutionManifestReferences.NONE));
    }

    @Override
    protected Bundle open(String id, Clock clock, int maximumPayloadBytes) {
        Path file = directory.resolve(id + ".db");
        SqliteStoreConfig defaults = SqliteStoreConfig.defaults();
        var config = new SqliteStoreConfig(defaults.synchronousMode(), defaults.busyTimeout(),
                defaults.maxLeaseTtl(), maximumPayloadBytes, defaults.maxClockSkew(),
                defaults.journalRetention(), defaults.maxInventoryPageSize(), defaults.terminalRetention(),
                defaults.executionResultRetention());
        return new Bundle(new SqliteExecutionStore(file, clock, config),
                new SqliteExecutionManifestStore(file, clock, ExecutionManifestReferences.NONE));
    }

    @Test
    void processCreationAndOrphanCleanupSerializeAcrossTheManifestLock() throws Exception {
        assertCleanupSerializes(false, false);
    }

    @Test
    void processCreationAndOrphanPurgeSerializeAcrossTheManifestLock() throws Exception {
        assertCleanupSerializes(true, false);
    }

    @Test
    void formatFourProcessCreationAndOrphanCleanupSerializeAcrossTheManifestLock() throws Exception {
        assertCleanupSerializes(false, true);
    }

    @Test
    void formatFourProcessCreationAndOrphanPurgeSerializeAcrossTheManifestLock() throws Exception {
        assertCleanupSerializes(true, true);
    }

    private void assertCleanupSerializes(boolean purge, boolean formatFour) throws Exception {
        Path file = directory.resolve("cleanup-race.db");
        var atCommit = new CountDownLatch(1);
        var releaseCommit = new CountDownLatch(1);
        var cleanupEntered = new CountDownLatch(1);
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        CommitBoundary boundary = new CommitBoundary() {
            @Override public void beforeCommit() {
                if (!armed.get()) return;
                atCommit.countDown();
                try {
                    assertTrue(releaseCommit.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        };
        try (var executions = new SqliteExecutionStore(file, Clock.systemUTC(),
                     SqliteStoreConfig.defaults(), boundary);
             var manifests = new SqliteExecutionManifestStore(
                     SqliteStoreLocation.ofFile(file), Clock.systemUTC(),
                     ExecutionManifestReferences.NONE, cleanupEntered::countDown)) {
            var key = new ai.ravenroot.api.persistence.ExecutionKey("acme", UUID.randomUUID());
            var stored = await(manifests.pin(formatFour
                    ? manifestV4(key, executions.maxPayloadBytes())
                    : manifest(key, executions.maxPayloadBytes())));
            armed.set(true);
            var creation = executions.applyManaged(creationBatch(key), ExecutionPersistenceAuthority.from(stored));
            assertTrue(atCommit.await(10, TimeUnit.SECONDS));
            java.util.concurrent.CompletionStage<?> cleanup = purge
                    ? manifests.purgeUnreferencedManifests("acme") : manifests.remove(key);
            assertTrue(cleanupEntered.await(10, TimeUnit.SECONDS),
                    "cleanup worker must enter before its blocked state is asserted");
            assertFalse(cleanup.toCompletableFuture().isDone(),
                    "cleanup must wait for the transaction that validated and created the process");
            releaseCommit.countDown();
            await(creation);
            if (purge) {
                org.junit.jupiter.api.Assertions.assertEquals(0L, cleanup.toCompletableFuture().join());
            } else {
                var failure = assertThrows(RuntimeException.class,
                        () -> cleanup.toCompletableFuture().join());
                assertInstanceOf(ExecutionManifestStoreFailure.StillReferenced.class,
                        ExecutionManifestStoreException.unwrap(failure).failure());
            }
            assertTrue(await(manifests.contains(key)));
        }
    }
}
