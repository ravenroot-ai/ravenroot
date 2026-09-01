package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.LeaseHandle;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Claims a lease from a genuinely separate operating-system process, for
 * {@link SqliteCrossProcessLeaseTest}.
 *
 * <p>The point of a second JVM here is that nothing is shared but the file. Two store instances
 * inside one JVM would exercise two SQLite connections, which is closer than it sounds but still
 * leaves open the one question {@link ai.ravenroot.api.persistence.StoreCapability#CROSS_PROCESS_LEASE}
 * is actually about — whether the exclusion holds when the two parties cannot see each other's memory
 * at all.</p>
 */
public final class ClaimLeaseInAnotherProcess {

    static final String CLAIMED = "CLAIMED ";

    private ClaimLeaseInAnotherProcess() {
    }

    public static void main(String[] args) {
        Path databaseFile = Path.of(args[0]);
        var key = new ExecutionKey(args[1], UUID.fromString(args[2]));
        Instant now = Instant.parse(args[3]);
        Duration ttl = Duration.parse(args[4]);

        try (var store = new SqliteExecutionStore(databaseFile, Clock.fixed(now, ZoneOffset.UTC))) {
            LeaseHandle lease = store.claim(key, "worker-in-another-process", ttl)
                    .toCompletableFuture().join();
            System.out.println(CLAIMED + lease.fencingToken() + " " + lease.expiresAt());
            System.out.flush();
        }
    }
}
