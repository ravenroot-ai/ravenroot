package ai.ravenroot.server.qa03;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.core.recovery.ExecutionRecoveryService;
import ai.ravenroot.core.recovery.RecoveryDispatcher;
import ai.ravenroot.core.recovery.RecoveryOutcome;
import ai.ravenroot.core.recovery.RepeatabilityDeclarations;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crash/replay matrix's engine-adapter cell.
 *
 * <p>Every other cell in this matrix (in {@code ravenroot-persistence-sqlite}) kills a bare store: the
 * {@code RUNNING} transition under test is written directly by the test process, and no engine, actor
 * or {@code send} ever runs. That leaves zero kill coverage on the actual production dispatch path.
 * This cell forks a process that wires a real {@link ai.ravenroot.pekko.PekkoExecutionEngine} to a
 * real {@link SqliteExecutionStore} through {@link ai.ravenroot.core.runtime.GraphRunner}, kills it
 * only once a real actor has actually started running a real node, and then proves that the recovery
 * loop -- {@link ExecutionRecoveryService}, unmodified, wired the way production wires it -- treats
 * what the real engine adapter left behind exactly as the store-only cells assume it will.
 *
 * <h2>Coverage is Pekko, not Akka</h2>
 * <p>Same declared scope as the rest of the matrix: Akka is not buildable in this environment,
 * so "at least one engine adapter" is Pekko here. See {@code CrashReplayMatrixScopeTest} in
 * {@code ravenroot-persistence-sqlite} for the matrix-wide statement; this class does not repeat it as
 * a scanned invariant because it lives in a different module (Pekko is a test-scoped dependency of
 * {@code ravenroot-server}, and cannot be a dependency of {@code ravenroot-persistence-sqlite} at all).</p>
 *
 * <h2>Process death, not machine death; no seed</h2>
 * <p>As with every other cell: a real {@code SIGKILL} via {@link Process#destroyForcibly()}, which
 * leaves the OS page cache intact, so this cannot distinguish {@code synchronous=FULL} from a reduced
 * mode. The kill point is announced (the child prints {@link PekkoEngineDispatchKillBoundary#AT_BOUNDARY}
 * only once the engine has actually dispatched the node), not sampled, so there is nothing for a seed
 * to vary.</p>
 */
class PekkoEngineDispatchKillTest {

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
    void aNodeARealEngineActuallyDispatchedIsRecoveredExactlyLikeADirectlyWrittenRunningAttempt()
            throws Exception {
        Path file = databaseDirectory.resolve("engine-adapter-kill.db");
        long revisionAtPrecondition = driveToRunningPrecondition(file);

        KillResult killed = runUntilBoundaryThenKill(file, revisionAtPrecondition);
        assertTrue(killed.exitCode() != 0, "a SIGKILLed process must not report a clean exit; got "
                + killed.exitCode());
        Path snapshot = KillMatrixArtifact.snapshot(file);

        try {
            // Opened at a time strictly after the lease's TTL from the same NOW the child claimed it
            // at: a lease held by a worker that is provably dead (this test just killed it) still
            // reads as "held and not yet expired" against the exact instant it was claimed, and
            // claimPendingWork correctly skips such an instance -- ExecutionRecorder's own class
            // javadoc names that guard by design. A recovery sweep in production never runs at the
            // instant of the crash either; it runs after the lease has had time to lapse.
            try (var recovered = openAfterLeaseExpiry(file)) {
                StoredProcessInstance reloaded = await(recovered.load(PekkoEngineDispatchKillBoundary.KEY));
                assertTrue(reloaded.revision() > revisionAtPrecondition,
                        "the engine adapter's dispatch committed the attempt's RUNNING transition, and "
                                + "that commit is durable -- GraphRunner.nodeStarted() is synchronous and "
                                + "returns before engine.send() is ever called");

                NodeInvocation invocation = hangInvocation(reloaded);
                NodeAttempt attempt = invocation.attempts().getLast();
                assertEquals(NodeAttemptStatus.RUNNING, attempt.status(),
                        "the real engine actually sent this node and the outcome is genuinely unknown -- "
                                + "the child died mid-behavior, which is exactly PERS-04's ambiguous case");

                // The child's whole actor system died with it. Recovery consults only the store, exactly
                // as production's recovery loop does. RecoveryDispatcher.NONE is the production wiring,
                // so an ambiguous attempt must park rather than silently hang.
                var recovery = new ExecutionRecoveryService(recovered, List.of(PekkoEngineDispatchKillBoundary.TENANT),
                        "qa03-recovery-sweep", 10, Duration.ofSeconds(30),
                        RepeatabilityDeclarations.NONE_DECLARED, RecoveryDispatcher.NONE);
                List<RecoveryOutcome> outcomes = recovery.sweepOnce();
                assertEquals(1, outcomes.size(),
                        "exactly the one attempt the engine adapter left RUNNING must be claimable");
                RecoveryOutcome.Parked parked = assertInstanceOf(RecoveryOutcome.Parked.class, outcomes.get(0),
                        "an attempt a real engine actually dispatched, with no repeatability declared, "
                                + "must park -- not silently re-dispatch and not silently vanish");
                assertEquals(attempt.attemptId(), parked.attemptId());
                assertTrue(parked.cause().contains("dispatched with unknown outcome"),
                        "the parked cause must say why a human is being asked to decide: " + parked.cause());

                StoredProcessInstance afterSweep = await(recovered.load(PekkoEngineDispatchKillBoundary.KEY));
                NodeAttempt attemptAfterSweep = hangInvocation(afterSweep).attempts().getLast();
                assertEquals(NodeAttemptStatus.PARKED, attemptAfterSweep.status(),
                        "the park recovery decided is itself durable");
                assertTrue(await(recovered.claimPendingWork(PekkoEngineDispatchKillBoundary.TENANT,
                                "qa03-recovery-sweep", 10, Duration.ofSeconds(30))).isEmpty(),
                        "a parked attempt has left the claim loop; a second sweep must not find it again");
            }
        } catch (AssertionError failure) {
            throw withArtifact(failure, "ENGINE_ADAPTER/PEKKO_DISPATCH", snapshot, killed, file,
                    revisionAtPrecondition);
        }
    }

    private static NodeInvocation hangInvocation(StoredProcessInstance stored) {
        return stored.state().traversals().get(PekkoEngineDispatchKillBoundary.TRAVERSAL_ID).invocations()
                .values().stream()
                .filter(invocation -> PekkoEngineDispatchKillBoundary.HANG_NODE_ID.equals(invocation.nodeId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no invocation of node '"
                        + PekkoEngineDispatchKillBoundary.HANG_NODE_ID + "' was recorded"));
    }

    /**
     * Creates the process instance and brings it to {@code RUNNING} with one {@code RUNNING} traversal
     * -- the exact in-memory starting state {@code GraphRunner.ExecutionState}'s constructor assumes,
     * which the forked child's {@code ExecutionRecorder} then writes on top of under the fence.
     */
    private long driveToRunningPrecondition(Path file) {
        try (var store = openAt(file)) {
            var key = PekkoEngineDispatchKillBoundary.KEY;
            var traversalId = PekkoEngineDispatchKillBoundary.TRAVERSAL_ID;
            var accepted = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                    Map.of(traversalId, new Traversal(traversalId, "start", TraversalStatus.ACCEPTED, Map.of())));
            long revision = await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.notPresent())
                    .apply(new ExecutionTransition.ProcessCreated(accepted, new GraphVersionPin("graph-v1")))
                    .build())).revision();
            return await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(revision))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                    .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                    .build())).revision();
        }
    }

    private SqliteExecutionStore openAt(Path file) {
        return new SqliteExecutionStore(file, Clock.fixed(PekkoEngineDispatchKillBoundary.NOW, ZoneOffset.UTC));
    }

    /** Opens the killed database at an instant strictly after the child's lease TTL has lapsed. */
    private SqliteExecutionStore openAfterLeaseExpiry(Path file) {
        var afterExpiry = PekkoEngineDispatchKillBoundary.NOW
                .plus(PekkoEngineDispatchKillBoundary.LEASE_TTL).plusSeconds(1);
        return new SqliteExecutionStore(file, Clock.fixed(afterExpiry, ZoneOffset.UTC));
    }

    /** Appends the {@code KillMatrixArtifact} location to a failing assertion and rethrows it. */
    private AssertionError withArtifact(AssertionError failure, String cell, Path snapshot, KillResult killed,
                                        Path liveFile, long expectedRevision) {
        long observed;
        try (var store = new SqliteExecutionStore(snapshot, Clock.fixed(PekkoEngineDispatchKillBoundary.NOW,
                ZoneOffset.UTC))) {
            observed = store.load(PekkoEngineDispatchKillBoundary.KEY).toCompletableFuture().join().revision();
        } catch (RuntimeException unreadable) {
            observed = -1L;
        }
        String artifactMessage = KillMatrixArtifact.write(ARTIFACT_ROOT, cell, snapshot, killed.transcript(),
                rerunCommand(liveFile, expectedRevision), expectedRevision, observed);
        return new AssertionError(failure.getMessage() + artifactMessage, failure);
    }

    private static List<String> rerunCommand(Path file, long expectedRevision) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                PekkoEngineDispatchKillBoundary.class.getName(), file.toString(), Long.toString(expectedRevision));
    }

    /** The child's exit code and full transcript, both needed later if the cell fails and an artifact is written. */
    private record KillResult(int exitCode, List<String> transcript) {
    }

    /**
     * Runs the child, waits for it to announce the boundary, kills it and returns its exit code.
     *
     * <p>Every step is asserted rather than assumed: the announcement must arrive, the child must
     * actually die, and it must not have printed {@link PekkoEngineDispatchKillBoundary#COMPLETED}. A
     * kill test whose child finished normally would otherwise report a pass having tested nothing.</p>
     */
    private KillResult runUntilBoundaryThenKill(Path file, long expectedRevision) throws Exception {
        var command = new ArrayList<>(rerunCommand(file, expectedRevision));
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
                "the child never reached the engine-dispatch boundary; transcript: " + transcript);
        assertFalse(transcript.contains(PekkoEngineDispatchKillBoundary.COMPLETED),
                "the child completed instead of hanging at the dispatched node, so nothing was killed "
                        + "and this cell would have proved nothing; transcript: " + transcript);
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the child survived destroyForcibly()");
        return new KillResult(child.exitValue(), List.copyOf(transcript));
    }

    private static Optional<String> readUntilBoundary(BufferedReader output, List<String> transcript)
            throws Exception {
        String line;
        while ((line = output.readLine()) != null) {
            transcript.add(line);
            if (PekkoEngineDispatchKillBoundary.AT_BOUNDARY.equals(line)) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
