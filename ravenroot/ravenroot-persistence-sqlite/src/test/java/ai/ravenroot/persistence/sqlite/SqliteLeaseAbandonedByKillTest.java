package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.LeaseHandle;
import ai.ravenroot.testkit.persistence.KillMatrixArtifact;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crash/replay matrix's lease claim/renew/release cell.
 *
 * <h2>The asymmetry this proves, stated by {@link ai.ravenroot.core.runtime.ExecutionRecorder}'s own
 * class Javadoc</h2>
 * <p>"Renewal failure is a stop; release is an optimisation... a worker whose renewal failed has lost
 * the fence, which means some other worker may now be executing this instance; its own next write is
 * refused with {@code FencedOut} — but that refusal arrives <em>after</em> the effect it was recording
 * already happened." This cell turns that sentence into evidence at the store level, which is where the
 * refusal is actually enforced: a real, killed process that held the lease is excluded once its lease
 * expires, a fresh claimant gets a strictly greater fencing token continuing the same database-backed
 * sequence, and the killed process's own (necessarily stale, since it never wrote again) token is
 * refused by the store as {@link ExecutionStoreFailure.FencedOut} — evidencing that the refusal, not
 * the dead worker's own good behaviour, is what makes the asymmetry safe.</p>
 *
 * <h2>What is new here relative to {@link SqliteCrossProcessLeaseTest}</h2>
 * <p>That test already proves cross-process exclusion and token continuity, through a second process
 * that claims a lease and <b>exits cleanly</b> — its store's {@code close()} runs. ADR 0010 section
 * 13.1 declares {@code close()} and a real {@code kill -9} equivalent for lease handling, but declaring
 * an equivalence and testing only one side of it is exactly the gap the rest of this matrix exists to
 * close: "a test that closes the store still gives the adapter a chance to run code." This cell removes
 * that chance for lease abandonment specifically — {@link HoldLeaseUntilKilledInAnotherProcess} never
 * closes its store and never releases its lease; only {@code SIGKILL} ends it — so the logical
 * assertions below overlap with that test by design, while the process that produces them does not.</p>
 *
 * <h2>Process death, not machine death; no seed</h2>
 * <p>A real {@code SIGKILL} via {@code Process#destroyForcibly()}, which leaves the OS page cache
 * intact; this cell says nothing about durability across power loss. The kill point is announced (the
 * child prints once it holds the lease, before parking) rather than sampled.</p>
 */
class SqliteLeaseAbandonedByKillTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);

    /**
     * Where a failing cell's {@code KillMatrixArtifact} is written. Deliberately <b>not</b>
     * {@code databaseDirectory}: that field is a JUnit {@code @TempDir}, deleted immediately after the
     * test method returns by default, which would delete the artifact at the exact moment a reader
     * needs it. {@code target/} survives until the next {@code mvn clean}, the same lifetime
     * surefire's own reports get.
     */
    private static final Path ARTIFACT_ROOT = Path.of("target");

    @TempDir
    Path databaseDirectory;

    @Test
    void aLeaseHeldByAKilledProcessExpiresAndItsStaleTokenIsThenRefused() throws Exception {
        Path file = databaseDirectory.resolve("lease-abandoned-by-kill.db");
        var key = new ExecutionKey("acme", UUID.randomUUID());
        var clock = new MutableClock(EPOCH);

        try (var store = new SqliteExecutionStore(file, clock)) {
            await(store.apply(Fixtures.creationBatch(key, UUID.randomUUID())));
        }

        KillResult killed = runUntilBoundaryThenKill(file, key);
        assertTrue(killed.exitCode() != 0, "a SIGKILLed process must not report a clean exit; got "
                + killed.exitCode());
        long tokenFromKilledProcess = tokenFrom(killed.transcript());
        // Captured before any recovery below can touch the live file, used only if this cell fails.
        Path snapshot = KillMatrixArtifact.snapshot(file);

        try {
            try (var store = new SqliteExecutionStore(file, clock)) {
                var contended = assertInstanceOf(ExecutionStoreFailure.LeaseHeldByAnother.class,
                        failureOf(() -> await(store.claim(key, "worker-here", TTL))),
                        "a lease held by a killed process must still exclude a new claimant -- the kill "
                                + "released nothing, exactly as ADR 0010 section 13.1 requires");
                assertEquals("worker-in-another-process", contended.holderWorkerId());
                assertEquals(EPOCH.plus(TTL), contended.expiresAt());

                clock.set(contended.expiresAt());
                LeaseHandle taken = await(store.claim(key, "worker-here", TTL));
                assertTrue(taken.fencingToken() > tokenFromKilledProcess,
                        "the fencing counter lives in the database and survives the kill, so this claim "
                                + "continues the killed process's sequence rather than restarting it");

                // The asymmetry itself: the killed process never gets to "stop writing" on its own --
                // it is dead -- so the store's refusal is the only thing standing between a stale token
                // and a write against an effect someone else is now responsible for.
                assertInstanceOf(ExecutionStoreFailure.FencedOut.class, failureOf(() ->
                        await(store.apply(ai.ravenroot.api.persistence.ExecutionBatch.to(key)
                                .expecting(ai.ravenroot.api.persistence.RevisionExpectation.any())
                                .fencedBy(tokenFromKilledProcess)
                                .apply(new ai.ravenroot.api.persistence.ExecutionTransition.ProcessTransitioned(
                                        ai.ravenroot.api.application.ProcessInstanceStatus.RUNNING))
                                .build()))),
                        "a write presented with the killed process's token must be refused -- accepting "
                                + "it would let a process that no longer exists still mutate state the "
                                + "current owner believes only it can change");
            }
        } catch (AssertionError failure) {
            throw withArtifact(failure, "LEASE/ABANDONED_BY_KILL", snapshot, killed, file, key);
        }
    }

    private static long tokenFrom(List<String> transcript) {
        for (String line : transcript) {
            if (line.startsWith(HoldLeaseUntilKilledInAnotherProcess.CLAIMED)) {
                return Long.parseLong(line.substring(HoldLeaseUntilKilledInAnotherProcess.CLAIMED.length())
                        .split(" ")[0]);
            }
        }
        throw new AssertionError("the killed process never reported a claim; transcript: " + transcript);
    }

    /** Appends the {@code KillMatrixArtifact} location to a failing assertion and rethrows it. */
    private AssertionError withArtifact(AssertionError failure, String cell, Path snapshot, KillResult killed,
                                        Path liveFile, ExecutionKey key) {
        long observed;
        try (var store = new SqliteExecutionStore(snapshot, new MutableClock(EPOCH))) {
            observed = store.load(key).toCompletableFuture().join().revision();
        } catch (RuntimeException unreadable) {
            observed = -1L;
        }
        String artifactMessage = KillMatrixArtifact.write(ARTIFACT_ROOT, cell, snapshot, killed.transcript(),
                rerunCommand(liveFile, key), 0L, observed);
        return new AssertionError(failure.getMessage() + artifactMessage, failure);
    }

    private static List<String> rerunCommand(Path file, ExecutionKey key) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                HoldLeaseUntilKilledInAnotherProcess.class.getName(), file.toString(), key.tenantId(),
                key.processInstanceId().toString(), EPOCH.toString(), TTL.toString());
    }

    /** The child's exit code and full transcript, both needed later if the cell fails and an artifact is written. */
    private record KillResult(int exitCode, List<String> transcript) {
    }

    private KillResult runUntilBoundaryThenKill(Path file, ExecutionKey key) throws Exception {
        var command = new ArrayList<>(rerunCommand(file, key));
        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();

        var transcript = new ArrayList<String>();
        Optional<String> boundary;
        try (var output = new BufferedReader(new InputStreamReader(child.getInputStream(),
                StandardCharsets.UTF_8))) {
            boundary = readUntilBoundary(output, transcript);
        } finally {
            child.destroyForcibly();
        }

        assertTrue(boundary.isPresent(),
                "the child never reached the lease-held boundary; transcript: " + transcript);
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the child survived destroyForcibly()");
        return new KillResult(child.exitValue(), List.copyOf(transcript));
    }

    private static Optional<String> readUntilBoundary(BufferedReader output, List<String> transcript)
            throws Exception {
        String line;
        while ((line = output.readLine()) != null) {
            transcript.add(line);
            if (HoldLeaseUntilKilledInAnotherProcess.AT_BOUNDARY.equals(line)) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
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
