package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.persistence.ExecutionManifestReferences;
import ai.ravenroot.api.persistence.ExecutionManifestStoreException;
import ai.ravenroot.api.persistence.ExecutionManifestStoreFailure;
import ai.ravenroot.api.persistence.ExecutionPersistenceAuthority;
import ai.ravenroot.testkit.persistence.ManagedExecutionStoreContract;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the managed persistence authority contract against a real containerized PostgreSQL store. */
class PostgresManagedExecutionStoreContractTest extends ManagedExecutionStoreContract {
    @Override
    protected Bundle open(String id, Clock clock) {
        var dataSource = PostgresTestDatabase.dataSourceFor(id);
        return new Bundle(new PostgresExecutionStore(dataSource, clock),
                new PostgresExecutionManifestStore(dataSource, clock, ExecutionManifestReferences.NONE));
    }

    @Override
    protected Bundle open(String id, Clock clock, int maximumPayloadBytes) {
        var dataSource = PostgresTestDatabase.dataSourceFor(id);
        PostgresStoreConfig defaults = PostgresStoreConfig.defaults();
        var config = new PostgresStoreConfig(defaults.lockTimeout(), defaults.statementTimeout(),
                defaults.serializationRetries(), defaults.maxLeaseTtl(), maximumPayloadBytes,
                defaults.maxClockSkew(), defaults.journalRetention(), defaults.maxInventoryPageSize(),
                defaults.terminalRetention(), defaults.executionResultRetention(),
                defaults.graphDefinitionUpsertAttempts());
        return new Bundle(new PostgresExecutionStore(dataSource, clock, config),
                new PostgresExecutionManifestStore(dataSource, clock, ExecutionManifestReferences.NONE,
                        PostgresExecutionManifestStore.DEFAULT_MAX_PIN_ATTEMPTS, config));
    }

    @Test
    void processCreationAndOrphanCleanupSerializeAcrossTheManifestRowLock() throws Exception {
        assertCleanupSerializes(false);
    }

    @Test
    void processCreationAndOrphanPurgeSerializeAcrossTheManifestRowLock() throws Exception {
        assertCleanupSerializes(true);
    }

    private void assertCleanupSerializes(boolean purge) throws Exception {
        DataSource raw = PostgresTestDatabase.dataSourceFor("managed-cleanup-" + UUID.randomUUID());
        var atCommit = new CountDownLatch(1);
        var releaseCommit = new CountDownLatch(1);
        var armed = new AtomicBoolean();
        var blockOneCommit = new AtomicBoolean(true);
        var cleanupEntered = new CountDownLatch(1);
        DataSource delayed = interceptConnections(raw, connection -> (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("commit") && armed.get() && blockOneCommit.compareAndSet(true, false)) {
                        atCommit.countDown();
                        assertTrue(releaseCommit.await(10, TimeUnit.SECONDS));
                    }
                    return invoke(connection, method, args);
                }));
        try (var executions = new PostgresExecutionStore(delayed, Clock.systemUTC());
             var manifests = new PostgresExecutionManifestStore(
                     raw, Clock.systemUTC(), ExecutionManifestReferences.NONE,
                     PostgresExecutionManifestStore.DEFAULT_MAX_PIN_ATTEMPTS,
                     PostgresStoreConfig.defaults(), cleanupEntered::countDown)) {
            var key = new ai.ravenroot.api.persistence.ExecutionKey("acme", UUID.randomUUID());
            var stored = await(manifests.pin(manifest(key, executions.maxPayloadBytes())));
            armed.set(true);
            var creation = executions.applyManaged(creationBatch(key), ExecutionPersistenceAuthority.from(stored));
            assertTrue(atCommit.await(10, TimeUnit.SECONDS));
            java.util.concurrent.CompletionStage<?> cleanup = purge
                    ? manifests.purgeUnreferencedManifests("acme") : manifests.remove(key);
            assertTrue(cleanupEntered.await(10, TimeUnit.SECONDS),
                    "cleanup worker must enter before its blocked state is asserted");
            assertFalse(cleanup.toCompletableFuture().isDone(),
                    "cleanup must wait for the transaction holding the manifest row lock");
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

    private static DataSource interceptConnections(DataSource source,
            java.util.function.UnaryOperator<Connection> interceptor) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    Object result = invoke(source, method, args);
                    return result instanceof Connection connection ? interceptor.apply(connection) : result;
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }
}
