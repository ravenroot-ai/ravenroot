package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.testkit.persistence.KillMatrixArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERS-03 requires this kill test to use a real {@code SIGKILL} of a real process at the two
 * boundaries where atomicity is actually decided.
 *
 * <h2>Why a forked process and not an in-JVM simulation</h2>
 * <p>A test that closes the store, or halts the JVM from inside itself, still gives the adapter a
 * chance to run code. {@link Process#destroyForcibly()} on a POSIX host sends {@code SIGKILL}, which
 * the child cannot catch, block or handle: no shutdown hook runs, no {@code finally} executes, no
 * connection is closed, and the file locks are released by the kernel rather than by SQLite. That is
 * the failure mode this adapter claims to survive, so it is the one worth reproducing.</p>
 *
 * <h2>Why the boundary is chosen rather than sampled</h2>
 * <p>Killing a writer "at some point during a batch" almost always lands in statement execution and
 * essentially never in the microseconds around {@code COMMIT}. Such a test reliably demonstrates that
 * an unfinished transaction leaves no trace — which nobody doubts — while never once exercising the
 * instant where the answer could differ. The child parks at exactly the boundary named and announces
 * it, so the kill is placed rather than hoped for.</p>
 *
 * <h2>What each direction proves</h2>
 * <ul>
 *   <li><strong>Before the commit</strong>: the batch is fully written into the transaction and
 *   touches three tables. After recovery, <em>none</em> of it is visible — not the transition, not the
 *   timer, not the idempotency record. That is {@code TRANSACTIONAL_BATCH} against an interrupted
 *   write, and the three tables matter: a store that lost atomicity across tables would surface here
 *   as a timer with no transition, which no single-table assertion could see.</li>
 *   <li><strong>After the commit</strong>: the same batch, killed with nothing between {@code COMMIT}
 *   returning and the signal. After recovery all three parts are present. That is {@code DURABLE}, and
 *   it is only meaningful because {@link SqliteStoreConfig.SynchronousMode#FULL} means the commit was
 *   fsynced rather than merely handed to the operating system.</li>
 * </ul>
 *
 * <p>What it does <em>not</em> prove: durability against host failure. A {@code SIGKILL} leaves the
 * page cache intact, so an unsynced commit would still reach the file and this test would pass under
 * {@code synchronous=NORMAL} too. That limit is why {@code FULL} is the default rather than something
 * a kill test was trusted to catch.</p>
 */
class SqliteKillAtCommitBoundaryTest {

    /**
     * Where a failing cell's {@code KillMatrixArtifact} is written. Deliberately <b>not</b>
     * {@code databaseDirectory}: that field is a JUnit {@code @TempDir}, deleted immediately after
     * the test method returns by default, which would delete the artifact at the exact moment a
     * reader needs it. {@code target/} survives until the next {@code mvn clean}, the same lifetime
     * surefire's own reports get.
     */
    private static final Path ARTIFACT_ROOT = Path.of("target");

    @TempDir
    Path databaseDirectory;

    @Test
    void aBatchKilledBeforeItsCommitLeavesNoPartOfItselfBehind() throws Exception {
        Path file = databaseDirectory.resolve("kill-before.db");
        long createdRevision = createInstance(file);
        String mode = KillAtCommitBoundary.BEFORE_COMMIT;

        KillResult killed = runUntilBoundaryThenKill(file, mode, createdRevision);
        assertTrue(killed.exitCode() != 0, "a SIGKILLed process must not report a clean exit; got "
                + killed.exitCode());
        Path snapshot = KillMatrixArtifact.snapshot(file);

        try {
            try (var recovered = openAt(file)) {
                StoredProcessInstance reloaded = await(recovered.load(KillAtCommitBoundary.KEY));
                assertEquals(createdRevision, reloaded.revision(),
                        "an interrupted write must leave the revision exactly where the last committed "
                                + "batch left it");
                assertEquals(ProcessInstanceStatus.ACCEPTED, reloaded.state().status());
                assertEquals(TraversalStatus.ACCEPTED,
                        reloaded.state().traversals().get(KillAtCommitBoundary.TRAVERSAL_ID).status());

                assertTrue(await(recovered.claimDueTimers(KillAtCommitBoundary.TENANT, "worker-1", 10,
                        Duration.ofSeconds(30))).isEmpty(), "the timer belonged to the uncommitted batch");
                assertEquals(0L, await(recovered.idempotencyRecordCount(KillAtCommitBoundary.TENANT)),
                        "the idempotency record belonged to the uncommitted batch; a surviving record "
                                + "would answer a later replay for work that never happened");
            }
        } catch (AssertionError failure) {
            throw withArtifact(failure, "COMMIT/" + mode, snapshot, killed, file, mode, createdRevision);
        }
    }

    @Test
    void aBatchKilledImmediatelyAfterItsCommitSurvivesInFull() throws Exception {
        Path file = databaseDirectory.resolve("kill-after.db");
        long createdRevision = createInstance(file);
        String mode = KillAtCommitBoundary.AFTER_COMMIT;

        KillResult killed = runUntilBoundaryThenKill(file, mode, createdRevision);
        assertTrue(killed.exitCode() != 0, "a SIGKILLed process must not report a clean exit; got "
                + killed.exitCode());
        Path snapshot = KillMatrixArtifact.snapshot(file);

        try {
            try (var recovered = openAt(file)) {
                StoredProcessInstance reloaded = await(recovered.load(KillAtCommitBoundary.KEY));
                assertTrue(reloaded.revision() > createdRevision,
                        "the committed batch advanced the revision and the advance is durable");
                assertEquals(ProcessInstanceStatus.RUNNING, reloaded.state().status());

                var due = await(recovered.claimDueTimers(KillAtCommitBoundary.TENANT, "worker-1", 10,
                        Duration.ofSeconds(30)));
                assertEquals(1, due.size(), "the committed timer record survives");
                assertEquals(KillAtCommitBoundary.TIMER_ID, due.get(0).workItemId());

                assertEquals(1L, await(recovered.idempotencyRecordCount(KillAtCommitBoundary.TENANT)));
                assertTrue(await(recovered.lookupIdempotency(KillAtCommitBoundary.TENANT,
                        KillAtCommitBoundary.IDEMPOTENCY_KEY, KillAtCommitBoundary.NOW)).isPresent(),
                        "the record must be answerable, or the caller's retry re-executes committed work");
            }
        } catch (AssertionError failure) {
            throw withArtifact(failure, "COMMIT/" + mode, snapshot, killed, file, mode, createdRevision);
        }
    }

    /** Appends the {@code KillMatrixArtifact} location to a failing assertion and rethrows it. */
    private AssertionError withArtifact(AssertionError failure, String cell, Path snapshot, KillResult killed,
                                        Path liveFile, String mode, long expectedRevision) {
        long observed = Fixtures.bestEffortRevision(snapshot, Clock.fixed(KillAtCommitBoundary.NOW, ZoneOffset.UTC),
                KillAtCommitBoundary.KEY);
        String artifactMessage = KillMatrixArtifact.write(ARTIFACT_ROOT, cell, snapshot, killed.transcript(),
                rerunCommand(liveFile, mode, expectedRevision), expectedRevision, observed);
        return new AssertionError(failure.getMessage() + artifactMessage, failure);
    }

    private static List<String> rerunCommand(Path file, String mode, long expectedRevision) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                KillAtCommitBoundary.class.getName(), file.toString(), mode, Long.toString(expectedRevision));
    }

    private long createInstance(Path file) {
        try (var store = openAt(file)) {
            return await(store.apply(Fixtures.creationBatch(KillAtCommitBoundary.KEY,
                    KillAtCommitBoundary.TRAVERSAL_ID))).revision();
        }
    }

    private SqliteExecutionStore openAt(Path file) {
        return new SqliteExecutionStore(file,
                Clock.fixed(KillAtCommitBoundary.NOW, ZoneOffset.UTC));
    }

    /** The child's exit code and full transcript, both needed later if the cell fails and an artifact is written. */
    private record KillResult(int exitCode, List<String> transcript) {
    }

    /**
     * Runs the child, waits for it to announce the boundary, kills it and returns its exit code.
     *
     * <p>Every step is asserted rather than assumed: the announcement must arrive, the child must
     * actually die, and it must not have printed {@link KillAtCommitBoundary#COMPLETED}. A kill test
     * whose child finished normally would otherwise report a pass having tested nothing.</p>
     */
    private KillResult runUntilBoundaryThenKill(Path file, String mode, long expectedRevision) throws Exception {
        var command = new ArrayList<>(rerunCommand(file, mode, expectedRevision));
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
                "the child never reached the " + mode + " boundary; transcript: " + transcript);
        assertFalse(transcript.contains(KillAtCommitBoundary.COMPLETED),
                "the child completed its batch instead of parking at the boundary, so nothing was "
                        + "killed and this test would have proved nothing; transcript: " + transcript);
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the child survived destroyForcibly()");
        return new KillResult(child.exitValue(), List.copyOf(transcript));
    }

    private static Optional<String> readUntilBoundary(BufferedReader output, List<String> transcript)
            throws Exception {
        String line;
        while ((line = output.readLine()) != null) {
            transcript.add(line);
            if (KillAtCommitBoundary.AT_BOUNDARY.equals(line)) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
