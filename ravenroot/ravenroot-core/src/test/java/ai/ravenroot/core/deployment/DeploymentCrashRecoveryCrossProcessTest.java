package ai.ravenroot.core.deployment;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.GenerationExpectation;
import ai.ravenroot.api.deployment.registry.GraphVersion;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.persistence.sqlite.SqliteDeploymentRegistry;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 91 criterion 2, out of process: a crash between intent persistence and runtime action is
 * resumed by one current owner, and the action is not duplicated.
 *
 * <h2>Why this test exists beside {@link DeploymentReconcilerTest}</h2>
 * <p>That suite proves the same logical property inside one JVM, where "the owner crashed" is a
 * fixture declining to host a runtime and the store, the lease and the clock are all fields the
 * surviving code can still reach. The criterion is about a process that died, so the load-bearing
 * question — does the surviving owner reach the right conclusion from durable state <em>alone</em> —
 * is the one an in-process test cannot ask. Here a second JVM records the intent, parks inside the
 * command, and is ended by {@code SIGKILL}; this process shares a database file and a marker
 * directory with it and nothing else.</p>
 *
 * <h2>Process death, not machine death</h2>
 * <p>A real {@code SIGKILL} through {@code Process#destroyForcibly()}, which leaves the operating
 * system's page cache intact. Nothing here claims anything about power loss. The kill point is
 * announced by the child rather than sampled, so the window under test is the one named and not
 * whichever one the scheduler happened to produce.</p>
 */
class DeploymentCrashRecoveryCrossProcessTest {

    private static final Instant EPOCH = Instant.parse("2026-09-01T00:00:00Z");
    private static final Duration LEASE_TTL = Duration.ofSeconds(30);
    private static final String TENANT = "acme";

    @TempDir
    Path workspace;

    /**
     * The first half of the crash window: intent is durable, the runtime was never touched. The
     * surviving owner must take ownership over the abandoned lease and perform the action.
     */
    @Test
    void aCrashBeforeTheEffectIsResumedByTheNextOwnerAndTheActionHappensOnce() throws Exception {
        Path database = workspace.resolve("crash-before-effect.db");
        Path markers = workspace.resolve("markers-before");
        var clock = new MutableClock(EPOCH);
        DeploymentId id = createDeployment(database, clock, "before");

        List<String> transcript = runUntilBoundaryThenKill(database, id, markers,
                SubmitLifecycleCommandThenDieInAnotherProcess.BEFORE_EFFECT);

        var runtime = new MarkerLifecycleTarget(markers);
        assertEquals(List.of("start-1-g1"), runtime.attempts(),
                "the dead process reached the runtime once: " + transcript);
        assertEquals(List.of(), runtime.effects(), "and died before the action landed");

        try (var registry = new SqliteDeploymentRegistry(database, clock)) {
            DeploymentRegistry.Record afterCrash = record(registry, id);
            assertEquals(1, afterCrash.generation(), "the decision the dead process made is durable");
            assertEquals(0, afterCrash.observed().observedGeneration(), "its effect is not");
            assertNotNull(afterCrash.lease(), "and the kill released nothing");
            assertEquals(SubmitLifecycleCommandThenDieInAnotherProcess.OWNER, afterCrash.lease().owner());
            DeploymentRegistry.Lease abandoned = afterCrash.lease();

            var reconciler = reconciler(registry, runtime, clock, "owner-here");
            assertInstanceOf(DeploymentReconcileOutcome.NotOwned.class, reconciler.sweepOnce().get(0),
                    "an abandoned lease still excludes, because a holder's death is not something the "
                            + "authority can observe -- only the lapse of its window is");

            clock.set(abandoned.expiresAt().plusSeconds(1));
            List<DeploymentReconcileOutcome> resumed = reconciler.sweepOnce();

            var reconciled = assertInstanceOf(DeploymentReconcileOutcome.Reconciled.class, resumed.get(0));
            assertEquals(1, reconciled.generation());
            assertTrue(reconciled.fence() > abandoned.fence(),
                    "the fencing counter lives in the database, so this process continues the dead "
                            + "process's sequence rather than restarting it");
            assertEquals(List.of("start-1-g1"), runtime.effects(),
                    "the interrupted action was carried out exactly once");
            assertEquals(List.of("start-1-g1", "start-1-g1"), runtime.attempts(),
                    "by a second attempt through the port, which is the shape recovery has");

            assertEquals(List.of(), reconciler.sweepOnce(),
                    "and evidence now closes the window: no later sweep considers this deployment");
            assertEquals(List.of("start-1-g1"), runtime.effects());

            assertFenced(registry, id, abandoned,
                    "a write presented with the killed process's token must be refused forever; "
                            + "accepting it would let a process that no longer exists publish lifecycle "
                            + "state the current owner believes only it can change");
        }
    }

    /**
     * The second half of the same window: the action had already landed and no evidence was published.
     * Nothing durable distinguishes this from the first half, so the identical recovery path runs — and
     * must not perform the action again.
     */
    @Test
    void aCrashAfterTheEffectIsReDrivenWithoutPerformingTheActionTwice() throws Exception {
        Path database = workspace.resolve("crash-after-effect.db");
        Path markers = workspace.resolve("markers-after");
        var clock = new MutableClock(EPOCH);
        DeploymentId id = createDeployment(database, clock, "after");

        List<String> transcript = runUntilBoundaryThenKill(database, id, markers,
                SubmitLifecycleCommandThenDieInAnotherProcess.AFTER_EFFECT);

        var runtime = new MarkerLifecycleTarget(markers);
        assertEquals(List.of("start-1-g1"), runtime.effects(),
                "the dead process performed the action before dying: " + transcript);

        try (var registry = new SqliteDeploymentRegistry(database, clock)) {
            DeploymentRegistry.Record afterCrash = record(registry, id);
            assertEquals(1, afterCrash.generation());
            assertEquals(0, afterCrash.observed().observedGeneration(),
                    "the durable record is identical to the before-effect case, which is exactly why "
                            + "one recovery path has to be correct for both");
            clock.set(afterCrash.lease().expiresAt().plusSeconds(1));

            var resumed = reconciler(registry, runtime, clock, "owner-here").sweepOnce();

            assertInstanceOf(DeploymentReconcileOutcome.Reconciled.class, resumed.get(0));
            assertEquals(List.of("start-1-g1", "start-1-g1"), runtime.attempts(),
                    "the surviving owner did re-drive the interrupted command");
            assertEquals(List.of("start-1-g1"), runtime.effects(),
                    "and the action still happened once, across two operating-system processes");
        }
    }

    // ------------------------------------------------------------------------------------- plumbing

    private DeploymentId createDeployment(Path database, MutableClock clock, String key) {
        try (var registry = new SqliteDeploymentRegistry(database, clock)) {
            return registry.create(
                    new GraphVersion.Content(1, ("graph-" + key).getBytes(StandardCharsets.UTF_8),
                            "alice", clock.instant()),
                    new DeploymentRegistry.CreateCommand(TENANT, key, "a".repeat(64)))
                    .toCompletableFuture().join().deploymentId();
        }
    }

    private DeploymentReconciler reconciler(DeploymentRegistry registry, MarkerLifecycleTarget runtime,
                                            MutableClock clock, String ownerId) {
        return new DeploymentReconciler(registry, (tenant, id) -> Optional.of(runtime),
                new DeploymentSingleFlight(), List.of(TENANT), ownerId, LEASE_TTL,
                Duration.ofSeconds(30), 50, clock);
    }

    private static DeploymentRegistry.Record record(DeploymentRegistry registry, DeploymentId id) {
        return registry.get(TENANT, id).toCompletableFuture().join().orElseThrow();
    }

    private static void assertFenced(DeploymentRegistry registry, DeploymentId id,
                                     DeploymentRegistry.Lease stale, String because) {
        DeploymentRegistry.Record current = record(registry, id);
        CompletionException thrown = assertThrows(CompletionException.class, () -> await(registry.observe(
                new DeploymentRegistry.Observation(DeploymentRegistry.ObservedKind.READY, 1L,
                        current.generation(), current.updatedAt()),
                stale,
                new DeploymentRegistry.Command(TENANT, id, "stale-evidence", "c".repeat(64),
                        RevisionExpectation.exactly(current.revision()), GenerationExpectation.any()))));
        var failure = assertInstanceOf(DeploymentRegistry.RegistryException.class, thrown.getCause());
        assertInstanceOf(DeploymentRegistry.FailureReason.Fenced.class, failure.reason(), because);
    }

    private List<String> runUntilBoundaryThenKill(Path database, DeploymentId id, Path markers,
                                                  String mode) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                SubmitLifecycleCommandThenDieInAnotherProcess.class.getName(), database.toString(),
                TENANT, id.value(), markers.toString(), EPOCH.toString(), LEASE_TTL.toString(), mode);
        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();

        var transcript = new ArrayList<String>();
        boolean reached = false;
        try (var output = new BufferedReader(new InputStreamReader(child.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null) {
                transcript.add(line);
                if (SubmitLifecycleCommandThenDieInAnotherProcess.AT_BOUNDARY.equals(line)) {
                    reached = true;
                    break;
                }
            }
        } finally {
            child.destroyForcibly();
        }

        assertTrue(reached, "the child never reached the announced boundary; transcript: " + transcript);
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the child survived destroyForcibly()");
        assertTrue(child.exitValue() != 0,
                "a SIGKILLed process must not report a clean exit; got " + child.exitValue());
        return List.copyOf(transcript);
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
