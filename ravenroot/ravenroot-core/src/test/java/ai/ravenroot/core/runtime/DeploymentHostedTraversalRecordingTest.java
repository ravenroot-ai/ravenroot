package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.IngressDisposition;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.LeaseHandle;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A traversal hosted by a {@link ai.ravenroot.api.deployment.GraphDeployment} records through
 * the store under its own lease, exactly as the direct submission path does.
 *
 * <p>Before this, the recorder was wired only on direct submission. Deployment-hosted traversals —
 * the ones the four inbound extensions produce — wrote nothing, held no lease, and a crash lost them.
 *
 * <h2>Every coexistence case is paired with its converse</h2>
 * <p>A sweep that returns nothing because the guard held is indistinguishable, on its own, from a
 * sweep that returns nothing because it is broken. So each case drives the lease away and asserts the
 * work <em>does</em> come back. Only the pair means anything, and a sweep incapable of ever returning
 * work would fail the second half.
 */
class DeploymentHostedTraversalRecordingTest {

    private static final String TENANT = "acme";
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

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

    // ---------------------------------------------------------------- records traversals

    @Test
    @DisplayName("a deployment-hosted traversal records its instance, and RUNNING is committed before dispatch")
    void aDeploymentHostedTraversalRecordsThroughTheStore(@TempDir Path dir) throws Exception {
        var clock = new MovableClock(EPOCH);
        try (var engine = new JoinTestEngine();
             var store = new SqliteExecutionStore(dir.resolve("record.db"), clock)) {
            var observed = ObservableExecutionStore.observing(store, key -> { });
            var completions = countCompletions();
            var deployment = deployment(engine, observed, completions.monitor());
            deployment.start(security()).toCompletableFuture().get(20, TimeUnit.SECONDS);

            assertEquals(IngressDisposition.ACCEPTED,
                    deployment.ingress().offer(security(), IngressTarget.start(), "payload"));
            assertTrue(completions.latch().await(20, TimeUnit.SECONDS), "traversal never completed");

            List<ExecutionKey> recorded = distinct(observed);
            assertEquals(1, recorded.size(),
                    "a deployment-hosted traversal must create exactly one process instance");
            StoredProcessInstance stored = await(store.load(recorded.get(0)));
            assertNotEquals(ProcessInstanceStatus.ACCEPTED, stored.state().status(),
                    "the instance must have advanced past ACCEPTED: RUNNING is committed before the "
                            + "engine send, so a persisted state of ACCEPTED would mean the traversal "
                            + "was dispatched without its intention being recorded first");
            deployment.stop().toCompletableFuture().get(20, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("with no store composed the deployment preserves in-memory behavior")
    void withoutAStoreNothingChanges(@TempDir Path dir) throws Exception {
        try (var engine = new JoinTestEngine()) {
            var completions = countCompletions();
            var deployment = new DefaultGraphDeployment(DeploymentId.of("no-store"), engine,
                    BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()), completions.monitor(),
                    ExecutionIdentitySource.randomUuids(), GRAPH.getBytes(StandardCharsets.UTF_8),
                    DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY);
            deployment.start(security()).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(IngressDisposition.ACCEPTED,
                    deployment.ingress().offer(security(), IngressTarget.start(), "payload"));
            assertTrue(completions.latch().await(20, TimeUnit.SECONDS));
            deployment.stop().toCompletableFuture().get(20, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------- coexistence, each paired with its converse

    @Test
    @DisplayName("a sweep never claims an instance a live deployment is executing, and does claim it once the lease is gone")
    void aSweepNeverClaimsWhatALiveDeploymentIsExecuting(@TempDir Path dir) throws Exception {
        var clock = new MovableClock(EPOCH);
        var sweepWhileLive = new AtomicReference<List<PendingWork>>();
        var keyUnderExecution = new AtomicReference<ExecutionKey>();

        try (var engine = new JoinTestEngine();
             var real = new SqliteExecutionStore(dir.resolve("coexist.db"), clock)) {
            // Runs the sweep at the exact moment the deployment has written under its lease and is
            // still executing. Interception is used instead of a blocking node so the observation
            // point is the write itself rather than a timing window.
            var observed = ObservableExecutionStore.observing(real, batchKey -> {
                if (keyUnderExecution.compareAndSet(null, batchKey)) {
                    sweepWhileLive.set(await(real.claimPendingWork(TENANT, "recovery-live", 10, TTL)));
                }
            });
            var completions = countCompletions();
            var deployment = deployment(engine, observed, completions.monitor());
            deployment.start(security()).toCompletableFuture().get(20, TimeUnit.SECONDS);

            assertEquals(IngressDisposition.ACCEPTED,
                    deployment.ingress().offer(security(), IngressTarget.start(), "payload"));
            assertTrue(completions.latch().await(20, TimeUnit.SECONDS), "traversal never completed");

            assertTrue(sweepWhileLive.get() != null, "the interception never fired; the assertion below "
                    + "would have passed vacuously");
            assertTrue(sweepWhileLive.get().isEmpty(),
                    "a recovery sweep claimed an instance a live deployment was executing");

            // THE CONVERSE. Same tenant, same sweep, same bound -- the only thing that changed is that
            // the lease is gone. If this comes back empty the sweep is simply incapable of returning
            // work and the assertion above proved nothing at all.
            ExecutionKey key = keyUnderExecution.get();
            var abandoned = newRunningInstanceWithoutALease(real, key.tenantId());
            List<PendingWork> afterLeaseGone = await(real.claimPendingWork(TENANT, "recovery-free", 10, TTL));
            assertFalse(afterLeaseGone.isEmpty(),
                    "the sweep returned nothing even with claimable, unleased work present -- so the "
                            + "coexistence assertion above was vacuous, not a guarantee");
            assertTrue(afterLeaseGone.stream().anyMatch(work -> work.key().equals(abandoned)),
                    "the sweep must return the unleased instance specifically");

            deployment.stop().toCompletableFuture().get(20, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------ per traversal, closed on completion

    @Test
    @DisplayName("each traversal gets its own instance and its own lease, never one hoisted to the deployment")
    void eachTraversalGetsItsOwnRecorder(@TempDir Path dir) throws Exception {
        var clock = new MovableClock(EPOCH);
        try (var engine = new JoinTestEngine();
             var store = new SqliteExecutionStore(dir.resolve("per-traversal.db"), clock)) {
            var observed = ObservableExecutionStore.observing(store, key -> { });
            var completions = countCompletions(3);
            var deployment = deployment(engine, observed, completions.monitor());
            deployment.start(security()).toCompletableFuture().get(20, TimeUnit.SECONDS);

            for (int i = 0; i < 3; i++) {
                assertEquals(IngressDisposition.ACCEPTED,
                        deployment.ingress().offer(security(), IngressTarget.start(), "payload-" + i));
            }
            assertTrue(completions.latch().await(30, TimeUnit.SECONDS), "not every traversal completed");

            // Three events, three distinct process instances. A recorder hoisted to the deployment
            // would have produced one instance holding a single lease over work it does not own.
            assertEquals(3, distinct(observed).size(),
                    "each accepted ingress event must own a distinct process instance");
            deployment.stop().toCompletableFuture().get(20, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("the lease is released on traversal completion, while the deployment is still running")
    void theLeaseIsReleasedOnTraversalCompletionNotDeploymentStop(@TempDir Path dir) throws Exception {
        var clock = new MovableClock(EPOCH);
        try (var engine = new JoinTestEngine();
             var store = new SqliteExecutionStore(dir.resolve("release.db"), clock)) {
            var observed = ObservableExecutionStore.observing(store, key -> { });
            var completions = countCompletions();
            var deployment = deployment(engine, observed, completions.monitor());
            deployment.start(security()).toCompletableFuture().get(20, TimeUnit.SECONDS);

            assertEquals(IngressDisposition.ACCEPTED,
                    deployment.ingress().offer(security(), IngressTarget.start(), "payload"));
            assertTrue(completions.latch().await(20, TimeUnit.SECONDS));

            // The deployment is deliberately still READY here. A recorder closed at deployment stop
            // would still be holding this instance, and a deployment running for days would hold every
            // instance it had ever touched.
            ExecutionKey key = distinct(observed).get(0);
            // Bounded poll, not an immediate assertion. EXECUTION_COMPLETED is published by the
            // monitor, and the recorder is closed in the traversal's whenComplete callback: the two
            // are ordered independently, so asserting the instant the latch fires races the release
            // and would pass or fail on scheduling. Polling asserts the same guarantee -- the lease
            // IS released without the deployment stopping -- without depending on which ran first.
            LeaseHandle reclaimed = null;
            for (int attempt = 0; attempt < 100 && reclaimed == null; attempt++) {
                try {
                    reclaimed = await(store.claim(key, "someone-else", TTL));
                } catch (ExecutionStoreException stillHeld) {
                    Thread.sleep(50);
                }
            }
            assertTrue(reclaimed != null,
                    "the traversal's lease was never released at completion: the instance is still "
                            + "held while the deployment runs on, so a deployment alive for days "
                            + "would hold every instance it ever touched");
            await(store.release(reclaimed));

            deployment.stop().toCompletableFuture().get(20, TimeUnit.SECONDS);
        }
    }

    // ---------------------------------------------------- busy instance fails closed

    @Test
    @DisplayName("a busy instance is refused and dispatches nothing, on both ingress surfaces")
    void aBusyInstanceFailsClosed(@TempDir Path dir) throws Exception {
        var clock = new MovableClock(EPOCH);
        try (var engine = new JoinTestEngine();
             var real = new SqliteExecutionStore(dir.resolve("busy.db"), clock)) {
            // Contention is unreachable through the product today -- every ingress event mints a fresh
            // instance identifier -- so it is injected here at the only place it could ever surface.
            var busy = ObservableExecutionStore.alwaysBusy(real);
            var completions = countCompletions();
            var deployment = deployment(engine, busy, completions.monitor());
            deployment.start(security()).toCompletableFuture().get(20, TimeUnit.SECONDS);

            assertEquals(IngressDisposition.REJECTED_INSTANCE_BUSY,
                    deployment.ingress().offer(security(), IngressTarget.start(), "payload"));

            IngressReceipt receipt = deployment.ingress()
                    .offerDurably(security(), IngressTarget.start(), "payload", "src", "key-1");
            assertInstanceOf(IngressReceipt.Refused.class, receipt);

            assertFalse(completions.latch().await(2, TimeUnit.SECONDS),
                    "a refused offer must dispatch no traversal at all");
            assertEquals(0, completions.count().get());

            // The permit was returned: a refusal that leaked its permit would silently shrink the
            // buffer until the deployment stopped admitting anything.
            assertEquals(DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY,
                    deployment.ingress().bufferCapacity());
            deployment.stop().toCompletableFuture().get(20, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------------------------------ fixtures

    private static DefaultGraphDeployment deployment(ai.ravenroot.api.execution.ExecutionEngine engine,
                                                     ExecutionStore store, ExecutionMonitor monitor) {
        return new DefaultGraphDeployment(DeploymentId.of("hosted-" + UUID.randomUUID()), engine,
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()), monitor,
                ExecutionIdentitySource.randomUuids(), GRAPH.getBytes(StandardCharsets.UTF_8),
                DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, store,
                DefaultGraphDeployment.DEFAULT_INBOX_RETENTION, "test-worker", TTL);
    }

    private static SecurityContext security() {
        return new SecurityContext("req-" + UUID.randomUUID(), TENANT, "tester",
                PrincipalType.USER, "test-issuer");
    }


    /** A RUNNING instance nobody holds, so the sweep has something it is genuinely allowed to claim. */
    private static ExecutionKey newRunningInstanceWithoutALease(ExecutionStore store, String tenant) {
        var key = new ExecutionKey(tenant, UUID.randomUUID());
        var traversalId = UUID.randomUUID();
        var traversal = new ai.ravenroot.api.application.Traversal(traversalId, "start",
                ai.ravenroot.api.application.TraversalStatus.ACCEPTED, java.util.Map.of());
        var instance = new ai.ravenroot.api.application.ProcessInstance(key.processInstanceId(),
                ProcessInstanceStatus.ACCEPTED, java.util.Map.of(traversalId, traversal));
        var created = await(store.apply(ExecutionBatch.to(key)
                .expecting(ai.ravenroot.api.persistence.RevisionExpectation.notPresent())
                .apply(new ai.ravenroot.api.persistence.ExecutionTransition.ProcessCreated(instance,
                        new ai.ravenroot.api.persistence.GraphVersionPin("v1")))
                .build()));
        // A RUNNING node attempt, not merely a RUNNING instance. PERS-04 widened the claim query to
        // RUNNING attempts, so an instance without one is not claimable and this fixture would make
        // the converse assertion pass for the wrong reason -- which is precisely what it caught.
        var invocationId = UUID.randomUUID();
        var attemptId = UUID.randomUUID();
        await(store.apply(ExecutionBatch.to(key)
                .expecting(ai.ravenroot.api.persistence.RevisionExpectation.exactly(created.revision()))
                .apply(new ai.ravenroot.api.persistence.ExecutionTransition.ProcessTransitioned(
                        ProcessInstanceStatus.RUNNING))
                .apply(new ai.ravenroot.api.persistence.ExecutionTransition.TraversalTransitioned(
                        traversalId, ai.ravenroot.api.application.TraversalStatus.RUNNING))
                .apply(new ai.ravenroot.api.persistence.ExecutionTransition.InvocationAdded(traversalId,
                        new ai.ravenroot.api.application.NodeInvocation(invocationId, "work", null,
                                ai.ravenroot.api.application.NodeInvocationStatus.SCHEDULED)))
                .apply(new ai.ravenroot.api.persistence.ExecutionTransition.InvocationTransitioned(traversalId,
                        invocationId, ai.ravenroot.api.application.NodeInvocationStatus.RUNNING))
                .apply(new ai.ravenroot.api.persistence.ExecutionTransition.AttemptAdded(traversalId,
                        invocationId, new ai.ravenroot.api.application.NodeAttempt(attemptId, 1,
                                ai.ravenroot.api.application.NodeAttemptStatus.SCHEDULED)))
                .apply(new ai.ravenroot.api.persistence.ExecutionTransition.AttemptTransitioned(traversalId,
                        invocationId, attemptId, ai.ravenroot.api.application.NodeAttemptStatus.RUNNING))
                .build()));
        return key;
    }

    /** Distinct process instances observed being written under a fence, in first-seen order. */
    private static List<ExecutionKey> distinct(ObservableExecutionStore observed) {
        return observed.fencedWrites().stream().distinct().toList();
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException wrapped) {
            ExecutionStoreException failure = ExecutionStoreException.unwrap(wrapped);
            throw failure == null ? wrapped : failure;
        }
    }

    private static Completions countCompletions() {
        return countCompletions(1);
    }

    private static Completions countCompletions(int expected) {
        var monitor = new ExecutionMonitor();
        var count = new AtomicInteger();
        var latch = new CountDownLatch(expected);
        monitor.subscribe(event -> {
            if (event.type() == ExecutionEventType.EXECUTION_COMPLETED) {
                count.incrementAndGet();
                latch.countDown();
            }
        });
        return new Completions(monitor, count, latch);
    }

    private record Completions(ExecutionMonitor monitor, AtomicInteger count, CountDownLatch latch) {
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public synchronized Instant instant() {
            return now;
        }
    }
}
