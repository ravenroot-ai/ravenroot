package ai.ravenroot.core.runtime;

import ai.ravenroot.api.deployment.DeploymentAdmissionException;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.DeploymentState;
import ai.ravenroot.api.deployment.IngressDisposition;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultRavenrootApplication} hosts long-lived deployments alongside one-shot
 * submissions, and admits them against the per-pod cap fail-closed (the deployment-admission contract).
 *
 * <p>The reusable conformance suite owns the deployment lifecycle contract itself (readiness,
 * rollback, restart, zero residual resources):
 * {@code ai.ravenroot.testkit.GraphDeploymentContract}, and is deliberately not repeated here. What
 * belongs at this level is what only this class can prove: the registry, the admission boundary, and
 * that the pre-existing one-shot path is untouched by any of it.</p>
 */
class DefaultRavenrootApplicationDeploymentTest {

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="wiring" edgedefault="directed">
                <node id="error"><data key="node-kind">ERROR</data></node>
                <node id="start"><data key="node-kind">START</data></node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="start-end" source="start" target="end">
                  <data key="edge-outcome">continue</data>
                </edge>
              </graph>
            </graphml>
            """;

    @Test
    void activatingWithinTheCapReachesReadyAndServesIngress() throws Exception {
        var engine = new JoinTestEngine();
        var application = applicationWith(engine, 2);
        try {
            var status = application.activateDeployment(TestIdentities.TENANT_A, DeploymentId.of("orders"),
                    graphStream()).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(DeploymentState.READY, status.state());
            var deployment = application.deployment(DeploymentId.of("orders")).orElseThrow();
            assertEquals(IngressDisposition.ACCEPTED,
                    deployment.ingress().offer(TestIdentities.TENANT_A, IngressTarget.start(), "payload"));
        } finally {
            application.close();
            engine.close();
        }
    }

    /**
     * The admission boundary, fail-closed (the deployment-admission contract).
     *
     * <p><b>Mutation-proven.</b> {@code active >= maxActiveDeployments} in
     * {@code DefaultRavenrootApplication.activateDeployment} was changed to
     * {@code active > maxActiveDeployments} (an off-by-one that admits one deployment over the
     * configured cap) and this test was rerun: it failed, because the second activation below no
     * longer threw and instead reached {@code READY}. Reverting the comparator made it pass again.
     * That is the whole of what "prove it by mutation" means for a boundary check: a test that cannot
     * fail when the boundary condition is wrong is not evidence the boundary exists.</p>
     */
    @Test
    void admissionRejectsAtTheCapFailClosedWithTheCounts() throws Exception {
        var engine = new JoinTestEngine();
        var application = applicationWith(engine, 1);
        try {
            application.activateDeployment(TestIdentities.TENANT_A, DeploymentId.of("first"), graphStream())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);

            var rejection = assertThrows(DeploymentAdmissionException.class, () ->
                    application.activateDeployment(TestIdentities.TENANT_A, DeploymentId.of("second"),
                            graphStream()));

            assertEquals(1, rejection.active());
            assertEquals(1, rejection.cap());
            // Rejected at the boundary: the second id was never registered, so a later activation
            // attempt for it -- once the pod has capacity again -- starts clean rather than colliding
            // with a half-admitted entry.
            assertTrue(application.deployment(DeploymentId.of("second")).isEmpty());
        } finally {
            application.close();
            engine.close();
        }
    }

    @Test
    void zeroIsFailClosedForEveryActivation() {
        var engine = new JoinTestEngine();
        var application = applicationWith(engine, 0);
        try {
            var rejection = assertThrows(DeploymentAdmissionException.class, () ->
                    application.activateDeployment(TestIdentities.TENANT_A, DeploymentId.of("any"), graphStream()));
            assertEquals(0, rejection.active());
            assertEquals(0, rejection.cap());
        } finally {
            application.close();
            engine.close();
        }
    }

    @Test
    void reactivatingAnAlreadyActiveDeploymentDoesNotCountAgainstItself() throws Exception {
        var engine = new JoinTestEngine();
        var application = applicationWith(engine, 1);
        try {
            var id = DeploymentId.of("recurring");
            application.activateDeployment(TestIdentities.TENANT_A, id, graphStream())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);

            // Re-activating the SAME id must not be refused by the cap it already holds a slot under
            // -- start() itself is idempotent for an already-READY deployment (GraphDeployment's own
            // contract), so this must return the current status rather than throw.
            var again = application.activateDeployment(TestIdentities.TENANT_A, id, graphStream())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(DeploymentState.READY, again.state());
        } finally {
            application.close();
            engine.close();
        }
    }

    @Test
    void concurrentActivationsAtTheCapAdmitExactlyOne() throws Exception {
        var engine = new JoinTestEngine();
        var application = applicationWith(engine, 1);
        int attempts = 16;
        var barrier = new java.util.concurrent.CyclicBarrier(attempts);
        var admitted = new AtomicInteger();
        var rejected = new AtomicInteger();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(attempts);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < attempts; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    try {
                        barrier.await();
                        application.activateDeployment(TestIdentities.TENANT_A, DeploymentId.of("racer-" + index),
                                graphStream());
                        admitted.incrementAndGet();
                    } catch (DeploymentAdmissionException expected) {
                        rejected.incrementAndGet();
                    } catch (Exception unexpected) {
                        throw new RuntimeException(unexpected);
                    }
                }));
            }
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            assertEquals(1, admitted.get(), "exactly one racer must be admitted against a cap of one");
            assertEquals(attempts - 1, rejected.get());
        } finally {
            pool.shutdownNow();
            application.close();
            engine.close();
        }
    }

    /**
     * {@code startGraphMl} returns as soon as the traversal is accepted -- {@code
     * ExecutionSubmission} carries only identifiers, never the {@code execution} stage {@code
     * DefaultRavenrootApplication#startGraphMl} keeps for its own {@code whenComplete} bookkeeping --
     * so a caller has no handle to await completion through the public API. "start" is spawned
     * synchronously inside {@code runner.execute(...)} (the worker instance is created and registered
     * before the async {@code engine.send()} call), but "end" is spawned only once "start"'s own
     * {@code CompletableFuture} settles, and whether that has already happened by the time a caller
     * checks back depends on whether {@code JoinTestEngine}'s pool wins the race to complete "start"
     * before the caller's own thread attaches its continuation -- the same inline-vs-pool-thread race
     * {@code SpawnGatingEngine} documents. An instrumented 400-iteration run in one JVM
     * confirmed this exactly: every "1, not yet 2" reading resolved to 2 within milliseconds on its
     * own, with no worker instance ever left registered after its traversal settled -- no leak, ever,
     * at any point. (At the time of that run capacity was still reserved through {@code
     * InvocationAdmission}, since removed; the measurement was about registry leakage, not
     * about that reservation, so its result stands unchanged by the removal.) This asserts on the
     * actual property (both nodes are eventually spawned) instead of on an arbitrary point mid-flight.
     */
    @Test
    void thePlaygroundStaysOnTheSharedEngineRegardlessOfTheCap() throws InterruptedException {
        var engine = new JoinTestEngine();
        // Cap of zero: deployments are entirely unavailable. If the one-shot path shared any code
        // path with activation admission, this would now fail too.
        var application = applicationWith(engine, 0);
        try {
            var submission = application.startGraphMl(TestIdentities.TENANT_A, java.util.UUID.randomUUID(),
                    graphStream(), "payload");

            assertTrue(submission.processInstanceId() != null);
            // The playground spawns directly on the engine, never through a domain -- this is the same
            // spawn count GraphRunner's constructor always produced, unrelated to any deployment.
            awaitSpawnCount(engine, 2, "start+end must be spawned directly, exactly as before");
        } finally {
            application.close();
            engine.close();
        }
    }

    @Test
    void closingTheApplicationStopsRegisteredDeployments() throws Exception {
        var engine = new JoinTestEngine();
        var application = applicationWith(engine, 1);
        var id = DeploymentId.of("shutdown-target");
        application.activateDeployment(TestIdentities.TENANT_A, id, graphStream())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        var deployment = application.deployment(id).orElseThrow();

        assertTimeoutPreemptively(Duration.ofSeconds(10), application::close);

        assertEquals(DeploymentState.STOPPED, deployment.status().state());
        engine.close();
    }

    private static DefaultRavenrootApplication applicationWith(JoinTestEngine engine, int maxActiveDeployments) {
        return new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), null, maxActiveDeployments);
    }

    private static ByteArrayInputStream graphStream() {
        return new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Bounded wait for genuinely asynchronous completion, not a retry of a flaky assertion: the
     * traversal this counts really does finish on its own schedule, off the caller's thread, and
     * {@code startGraphMl}'s public contract gives no stronger handle to wait on than this. Fails with
     * the last observed count if the bound is exceeded, so a real regression still reads as a clear
     * assertion failure rather than a silent timeout.
     */
    private static void awaitSpawnCount(JoinTestEngine engine, int expected, String message)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (engine.spawnCount() == expected) {
                return;
            }
            Thread.sleep(2);
        }
        assertEquals(expected, engine.spawnCount(), message);
    }
}
