package ai.ravenroot.core.deployment;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.lifecycle.DeploymentLifecycleTarget;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.GraphVersion;
import ai.ravenroot.core.deployment.registry.InMemoryDeploymentRegistry;
import ai.ravenroot.testkit.persistence.MutableClock;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One coordinator, one reconciler, one registry and as many deployment runtimes as a test needs.
 *
 * <p>Assembled here rather than in each test because the wiring itself carries two decisions that
 * every test depends on and none of them should be free to vary: the coordinator and the reconciler
 * share one {@link DeploymentSingleFlight}, so a sweep and a command can never drive the same
 * deployment at once, and both are given the same {@link MutableClock} the registry uses, so lease
 * expiry is decided deterministically instead of by sleeping.</p>
 */
final class CoordinatorFixture {

    static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    static final Duration LEASE_TTL = Duration.ofMinutes(1);
    static final Duration DRAIN_BOUND = Duration.ofSeconds(30);

    final MutableClock clock = new MutableClock(START);
    final DeploymentRegistry registry = new InMemoryDeploymentRegistry(clock);
    final DeploymentSingleFlight singleFlight = new DeploymentSingleFlight();
    final RecordingLifecycleTarget.SharedEngine engine = new RecordingLifecycleTarget.SharedEngine();

    private final Map<DeploymentId, DeploymentLifecycleTarget> runtimes = new HashMap<>();
    private boolean shuttingDown;

    final DeploymentTargets targets = (tenantId, deploymentId) ->
            Optional.ofNullable(runtimes.get(deploymentId));

    DeploymentCoordinator coordinator(String ownerId) {
        return new DeploymentCoordinator(registry, targets, singleFlight, () -> shuttingDown,
                ownerId, LEASE_TTL, DRAIN_BOUND, clock);
    }

    DeploymentReconciler reconciler(String ownerId, String... tenantIds) {
        return new DeploymentReconciler(registry, targets, singleFlight, java.util.List.of(tenantIds),
                ownerId, LEASE_TTL, DRAIN_BOUND, 50, clock);
    }

    /** Declares a service-scoped shutdown, which every later command must be refused against. */
    void shutDown() {
        shuttingDown = true;
    }

    /** Creates a deployment with version 1 and registers a recording runtime for it. */
    RecordingLifecycleTarget deployment(String tenantId, String key) {
        return host(unhostedDeployment(tenantId, key));
    }

    /** Creates a deployment whose runtime this process deliberately does not host. */
    DeploymentId unhostedDeployment(String tenantId, String key) {
        return registry.create(
                new GraphVersion.Content(1, ("graph-" + key).getBytes(StandardCharsets.UTF_8), "alice",
                        clock.instant()),
                new DeploymentRegistry.CreateCommand(tenantId, key, "a".repeat(64)))
                .toCompletableFuture().join().deploymentId();
    }

    /** Registers an already-built runtime, so a test can substitute an instrumented one. */
    void hostProbe(DeploymentId deploymentId, DeploymentLifecycleTarget target) {
        runtimes.put(deploymentId, target);
    }

    /**
     * Registers a runtime that parks inside {@code start} until released, so a test can hold one
     * deployment's single-flight open and prove that a sibling's is not held with it.
     */
    void hostBlocking(DeploymentId deploymentId, CountDownLatch entered, CountDownLatch release) {
        runtimes.put(deploymentId, new DeploymentLifecycleTarget() {
            @Override public CompletionStage<Void> start(long graphVersion, long generation) {
                entered.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        return CompletableFuture.failedFuture(new IllegalStateException("never released"));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(interrupted);
                }
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletionStage<Void> closeAdmission(long generation) {
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletionStage<Void> openAdmission(long generation) {
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletionStage<Void> barrier(long generation) {
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletionStage<Boolean> drain(Duration bound, long generation) {
                return CompletableFuture.completedFuture(true);
            }
            @Override public CompletionStage<Void> terminateDomain(long generation) {
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletionStage<Reading> observe() {
                return CompletableFuture.completedFuture(
                        new Reading(DeploymentRegistry.ObservedKind.READY, 1L, 0));
            }
        });
    }

    /** Registers a runtime for a deployment that had none, so a later sweep can find one. */
    RecordingLifecycleTarget host(DeploymentId deploymentId) {
        var target = new RecordingLifecycleTarget(engine);
        runtimes.put(deploymentId, target);
        ids.put(target, deploymentId);
        return target;
    }

    private final Map<RecordingLifecycleTarget, DeploymentId> ids = new HashMap<>();

    /**
     * Removes this process's runtime for a deployment, standing in for the host that owned it going
     * away: the durable record is untouched and nothing here can publish evidence for it any more.
     */
    void unhost(DeploymentId deploymentId) {
        runtimes.remove(deploymentId);
    }

    /** The identity the registry minted for a runtime created through this fixture. */
    DeploymentId idOf(RecordingLifecycleTarget target) {
        return ids.get(target);
    }

    /** The current durable record for a deployment. */
    DeploymentRegistry.Record record(String tenantId, DeploymentId deploymentId) {
        return registry.get(tenantId, deploymentId).toCompletableFuture().join().orElseThrow();
    }
}
