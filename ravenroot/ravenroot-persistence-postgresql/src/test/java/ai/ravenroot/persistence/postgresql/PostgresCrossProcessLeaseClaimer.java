package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.LeaseHandle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Claims a lease from a genuinely separate operating-system process, for
 * {@link PostgresCrossProcessLeaseTest}.
 *
 * <p>The point of a second JVM is that nothing whatever is shared but the database. Two store
 * instances inside one JVM would exercise two connections, which is closer than it sounds but leaves
 * open the one question
 * {@link ai.ravenroot.api.persistence.StoreCapability#CROSS_PROCESS_LEASE} is actually about — whether
 * the exclusion holds between two parties that cannot see each other's memory at all. That is the half
 * the single-host adapter has to disclaim for network filesystems, and the half this adapter exists to
 * provide.</p>
 *
 * <p>The connection details arrive as arguments rather than as anything checked in: they belong to a
 * container this test run started and end with it.</p>
 */
public final class PostgresCrossProcessLeaseClaimer {

    static final String CLAIMED = "CLAIMED ";

    private PostgresCrossProcessLeaseClaimer() {
    }

    /**
     * @param args jdbcUrl, username, password, schema, tenantId, processInstanceId, now, ttl
     */
    public static void main(String[] args) {
        var dataSource = PostgresTestDatabase.dataSource(args[0], args[1], args[2], args[3]);
        var key = new ExecutionKey(args[4], UUID.fromString(args[5]));
        Instant now = Instant.parse(args[6]);
        Duration ttl = Duration.parse(args[7]);

        try (var store = new PostgresExecutionStore(dataSource, Clock.fixed(now, ZoneOffset.UTC))) {
            LeaseHandle lease = store.claim(key, "worker-in-another-process", ttl)
                    .toCompletableFuture().join();
            System.out.println(CLAIMED + lease.fencingToken() + " " + lease.expiresAt());
            System.out.flush();
        }
    }
}
