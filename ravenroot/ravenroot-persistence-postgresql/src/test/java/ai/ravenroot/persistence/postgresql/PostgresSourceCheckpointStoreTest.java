package ai.ravenroot.persistence.postgresql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

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
            assertTrue(owner.recordInbox("occurrences", event, Duration.ofDays(1)).toCompletableFuture().join());

            assertThrows(IllegalStateException.class,
                    () -> peerStore.openSourceCheckpointStore("tenant", "deployment/timer/schedule"));
            owner.close();

            try (var successor = peerStore.openSourceCheckpointStore("tenant", "deployment/timer/schedule")) {
                assertEquals(advanced, successor.checkpoint("occurrences").toCompletableFuture().join());
                assertFalse(successor.recordInbox("occurrences", event, Duration.ofDays(1))
                        .toCompletableFuture().join());
            }
        }
    }
}
