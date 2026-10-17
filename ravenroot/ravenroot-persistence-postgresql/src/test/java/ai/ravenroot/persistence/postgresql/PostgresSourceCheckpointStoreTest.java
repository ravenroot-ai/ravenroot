package ai.ravenroot.persistence.postgresql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PostgresSourceCheckpointStoreTest {
    @Test void pooledConnectionReleasesSessionLockBeforeReturningToPool() throws Exception {
        String storeId = "pooled-source-checkpoint-" + UUID.randomUUID();
        var config = new HikariConfig();
        config.setDataSource(PostgresTestDatabase.dataSourceFor(storeId));
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(500);
        try (var pool = new HikariDataSource(config);
             var store = new PostgresExecutionStore(pool, Clock.systemUTC())) {
            var owner = store.openSourceCheckpointStore("tenant", "deployment/timer/schedule");
            owner.close();
            // The same physical session is returned. A leaked lock would be reentrant and invisible
            // to another acquisition on this pooled connection, so inspect PostgreSQL's lock table.
            try (var connection = pool.getConnection();
                 var statement = connection.prepareStatement(
                         "SELECT count(*) FROM pg_locks WHERE pid = pg_backend_pid() AND locktype = 'advisory'");
                 var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test void advisoryOwnershipExcludesPeersAndSuccessorResumesCursorAndInbox() {
        String storeId = "source-checkpoint-" + UUID.randomUUID();
        var dataSource = PostgresTestDatabase.dataSourceFor(storeId);
        try (var firstStore = new PostgresExecutionStore(dataSource, Clock.systemUTC());
             var peerStore = new PostgresExecutionStore(dataSource, Clock.systemUTC())) {
            var owner = firstStore.openSourceCheckpointStore("tenant", "deployment/timer/schedule");
            var initial = owner.checkpoint("occurrences").toCompletableFuture().join();
            var advanced = owner.advance(initial, 42).toCompletableFuture().join();
            UUID event = UUID.randomUUID();
            assertFalse(owner.containsInbox("occurrences", event).toCompletableFuture().join());
            assertTrue(owner.recordInbox("occurrences", event, Duration.ofDays(1)).toCompletableFuture().join());
            assertTrue(owner.containsInbox("occurrences", event).toCompletableFuture().join());

            assertThrows(IllegalStateException.class,
                    () -> peerStore.openSourceCheckpointStore("tenant", "deployment/timer/schedule"));
            owner.close();

            try (var successor = peerStore.openSourceCheckpointStore("tenant", "deployment/timer/schedule")) {
                assertEquals(advanced, successor.checkpoint("occurrences").toCompletableFuture().join());
                assertTrue(successor.containsInbox("occurrences", event).toCompletableFuture().join());
                assertFalse(successor.recordInbox("occurrences", event, Duration.ofDays(1))
                        .toCompletableFuture().join());
            }
        }
    }

    @Test void closingAnOwnerRetainsItsAdvisoryLockUntilAnAdmittedWriteSettles() throws Exception {
        String storeId = "pending-source-checkpoint-" + UUID.randomUUID();
        DataSource raw = PostgresTestDatabase.dataSourceFor(storeId);
        var atCommit = new CountDownLatch(1);
        var releaseCommit = new CountDownLatch(1);
        var armed = new AtomicBoolean();
        var blockOneCommit = new AtomicBoolean(true);
        DataSource delayed = interceptConnections(raw, connection -> (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("commit") && armed.get()
                            && blockOneCommit.compareAndSet(true, false)) {
                        atCommit.countDown();
                        assertTrue(releaseCommit.await(10, TimeUnit.SECONDS));
                    }
                    return invoke(connection, method, args);
                }));
        try (var store = new PostgresExecutionStore(delayed, Clock.systemUTC());
             var peer = new PostgresExecutionStore(raw, Clock.systemUTC())) {
            var owner = store.openSourceCheckpointStore("tenant", "deployment/timer/pending");
            armed.set(true);
            UUID event = UUID.randomUUID();
            var write = owner.recordInbox("occurrences", event, Duration.ofDays(1));
            assertTrue(atCommit.await(10, TimeUnit.SECONDS), "the admitted write must reach commit");

            owner.close();
            assertThrows(IllegalStateException.class,
                    () -> peer.openSourceCheckpointStore("tenant", "deployment/timer/pending"),
                    "retirement cannot transfer custody while an admitted outcome is unresolved");

            releaseCommit.countDown();
            assertTrue(write.toCompletableFuture().get(10, TimeUnit.SECONDS));
            try (var successor = peer.openSourceCheckpointStore("tenant", "deployment/timer/pending")) {
                assertTrue(successor.containsInbox("occurrences", event).toCompletableFuture().join(),
                        "the successor observes the write whose outcome settled before ownership transferred");
            }
        } finally {
            releaseCommit.countDown();
        }
    }

    @Test void lateOpenReleasesAdvisoryOwnershipOutsideTheOwnerListMonitor() throws Exception {
        String storeId = "late-source-checkpoint-" + UUID.randomUUID();
        DataSource raw = PostgresTestDatabase.dataSourceFor(storeId);
        var advisoryAcquired = new CountDownLatch(1);
        var allowPublication = new CountDownLatch(1);
        var atUnlock = new CountDownLatch(1);
        var releaseUnlock = new CountDownLatch(1);
        var ownerMonitor = new AtomicReference<Object>();
        DataSource delayed = interceptConnections(raw, connection -> (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    Object result = invoke(connection, method, args);
                    if (!method.getName().equals("prepareStatement") || !(result instanceof PreparedStatement statement)
                            || args == null || args.length == 0 || !(args[0] instanceof String sql)) return result;
                    boolean acquisition = sql.equals("SELECT pg_try_advisory_lock(?)");
                    boolean release = sql.equals("SELECT pg_advisory_unlock(?)");
                    if (!acquisition && !release) return result;
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                            new Class<?>[]{PreparedStatement.class}, (statementProxy, statementMethod, statementArgs) -> {
                                Object statementResult = invoke(statement, statementMethod, statementArgs);
                                if (!statementMethod.getName().equals("executeQuery")
                                        || !(statementResult instanceof ResultSet rows)) return statementResult;
                                if (release) {
                                    assertFalse(Thread.holdsLock(ownerMonitor.get()),
                                            "advisory unlock must not run while holding the owner-list monitor");
                                    atUnlock.countDown();
                                    assertTrue(releaseUnlock.await(10, TimeUnit.SECONDS));
                                    return rows;
                                }
                                return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                                        new Class<?>[]{ResultSet.class}, (rowsProxy, rowsMethod, rowsArgs) -> {
                                            Object rowsResult = invoke(rows, rowsMethod, rowsArgs);
                                            if (rowsMethod.getName().equals("getBoolean") && Boolean.TRUE.equals(rowsResult)) {
                                                advisoryAcquired.countDown();
                                                assertTrue(allowPublication.await(10, TimeUnit.SECONDS));
                                            }
                                            return rowsResult;
                                        });
                            });
                }));
        try (var store = new PostgresExecutionStore(delayed, Clock.systemUTC())) {
            var owners = PostgresExecutionStore.class.getDeclaredField("sourceCheckpointOwners");
            owners.setAccessible(true);
            ownerMonitor.set(owners.get(store));
            var opened = new CompletableFuture<ai.ravenroot.api.persistence.SourceCheckpointStore>();
            Thread.ofVirtual().start(() -> {
                try {
                    opened.complete(store.openSourceCheckpointStore("tenant", "deployment/timer/late"));
                } catch (Throwable failure) {
                    opened.completeExceptionally(failure);
                }
            });
            assertTrue(advisoryAcquired.await(10, TimeUnit.SECONDS));

            store.close();
            allowPublication.countDown();
            assertTrue(atUnlock.await(10, TimeUnit.SECONDS), "the late opener must begin ownership cleanup");
            assertFalse(opened.isDone(), "blocked JDBC cleanup keeps only the rejected opener pending");
            releaseUnlock.countDown();
            ExecutionException rejected = assertThrows(ExecutionException.class,
                    () -> opened.get(10, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, rejected.getCause());

            try (var peer = new PostgresExecutionStore(raw, Clock.systemUTC());
                 var successor = peer.openSourceCheckpointStore("tenant", "deployment/timer/late")) {
                assertNotNull(successor);
            }
        } finally {
            allowPublication.countDown();
            releaseUnlock.countDown();
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

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }
}
