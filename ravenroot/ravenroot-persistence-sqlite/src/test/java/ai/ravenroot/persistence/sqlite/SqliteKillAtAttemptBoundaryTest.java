package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptCompletion;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.testkit.persistence.KillMatrixArtifact;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERS-04's documented requirements, executed: <em>process terminated at every transition and restarted
 * without loss</em>.
 *
 * <p>Eight forked JVMs — the four transitions PERS-04 promises to persist (intention, dispatch, the
 * disposition of ambiguity, result) times the two sides of the commit. Each child is driven to a
 * named boundary and killed there with a real {@code SIGKILL} via {@link Process#destroyForcibly()},
 * which it cannot catch, block or handle: no shutdown hook runs, no {@code finally} executes, no
 * connection closes, and the kernel rather than SQLite releases the file locks.</p>
 *
 * <h2>What this proves, stated as narrowly as it is true</h2>
 * <p><strong>Process death at each transition. Not machine death.</strong> A {@code SIGKILL} leaves
 * the operating system's page cache intact, so a commit that reached the page cache but was never
 * fsynced still reaches the file — which means every assertion below would pass under
 * {@link SqliteStoreConfig.SynchronousMode#FULL} <em>and</em> under a reduced synchronous mode. This
 * suite therefore cannot distinguish them and must not be cited as evidence that it can. That limit
 * is precisely why {@code FULL} is the default rather than something a kill test was trusted to
 * catch, and it is written here rather than left to be inferred by whoever reads a green build.</p>
 *
 * <p>What it does prove is the property the recovery loop depends on and cannot verify for itself:
 * every one of these transitions is atomic with respect to an abrupt death, so after a restart the
 * attempt is in exactly one of the two states the loop knows how to decide about — never in a torn
 * one, and never silently gone.</p>
 */
class SqliteKillAtAttemptBoundaryTest {

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

    @TestFactory
    Stream<DynamicTest> everyAttemptTransitionIsAtomicAcrossAProcessKillOnBothSidesOfItsCommit() {
        return Stream.of(KillAtAttemptBoundary.Stage.values())
                .flatMap(stage -> Stream.of(KillAtAttemptBoundary.BEFORE_COMMIT,
                                KillAtAttemptBoundary.AFTER_COMMIT)
                        .map(mode -> DynamicTest.dynamicTest(stage + " killed " + mode + " commit",
                                () -> killAt(stage, mode))));
    }

    private void killAt(KillAtAttemptBoundary.Stage stage, String mode) throws Exception {
        Path file = databaseDirectory.resolve(stage + "-" + mode + ".db");
        long revisionBefore;
        try (var store = openAt(file)) {
            revisionBefore = KillAtAttemptBoundary.driveToPreconditionOf(store, stage);
        }

        KillResult killed = runUntilBoundaryThenKill(file, stage, mode, revisionBefore);
        assertTrue(killed.exitCode() != 0, "a SIGKILLed process must not report a clean exit; got "
                + killed.exitCode());
        Path snapshot = KillMatrixArtifact.snapshot(file);

        try {
            try (var recovered = openAt(file)) {
                StoredProcessInstance reloaded = await(recovered.load(KillAtAttemptBoundary.KEY));
                if (KillAtAttemptBoundary.BEFORE_COMMIT.equals(mode)) {
                    assertEquals(revisionBefore, reloaded.revision(),
                            "an interrupted write must leave the revision exactly where the last committed "
                                    + "batch left it");
                    assertPreconditionIntact(recovered, stage, reloaded);
                } else {
                    assertTrue(reloaded.revision() > revisionBefore,
                            "the committed batch advanced the revision and the advance is durable");
                    assertStageApplied(recovered, stage, reloaded);
                }
            }
        } catch (AssertionError failure) {
            throw withArtifact(failure, stage + "/" + mode, snapshot, killed, file, stage, mode, revisionBefore);
        }
    }

    /** Appends the {@code KillMatrixArtifact} location to a failing assertion and rethrows it. */
    private AssertionError withArtifact(AssertionError failure, String cell, Path snapshot, KillResult killed,
                                        Path liveFile, KillAtAttemptBoundary.Stage stage, String mode,
                                        long expectedRevision) {
        long observed = Fixtures.bestEffortRevision(snapshot,
                Clock.fixed(KillAtAttemptBoundary.NOW, ZoneOffset.UTC), KillAtAttemptBoundary.KEY);
        String artifactMessage = KillMatrixArtifact.write(ARTIFACT_ROOT, cell, snapshot, killed.transcript(),
                rerunCommand(liveFile, stage, mode, expectedRevision), expectedRevision, observed);
        return new AssertionError(failure.getMessage() + artifactMessage, failure);
    }

    private static List<String> rerunCommand(Path file, KillAtAttemptBoundary.Stage stage, String mode,
                                              long expectedRevision) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                KillAtAttemptBoundary.class.getName(), file.toString(), stage.name(), mode,
                Long.toString(expectedRevision));
    }

    /** After a kill before the commit, the world is exactly the stage's precondition and nothing more. */
    private void assertPreconditionIntact(SqliteExecutionStore store, KillAtAttemptBoundary.Stage stage,
                                          StoredProcessInstance reloaded) {
        switch (stage) {
            case INTENTION -> {
                assertTrue(reloaded.state().traversals().get(KillAtAttemptBoundary.TRAVERSAL_ID)
                                .invocations().isEmpty(),
                        "an uncommitted intention leaves no invocation and therefore no claimable work");
                assertTrue(await(store.claimPendingWork(KillAtAttemptBoundary.TENANT, "worker-1", 10,
                        KillAtAttemptBoundary.TTL)).isEmpty());
            }
            case DISPATCH -> {
                assertEquals(NodeAttemptStatus.SCHEDULED, attempt(reloaded).status(),
                        "an uncommitted dispatch leaves the attempt provably not started, which is the "
                                + "case recovery may dispatch freely");
                // And it is still claimable, which is the whole point: nothing was lost.
                assertEquals(1, await(store.claimPendingWork(KillAtAttemptBoundary.TENANT, "worker-1", 10,
                        KillAtAttemptBoundary.TTL)).size());
            }
            case PARK -> {
                assertEquals(NodeAttemptStatus.RUNNING, attempt(reloaded).status());
                assertNull(attempt(reloaded).parkCause(), "an uncommitted park explains nothing");
            }
            case RESULT -> assertEquals(NodeAttemptStatus.RUNNING, attempt(reloaded).status(),
                    "an uncommitted result leaves the attempt ambiguous, which recovery will park — "
                            + "the conservative direction, and the correct one");
        }
    }

    /** After a kill immediately following the commit, the stage's write is present in full. */
    private void assertStageApplied(SqliteExecutionStore store, KillAtAttemptBoundary.Stage stage,
                                    StoredProcessInstance reloaded) {
        switch (stage) {
            case INTENTION -> {
                assertEquals(NodeAttemptStatus.SCHEDULED, attempt(reloaded).status());
                List<PendingWork> claimable = await(store.claimPendingWork(KillAtAttemptBoundary.TENANT,
                        "worker-1", 10, KillAtAttemptBoundary.TTL));
                assertEquals(1, claimable.size(),
                        "a committed intention is claimable work, which is what makes the accepted "
                                + "execution recoverable at all");
                assertEquals(KillAtAttemptBoundary.ATTEMPT_ID,
                        ((PendingWork.AttemptDispatch) claimable.get(0)).attemptId());
            }
            case DISPATCH -> assertEquals(NodeAttemptStatus.RUNNING, attempt(reloaded).status(),
                    "the intention to act survives the death of the actor, which is the ordering the "
                            + "whole ambiguity rule rests on");
            case PARK -> {
                assertEquals(NodeAttemptStatus.PARKED, attempt(reloaded).status());
                assertEquals(KillAtAttemptBoundary.PARK_CAUSE, attempt(reloaded).parkCause());
                assertTrue(await(store.claimPendingWork(KillAtAttemptBoundary.TENANT, "worker-1", 10,
                                KillAtAttemptBoundary.TTL)).isEmpty(),
                        "a parked attempt has left the claim loop and a restart does not put it back");
            }
            case RESULT -> {
                assertEquals(NodeAttemptStatus.COMPLETED, attempt(reloaded).status());
                assertEquals(NodeAttemptCompletion.SUCCEEDED, attempt(reloaded).completion());
                assertTrue(await(store.claimPendingWork(KillAtAttemptBoundary.TENANT, "worker-1", 10,
                                KillAtAttemptBoundary.TTL)).isEmpty(),
                        "completed work is not handed out again after a restart");
            }
        }
    }

    private static NodeAttempt attempt(StoredProcessInstance stored) {
        return stored.state().traversals().get(KillAtAttemptBoundary.TRAVERSAL_ID)
                .invocations().get(KillAtAttemptBoundary.INVOCATION_ID).attempts().getLast();
    }

    private SqliteExecutionStore openAt(Path file) {
        return new SqliteExecutionStore(file, Clock.fixed(KillAtAttemptBoundary.NOW, ZoneOffset.UTC));
    }

    /**
     * Runs the child, waits for its announcement, kills it and returns its exit code.
     *
     * <p>Every step is asserted rather than assumed: the announcement must arrive, the child must
     * actually die, and it must not have printed
     * {@link KillAtAttemptBoundary#COMPLETED}. A kill test whose child finished normally would
     * otherwise report a pass having tested nothing.</p>
     */
    /** The child's exit code and full transcript, both needed later if the cell fails and an artifact is written. */
    private record KillResult(int exitCode, List<String> transcript) {
    }

    private KillResult runUntilBoundaryThenKill(Path file, KillAtAttemptBoundary.Stage stage, String mode,
                                                long expectedRevision) throws Exception {
        var command = new ArrayList<>(rerunCommand(file, stage, mode, expectedRevision));
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
                "the child never reached the " + stage + "/" + mode + " boundary; transcript: " + transcript);
        assertFalse(transcript.contains(KillAtAttemptBoundary.COMPLETED),
                "the child completed its batch instead of parking at the boundary, so nothing was killed "
                        + "and this case would have proved nothing; transcript: " + transcript);
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the child survived destroyForcibly()");
        return new KillResult(child.exitValue(), List.copyOf(transcript));
    }

    private static Optional<String> readUntilBoundary(BufferedReader output, List<String> transcript)
            throws Exception {
        String line;
        while ((line = output.readLine()) != null) {
            transcript.add(line);
            if (KillAtAttemptBoundary.AT_BOUNDARY.equals(line)) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
