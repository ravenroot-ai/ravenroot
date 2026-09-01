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
 * Claims a lease from a genuinely separate operating-system process and then holds it forever, for
 * {@link SqliteLeaseAbandonedByKillTest} — the lease claim/renew/release cell of the crash/replay
 * matrix (QA-03 lease-abandonment coverage).
 *
 * <h2>Why this differs from {@link ClaimLeaseInAnotherProcess}</h2>
 * <p>That class claims a lease and exits cleanly, so its store's {@code try}-with-resources runs
 * {@code close()} before the process ends. ADR 0010 section 13.1 states that {@code close()} and a
 * real {@code kill -9} are supposed to be semantically identical for lease handling — {@code close()}
 * releases none, exactly like a crash — but {@link SqliteCrossProcessLeaseTest} only ever exercises the
 * clean-exit half of that claim. Every other cell in this matrix exists because "a test that closes the
 * store still gives the adapter a chance to run code" is not idle caution; this class removes that
 * chance for lease abandonment specifically, the one place in the matrix it had not yet been removed.
 * The store here is never closed and the lease is never released: the process parks, and the kill is
 * the only thing that ends it.</p>
 */
public final class HoldLeaseUntilKilledInAnotherProcess {

    static final String CLAIMED = "CLAIMED ";
    static final String AT_BOUNDARY = "AT_BOUNDARY";

    private HoldLeaseUntilKilledInAnotherProcess() {
    }

    public static void main(String[] args) throws Exception {
        Path databaseFile = Path.of(args[0]);
        var key = new ExecutionKey(args[1], UUID.fromString(args[2]));
        Instant now = Instant.parse(args[3]);
        Duration ttl = Duration.parse(args[4]);

        // Deliberately not try-with-resources: closing would release nothing under ADR 0010 anyway,
        // but never calling close() at all is what makes this a kill test rather than a shutdown test.
        var store = new SqliteExecutionStore(databaseFile, Clock.fixed(now, ZoneOffset.UTC));
        LeaseHandle lease = store.claim(key, "worker-in-another-process", ttl).toCompletableFuture().join();
        System.out.println(CLAIMED + lease.fencingToken() + " " + lease.expiresAt());
        System.out.println(AT_BOUNDARY);
        System.out.flush();
        // Long enough that a parent which failed to kill us hits its own timeout and reports that,
        // rather than this process quietly exiting and inventing a pass.
        Thread.sleep(Duration.ofMinutes(10));
    }
}
