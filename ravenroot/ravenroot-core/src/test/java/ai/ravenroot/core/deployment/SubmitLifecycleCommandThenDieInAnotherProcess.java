package ai.ravenroot.core.deployment;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.lifecycle.DeploymentCommandOutcome;
import ai.ravenroot.api.deployment.lifecycle.DeploymentLifecycleTarget;
import ai.ravenroot.api.deployment.lifecycle.LifecycleCommand;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.GenerationExpectation;
import ai.ravenroot.persistence.sqlite.SqliteDeploymentRegistry;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Submits one lifecycle command from a genuinely separate operating-system process, parks at a chosen
 * point inside the command, and waits to be killed — for
 * {@link DeploymentCrashRecoveryCrossProcessTest}.
 *
 * <h2>Why a second JVM, and why it is never allowed to exit</h2>
 * <p>Issue 91 criterion 2 is about a crash between intent persistence and runtime action, resumed by
 * one current owner. Inside one JVM, "the owner died" can only ever be a fixture pretending: the
 * store is a field the surviving code can still reach, the lease lives in the same heap, and the
 * clock is a reference both halves share. Here the two parties share a database file and a marker
 * directory and nothing else, and the process is ended by {@code SIGKILL} — so the lease is abandoned
 * without being released, exactly as a real crash abandons it, and the surviving owner has to reach
 * the same conclusion from durable state alone.</p>
 *
 * <p>Deliberately not try-with-resources, and deliberately parked rather than exited: closing the
 * registry would give the adapter a chance to run code, and an exit would give the JVM one. Only the
 * kill ends this process, which is what makes the parent's assertions about a crash rather than about
 * a shutdown.</p>
 *
 * <h2>Two boundaries, because the crash window has two halves</h2>
 * <ul>
 *   <li>{@link #BEFORE_EFFECT} — the intent is durable and the runtime has not been touched. The
 *       surviving owner must perform the action.</li>
 *   <li>{@link #AFTER_EFFECT} — the intent is durable and the action has already happened, with no
 *       evidence published. The surviving owner must re-drive it and must not perform it again.</li>
 * </ul>
 * <p>Nothing durable distinguishes the two, which is the point: one recovery path has to be correct
 * for both.</p>
 */
public final class SubmitLifecycleCommandThenDieInAnotherProcess {

    static final String BEFORE_EFFECT = "BEFORE_EFFECT";
    static final String AFTER_EFFECT = "AFTER_EFFECT";
    static final String AT_BOUNDARY = "AT_BOUNDARY";
    static final String OWNER = "owner-in-another-process";

    private SubmitLifecycleCommandThenDieInAnotherProcess() {
    }

    /**
     * Runs the child.
     *
     * @param args database file, tenant id, deployment id, marker directory, instant, lease ttl, mode.
     * @throws Exception when the park is interrupted, which only a failing parent can cause.
     */
    public static void main(String[] args) throws Exception {
        Path databaseFile = Path.of(args[0]);
        String tenantId = args[1];
        DeploymentId deploymentId = DeploymentId.of(args[2]);
        Path markers = Path.of(args[3]);
        Instant now = Instant.parse(args[4]);
        Duration leaseTtl = Duration.parse(args[5]);
        String mode = args[6];

        var registry = new SqliteDeploymentRegistry(databaseFile, Clock.fixed(now, ZoneOffset.UTC));
        var runtime = new ParkingTarget(new MarkerLifecycleTarget(markers), mode);
        var coordinator = new DeploymentCoordinator(registry,
                (tenant, id) -> Optional.of(runtime), new DeploymentSingleFlight(),
                ServiceShutdownIntent.RUNNING, OWNER, leaseTtl, Duration.ofSeconds(30),
                Clock.fixed(now, ZoneOffset.UTC));

        DeploymentCommandOutcome outcome = coordinator.submit(tenantId, deploymentId,
                new LifecycleCommand.Start("cross-process-start", 1,
                        DeploymentRegistry.UpdateStrategy.STOP_FIRST),
                GenerationExpectation.exactly(0));

        // Only reachable if the park above failed to hold, which would mean this cell tested nothing.
        System.out.println("UNEXPECTEDLY_COMPLETED " + outcome);
        System.out.flush();
    }

    /** A runtime that announces the chosen boundary and then never returns from it. */
    private record ParkingTarget(MarkerLifecycleTarget delegate, String mode)
            implements DeploymentLifecycleTarget {

        @Override
        public CompletionStage<Void> start(long graphVersion, long deploymentGeneration) {
            String marker = "start-" + graphVersion + "-g" + deploymentGeneration;
            delegate.record(marker);
            if (AFTER_EFFECT.equals(mode)) {
                delegate.apply(marker);
            }
            System.out.println(AT_BOUNDARY);
            System.out.flush();
            park();
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletionStage<Void> closeAdmission(long generation) { return park(); }
        @Override public CompletionStage<Void> openAdmission(long generation) { return park(); }
        @Override public CompletionStage<Void> barrier(long generation) { return park(); }
        @Override public CompletionStage<Boolean> drain(Duration bound, long generation) {
            park();
            return CompletableFuture.completedFuture(true);
        }
        @Override public CompletionStage<Void> terminateDomain(long generation) { return park(); }
        @Override public CompletionStage<Reading> observe() { return delegate.observe(); }

        private static CompletionStage<Void> park() {
            try {
                // Long enough that a parent which failed to kill this process hits its own timeout and
                // reports that, rather than this process quietly exiting and inventing a pass.
                Thread.sleep(Duration.ofMinutes(10));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(null);
        }
    }
}
