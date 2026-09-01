package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.LeaseHandle;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ai.ravenroot.api.persistence.StoreCapability#CROSS_PROCESS_LEASE}, tested across processes.
 *
 * <p>The conformance suite covers this capability with a close-and-reopen, which is the right shape
 * for a suite that must run against any adapter but leaves the literal claim — "coordinated across
 * operating-system processes" — asserted only by analogy. Here a second JVM takes the lease and exits;
 * this process, which never saw that JVM's memory, must be told the lease is held, must be refused,
 * and must receive a strictly greater fencing token once the lease has lapsed on the store's clock.</p>
 *
 * <p>The token assertion is the load-bearing one. A counter kept anywhere but the database would
 * restart at zero in this process and reissue the token the other process is still holding, and the
 * fence — whose entire purpose is to make two simultaneous owners impossible — would accept both.</p>
 */
class SqliteCrossProcessLeaseTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);

    @TempDir
    Path databaseDirectory;

    @Test
    void aLeaseTakenByAnotherProcessExcludesThisOneAndItsTokenIsNeverReissued() throws Exception {
        Path file = databaseDirectory.resolve("cross-process.db");
        var key = new ExecutionKey("acme", UUID.randomUUID());
        var clock = new MutableClock(EPOCH);

        try (var store = new SqliteExecutionStore(file, clock)) {
            await(store.apply(Fixtures.creationBatch(key, UUID.randomUUID())));
        }

        long tokenFromOtherProcess = claimInAnotherProcess(file, key);

        try (var store = new SqliteExecutionStore(file, clock)) {
            var contended = assertInstanceOf(ExecutionStoreFailure.LeaseHeldByAnother.class,
                    failureOf(() -> await(store.claim(key, "worker-here", TTL))),
                    "a lease taken in another process must exclude this one; if it did not, both "
                            + "processes would believe they own the instance and the fence would be "
                            + "handing out concurrently valid tokens");
            assertEquals("worker-in-another-process", contended.holderWorkerId());
            assertEquals(EPOCH.plus(TTL), contended.expiresAt());

            clock.set(contended.expiresAt());
            LeaseHandle taken = await(store.claim(key, "worker-here", TTL));
            assertTrue(taken.fencingToken() > tokenFromOtherProcess,
                    "the fencing counter lives in the database, so this process continues the other "
                            + "process's sequence rather than starting its own; a token that repeated "
                            + "would let the abandoned worker write as the current owner");

            // And the abandoned holder's token is rejected, which is the property the number exists for.
            assertInstanceOf(ExecutionStoreFailure.FencedOut.class, failureOf(() ->
                    await(store.apply(ai.ravenroot.api.persistence.ExecutionBatch.to(key)
                            .expecting(ai.ravenroot.api.persistence.RevisionExpectation.any())
                            .fencedBy(tokenFromOtherProcess)
                            .apply(new ai.ravenroot.api.persistence.ExecutionTransition.ProcessTransitioned(
                                    ai.ravenroot.api.application.ProcessInstanceStatus.RUNNING))
                            .build()))));
        }
    }

    private long claimInAnotherProcess(Path file, ExecutionKey key) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process child = new ProcessBuilder(List.of(java.toString(), "-cp",
                System.getProperty("java.class.path"), ClaimLeaseInAnotherProcess.class.getName(),
                file.toString(), key.tenantId(), key.processInstanceId().toString(), EPOCH.toString(),
                TTL.toString())).redirectErrorStream(true).start();

        var transcript = new ArrayList<String>();
        String claimed = null;
        try (var output = new BufferedReader(new InputStreamReader(child.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null) {
                transcript.add(line);
                if (line.startsWith(ClaimLeaseInAnotherProcess.CLAIMED)) {
                    claimed = line;
                }
            }
        }
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the claiming process did not exit");
        assertEquals(0, child.exitValue(), "the claiming process failed: " + transcript);
        assertNotNull(claimed, "the other process never reported a claim: " + transcript);
        return Long.parseLong(claimed.substring(ClaimLeaseInAnotherProcess.CLAIMED.length()).split(" ")[0]);
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static ExecutionStoreFailure failureOf(Runnable operation) {
        CompletionException thrown = assertThrows(CompletionException.class, operation::run);
        ExecutionStoreException failure = ExecutionStoreException.unwrap(thrown);
        assertNotNull(failure, "adapters must not leak non-store exceptions: " + thrown);
        return failure.failure();
    }
}
