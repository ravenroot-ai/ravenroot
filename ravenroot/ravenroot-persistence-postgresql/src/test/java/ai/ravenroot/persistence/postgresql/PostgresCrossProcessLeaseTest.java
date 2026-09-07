package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.LeaseHandle;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static ai.ravenroot.persistence.postgresql.PostgresExecutionStoreFixtures.await;
import static ai.ravenroot.persistence.postgresql.PostgresExecutionStoreFixtures.creationBatch;
import static ai.ravenroot.persistence.postgresql.PostgresExecutionStoreFixtures.failureOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ai.ravenroot.api.persistence.StoreCapability#CROSS_PROCESS_LEASE}, tested across processes.
 *
 * <p>The conformance suite covers this capability with a close-and-reopen, which is the right shape for
 * a suite that must run against any adapter but leaves the literal claim — "coordinated across
 * operating-system processes" — asserted only by analogy. Here a second JVM takes the lease and exits;
 * this process, which never saw that JVM's memory, must be told the lease is held, must be refused, and
 * must receive a strictly greater fencing token once the lease has lapsed on the store's clock.</p>
 *
 * <p>The token assertion is the load-bearing one. A counter kept anywhere but the database would
 * restart in this process and reissue the token the other process is still holding, and the fence —
 * whose entire purpose is to make two simultaneous owners impossible — would accept both. That is the
 * failure this adapter exists to rule out, and it is the one a single-threaded conformance run cannot
 * see.</p>
 */
class PostgresCrossProcessLeaseTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);

    @Test
    void aLeaseTakenByAnotherProcessExcludesThisOneAndItsTokenIsNeverReissued() throws Exception {
        String storeId = "cross-process-" + UUID.randomUUID();
        DataSource dataSource = PostgresTestDatabase.dataSourceFor(storeId);
        var key = new ExecutionKey("acme", UUID.randomUUID());
        var clock = new MutableClock(EPOCH);

        try (var store = new PostgresExecutionStore(dataSource, clock)) {
            await(store.apply(creationBatch(key, UUID.randomUUID())));
        }

        long tokenFromOtherProcess = claimInAnotherProcess(storeId, key);

        try (var store = new PostgresExecutionStore(dataSource, clock)) {
            var contended = assertInstanceOf(ExecutionStoreFailure.LeaseHeldByAnother.class,
                    failureOf(() -> await(store.claim(key, "worker-here", TTL))),
                    "a lease taken in another process must exclude this one; if it did not, both "
                            + "processes would believe they own the instance and the fence would be "
                            + "handing out concurrently valid tokens");
            assertEquals("worker-in-another-process", contended.holderWorkerId());
            assertEquals(EPOCH.plus(TTL), contended.expiresAt());

            // Exactly at the reported expiry, because a lease is live while now < expiresAt and a
            // caller that waits for the instant it was given must find the lease gone rather than
            // having to wait a further tick for a reason nobody published.
            clock.set(contended.expiresAt());
            LeaseHandle taken = await(store.claim(key, "worker-here", TTL));
            assertTrue(taken.fencingToken() > tokenFromOtherProcess,
                    "the fencing counter lives in the database, so this process continues the other "
                            + "process's sequence rather than starting its own; a token that repeated "
                            + "would let the abandoned worker write as the current owner");

            // And the abandoned holder's token is rejected, which is the property the number exists for.
            var fenced = assertInstanceOf(ExecutionStoreFailure.FencedOut.class,
                    failureOf(() -> await(store.apply(ExecutionBatch.to(key)
                            .expecting(RevisionExpectation.any())
                            .fencedBy(tokenFromOtherProcess)
                            .apply(new ExecutionTransition.ProcessTransitioned(
                                    ProcessInstanceStatus.RUNNING))
                            .build()))));
            assertEquals(tokenFromOtherProcess, fenced.presentedToken());
            assertEquals(taken.fencingToken(), fenced.currentToken());

            // The new owner, holding the current token, is not refused. Without this the previous
            // assertion would pass just as happily against a store that refused every batch.
            assertEquals(2L, await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.any())
                    .fencedBy(taken.fencingToken())
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                    .build())).revision());
        }
    }

    private long claimInAnotherProcess(String storeId, ExecutionKey key) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process child = new ProcessBuilder(List.of(java.toString(), "-cp",
                System.getProperty("java.class.path"), PostgresCrossProcessLeaseClaimer.class.getName(),
                PostgresTestDatabase.jdbcUrl(), PostgresTestDatabase.username(),
                PostgresTestDatabase.password(), PostgresTestDatabase.schemaNameFor(storeId),
                key.tenantId(), key.processInstanceId().toString(), EPOCH.toString(), TTL.toString()))
                .redirectErrorStream(true).start();

        var transcript = new ArrayList<String>();
        String claimed = null;
        try (var output = new BufferedReader(new InputStreamReader(child.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null) {
                transcript.add(line);
                if (line.startsWith(PostgresCrossProcessLeaseClaimer.CLAIMED)) {
                    claimed = line;
                }
            }
        }
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the claiming process did not exit");
        assertEquals(0, child.exitValue(), "the claiming process failed: " + transcript);
        assertNotNull(claimed, "the other process never reported a claim: " + transcript);
        return Long.parseLong(
                claimed.substring(PostgresCrossProcessLeaseClaimer.CLAIMED.length()).split(" ")[0]);
    }
}
