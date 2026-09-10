package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.DeploymentState;
import ai.ravenroot.api.deployment.IngressDisposition;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.lifecycle.DeploymentLifecycleTarget;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry.ObservedKind;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 91's third acceptance criterion, driven against a real implementor of
 * {@link DeploymentLifecycleTarget} rather than against generation arithmetic.
 *
 * <blockquote>Arrivals and dispatch racing Pause, Cancel, Drain, Stop, or Undeploy are assigned
 * deterministically and cannot escape both generations.</blockquote>
 *
 * <h2>Why this could not be asserted before</h2>
 * <p>{@code DeploymentRegistryContract} asserts that a barrier moves the generation and that a
 * command decided against the old one is refused. Both are true of a registry that hosts nothing:
 * they are statements about two numbers. The claim above is about <em>work</em> -- that no arrival
 * is admitted into a generation a barrier has already closed, and that none is silently dropped
 * between the two either. Asserting it needs something that actually admits, dispatches and
 * completes traversals, which is what {@link DefaultGraphDeployment} became when it implemented the
 * port.</p>
 *
 * <h2>How "cannot escape both" is measured, and why the arithmetic is exact</h2>
 * <p>Every accepted arrival ends in exactly one of two places: it reaches the {@code settled} node,
 * or the barrier ends it. The two counters that report those are independent -- the graph's own node
 * counts the first, and {@link DefaultGraphDeployment#endedByLastBarrier()} counts only the
 * cancellations the runner accepted -- so {@code accepted == settled + ended} is a real accounting
 * identity and not two numbers that were derived from each other. An arrival that escaped both
 * generations would break it in the direction of {@code accepted} being larger; an arrival counted
 * on both sides would break it in the other.</p>
 *
 * <p>Every unit is parked in the {@code work} node until the test releases it, so no traversal can
 * finish between the barrier's snapshot and its cancellations. That window is real and correct --
 * work admitted before a barrier is allowed to complete under its own generation -- but it would make
 * the identity above depend on timing, so it is closed here deliberately rather than tolerated.</p>
 */
class DefaultGraphDeploymentHalfOpenBarrierTest {

    private static final SecurityContext IDENTITY = new SecurityContext("barrier-request",
            "barrier-tenant", "barrier-subject", PrincipalType.WORKLOAD, "urn:ravenroot:barrier");

    /** {@code start -> work (parks) -> settled (counts) -> end}. */
    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="node-behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="half-open-barrier" edgedefault="directed">
                <node id="error"><data key="node-kind">ERROR</data></node>
                <node id="start"><data key="node-kind">START</data></node>
                <node id="work">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">work</data>
                </node>
                <node id="settled">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">settled</data>
                </node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="e1" source="start" target="work"><data key="edge-outcome">continue</data></edge>
                <edge id="e2" source="work" target="settled"><data key="edge-outcome">continue</data></edge>
                <edge id="e3" source="settled" target="end"><data key="edge-outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    private static final int CAPACITY = 256;

    @TempDir
    Path directory;

    // --------------------------------------------------------------------- the deterministic half

    /**
     * The interval itself: admission closes at {@code G}, the work holding {@code G} is ended by the
     * barrier, and everything arriving afterwards enters at {@code G + 1} and completes there.
     */
    @Test
    void aBarrierEndsExactlyTheWorkAdmittedBeforeItAndAdmitsEverythingAfterwardsAtTheNewGeneration()
            throws Exception {
        var gate = new CompletableFuture<NodeResult>();
        var settled = new AtomicInteger();
        try (var engine = new JoinTestEngine()) {
            DefaultGraphDeployment deployment = deployment(engine, gate, settled);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            for (int i = 0; i < 3; i++) {
                assertEquals(IngressDisposition.ACCEPTED,
                        deployment.ingress().offer(IDENTITY, IngressTarget.start(), "before-" + i));
            }
            awaitInFlight(deployment, 3);

            deployment.barrier(2).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(3, deployment.endedByLastBarrier(),
                    "the barrier must end every unit admitted at the generation it closed");

            for (int i = 0; i < 3; i++) {
                assertEquals(IngressDisposition.ACCEPTED,
                        deployment.ingress().offer(IDENTITY, IngressTarget.start(), "after-" + i),
                        "a barrier opens the new generation; it does not close the deployment");
            }

            gate.complete(NodeResult.continueWith("released"));
            awaitInFlight(deployment, 0);

            assertEquals(3, settled.get(),
                    "only the units admitted at the generation the barrier opened may complete");
            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Re-driving the same barrier is the recovery path, and it must not end the work that barrier's
     * own generation opened.
     *
     * <p>This is where the comparison being exact equality rather than {@code >=} is observable. A
     * second {@code barrier(2)} arrives with units in flight that carry generation 2 -- the generation
     * it opened. An ordering test would find them "not after" the barrier and end them, discarding
     * work an operator's cancel never asked about; equality finds them on the opening side and leaves
     * them alone (ADR 0038 D6).</p>
     *
     * <p>The old generation is deliberately inside an incomplete node when the first barrier runs.
     * Cancellation is cooperative, so that node remains physically in flight until its future returns,
     * even though the traversal may run no later node. The two units admitted by the barrier therefore
     * bring the physical count to three. Waiting for all three node entries makes that contract fact,
     * rather than executor scheduling, decide what the re-drive observes.</p>
     */
    @Test
    void reDrivingTheSameBarrierLeavesTheWorkItsOwnGenerationAdmitted() throws Exception {
        var gate = new CompletableFuture<NodeResult>();
        var settled = new AtomicInteger();
        var beforeEntered = new CountDownLatch(1);
        var openedEntered = new CountDownLatch(2);
        try (var engine = new JoinTestEngine()) {
            DefaultGraphDeployment deployment = deployment(engine, gate, settled,
                    new ConcurrentHashMap<>(), ConcurrentHashMap.newKeySet(), payload -> {
                        if (payload.equals("before")) {
                            beforeEntered.countDown();
                        } else if (payload.startsWith("opened-")) {
                            openedEntered.countDown();
                        }
                    });
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            try {
                assertEquals(IngressDisposition.ACCEPTED,
                        deployment.ingress().offer(IDENTITY, IngressTarget.start(), "before"));
                assertTrue(beforeEntered.await(10, TimeUnit.SECONDS),
                        "the old generation must enter blocked work before the barrier");
                deployment.barrier(2).toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals(1, deployment.endedByLastBarrier());

                for (int i = 0; i < 2; i++) {
                    assertEquals(IngressDisposition.ACCEPTED,
                            deployment.ingress().offer(IDENTITY, IngressTarget.start(), "opened-" + i));
                }
                assertTrue(openedEntered.await(10, TimeUnit.SECONDS),
                        "both units admitted by the barrier must enter blocked work before its re-drive");
                assertEquals(3, deployment.observe().toCompletableFuture().get(10, TimeUnit.SECONDS).inFlight(),
                        "cooperative cancellation leaves the old node physically in flight until it returns");

                deployment.barrier(2).toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals(1, deployment.endedByLastBarrier(),
                        "a re-drive at the same generation is the same barrier, and ends nothing further");
                assertEquals(3, deployment.observe().toCompletableFuture().get(10, TimeUnit.SECONDS).inFlight(),
                        "re-driving the barrier must leave both units admitted by its generation in flight");

                gate.complete(NodeResult.continueWith("released"));
                awaitInFlight(deployment, 0);
                assertEquals(2, settled.get(),
                        "the cancelled old generation must not continue after its in-flight node returns");
            } finally {
                gate.complete(NodeResult.continueWith("cleanup"));
                deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Closing admission holds the activation, so the deployment keeps reporting {@code READY} through
     * {@link ai.ravenroot.api.deployment.DeploymentStatus} -- which is exactly why ingress cannot
     * decide admission from that state alone.
     */
    @Test
    void closingAdmissionRefusesArrivalsWhileTheDeploymentIsStillReadyAndOpeningItAdmitsAgain()
            throws Exception {
        var gate = new CompletableFuture<NodeResult>();
        var settled = new AtomicInteger();
        try (var engine = new JoinTestEngine()) {
            DefaultGraphDeployment deployment = deployment(engine, gate, settled);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            deployment.closeAdmission(2).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertSame(DeploymentState.READY, deployment.status().state(),
                    "a pause retains the activation: the state machine is not what closed admission");
            assertEquals(IngressDisposition.REJECTED_ADMISSION_CLOSED,
                    deployment.ingress().offer(IDENTITY, IngressTarget.start(), "held"));
            assertSame(ObservedKind.PAUSED,
                    deployment.observe().toCompletableFuture().get(10, TimeUnit.SECONDS).state());

            deployment.openAdmission(3).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertSame(ObservedKind.READY,
                    deployment.observe().toCompletableFuture().get(10, TimeUnit.SECONDS).state());
            assertEquals(IngressDisposition.ACCEPTED,
                    deployment.ingress().offer(IDENTITY, IngressTarget.start(), "resumed"));

            gate.complete(NodeResult.continueWith("released"));
            awaitInFlight(deployment, 0);
            assertEquals(1, settled.get(),
                    "the arrival admitted at the reopened generation completes under it");
            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * The interleaving the racing test below reaches only by chance, entered deliberately: an arrival
     * assigned the closing generation whose recorder is still opening when the barrier runs.
     *
     * <p>That arrival was never handed to the runner, so there is no traversal for the barrier to end;
     * the offer refuses it when it resumes, and the refusal is the whole of its assignment. The barrier
     * must not also report it as ended. It used to, because {@link GraphRunner#cancelTraversal} answers
     * {@code true} for any identifier it has not refused before -- deliberately, leaving "was anything
     * running" to its caller -- so one arrival was counted on both sides: refused to its caller and
     * ended by the barrier (issue 314).</p>
     */
    @Test
    void anArrivalTheBarrierClosedBeforeItWasDispatchedIsRefusedAndNotEnded() throws Exception {
        var gate = new CompletableFuture<NodeResult>();
        var settled = new AtomicInteger();
        try (var engine = new JoinTestEngine();
             var durable = new SqliteExecutionStore(directory.resolve("barrier.db"), Clock.systemUTC())) {
            var store = new SuspendFirstWriteExecutionStore(durable);
            DefaultGraphDeployment deployment = deployment(engine, gate, settled, store);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            try {
                CompletableFuture<IngressDisposition> held = CompletableFuture.supplyAsync(
                        () -> deployment.ingress().offer(IDENTITY, IngressTarget.start(), "held"));
                assertTrue(store.awaitFirstWrite(Duration.ofSeconds(10)),
                        "the arrival must be parked between its admission and its dispatch");

                deployment.barrier(2).toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals(0, deployment.endedByLastBarrier(),
                        "nothing had been dispatched, so the barrier has nothing to end");

                store.releaseFirstWrite();
                assertEquals(IngressDisposition.REJECTED_ADMISSION_CLOSED, held.get(10, TimeUnit.SECONDS),
                        "an arrival whose generation closed before dispatch is refused, not dispatched");
                assertTrue(store.firstWriteWasHeldOpen());
                assertEquals(0, deployment.endedByLastBarrier(),
                        "the refused arrival is on the closing side once, as a refusal");
                awaitInFlight(deployment, 0);

                assertEquals(IngressDisposition.ACCEPTED,
                        deployment.ingress().offer(IDENTITY, IngressTarget.start(), "after"));
                gate.complete(NodeResult.continueWith("released"));
                awaitInFlight(deployment, 0);
                assertEquals(1, settled.get(), "only the arrival admitted at the opened generation completes");
            } finally {
                store.releaseFirstWrite();
                gate.complete(NodeResult.continueWith("cleanup"));
                deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * The refusal above belongs to the barrier that closed the arrival's generation, not to whether
     * that barrier still governs when the arrival resumes. A pause and resume after the barrier adopt
     * a new generation and stand the barrier down; the arrival it closed must stay closed rather than
     * be dispatched as work carrying a generation the barrier already ended.
     */
    @Test
    void anArrivalTheBarrierClosedStaysClosedAfterAdmissionMovesOn() throws Exception {
        var gate = new CompletableFuture<NodeResult>();
        var settled = new AtomicInteger();
        try (var engine = new JoinTestEngine();
             var durable = new SqliteExecutionStore(directory.resolve("barrier.db"), Clock.systemUTC())) {
            var store = new SuspendFirstWriteExecutionStore(durable);
            DefaultGraphDeployment deployment = deployment(engine, gate, settled, store);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            try {
                CompletableFuture<IngressDisposition> held = CompletableFuture.supplyAsync(
                        () -> deployment.ingress().offer(IDENTITY, IngressTarget.start(), "held"));
                assertTrue(store.awaitFirstWrite(Duration.ofSeconds(10)),
                        "the arrival must be parked between its admission and its dispatch");

                deployment.barrier(2).toCompletableFuture().get(10, TimeUnit.SECONDS);
                deployment.closeAdmission(3).toCompletableFuture().get(10, TimeUnit.SECONDS);
                deployment.openAdmission(4).toCompletableFuture().get(10, TimeUnit.SECONDS);

                store.releaseFirstWrite();
                assertEquals(IngressDisposition.REJECTED_ADMISSION_CLOSED, held.get(10, TimeUnit.SECONDS),
                        "a barrier that closed this arrival's generation is not reopened by a later resume");
                assertTrue(store.firstWriteWasHeldOpen());
                awaitInFlight(deployment, 0);
                assertEquals(0, settled.get());
            } finally {
                store.releaseFirstWrite();
                gate.complete(NodeResult.continueWith("cleanup"));
                deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    // ------------------------------------------------------------------------- the racing half

    /**
     * The criterion's own wording: arrivals racing the barrier are assigned deterministically and
     * cannot escape both generations.
     *
     * <p>Offers run continuously from several threads while {@code barrier} is in flight, so arrivals
     * land on both sides of it and in the window between the snapshot and the cancellations. Every
     * accepted one is then accounted for exactly once. A refusal is an assignment too and is counted
     * separately: an arrival whose generation the barrier closed while it was still awaiting dispatch
     * is refused rather than dispatched, which is the closing side answering, not a unit going
     * missing -- and not a unit the barrier ended, which is why {@code refused} is never part of the
     * identity below.</p>
     */
    @Test
    void everyArrivalRacingABarrierIsAccountedForExactlyOnce() throws Exception {
        var gate = new CompletableFuture<NodeResult>();
        var settled = new AtomicInteger();
        var accepted = new AtomicInteger();
        var refused = new AtomicInteger();
        var entered = new ConcurrentHashMap<UUID, String>();
        Set<UUID> completed = ConcurrentHashMap.newKeySet();
        try (var engine = new JoinTestEngine()) {
            DefaultGraphDeployment deployment = deployment(engine, gate, settled, entered, completed);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            for (int i = 0; i < 8; i++) {
                assertEquals(IngressDisposition.ACCEPTED,
                        deployment.ingress().offer(IDENTITY, IngressTarget.start(), "before-" + i));
                accepted.incrementAndGet();
            }
            awaitInFlight(deployment, 8);

            var release = new CountDownLatch(1);
            var racersReady = new CountDownLatch(4);
            var racers = new ArrayList<Thread>();
            for (int racer = 0; racer < 4; racer++) {
                Thread thread = new Thread(() -> {
                    racersReady.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < 20; i++) {
                        IngressDisposition disposition =
                                deployment.ingress().offer(IDENTITY, IngressTarget.start(), "racing");
                        if (disposition == IngressDisposition.ACCEPTED) {
                            accepted.incrementAndGet();
                        } else {
                            refused.incrementAndGet();
                        }
                    }
                }, "barrier-racer-" + racer);
                racers.add(thread);
                thread.start();
            }
            assertTrue(racersReady.await(10, TimeUnit.SECONDS));

            release.countDown();
            deployment.barrier(2).toCompletableFuture().get(10, TimeUnit.SECONDS);
            for (Thread racer : racers) {
                racer.join(TimeUnit.SECONDS.toMillis(20));
            }

            for (int i = 0; i < 8; i++) {
                IngressDisposition disposition =
                        deployment.ingress().offer(IDENTITY, IngressTarget.start(), "after-" + i);
                assertEquals(IngressDisposition.ACCEPTED, disposition);
                accepted.incrementAndGet();
            }

            gate.complete(NodeResult.continueWith("released"));
            awaitInFlight(deployment, 0);

            long ended = deployment.endedByLastBarrier();
            assertEquals(accepted.get(), settled.get() + ended,
                    "every accepted arrival must be on exactly one side of the barrier: "
                            + "accepted=" + accepted.get() + " settled=" + settled.get()
                            + " ended=" + ended + " refused=" + refused.get());
            assertTrue(ended >= 8,
                    "the eight units admitted before the barrier are on its closing side");
            assertTrue(settled.get() >= 8,
                    "the eight units offered after the barrier are on its opening side");
            // The escape the criterion actually forbids: a unit admitted at the generation the barrier
            // closed must not complete under it. Every offer made before barrier() was called carries
            // that generation, because nothing else moves it.
            List<String> escaped = completed.stream()
                    .map(entered::get)
                    .filter(payload -> payload != null && payload.startsWith("before-"))
                    .toList();
            assertEquals(List.of(), escaped,
                    "work admitted at the closing generation must not complete under it");
            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    // -------------------------------------------------------------------------------- fixtures

    private static DefaultGraphDeployment deployment(JoinTestEngine engine,
                                                     CompletableFuture<NodeResult> gate,
                                                     AtomicInteger settled) {
        return deployment(engine, gate, settled, new ConcurrentHashMap<>(), ConcurrentHashMap.newKeySet());
    }

    /**
     * @param entered what each traversal was offered, recorded where the original payload is still
     *                visible: {@code work} returns the shared gate, whose own result is what
     *                {@code settled} would otherwise see.
     * @param completed the traversals that reached {@code settled}.
     */
    private static DefaultGraphDeployment deployment(JoinTestEngine engine,
                                                     CompletableFuture<NodeResult> gate,
                                                     AtomicInteger settled,
                                                     ConcurrentHashMap<UUID, String> entered,
                                                     Set<UUID> completed) {
        return deployment(engine, gate, settled, entered, completed, ignored -> { });
    }

    /**
     * @param store an execution store, so the volatile offer opens a recorder between its admission and
     *              its dispatch -- the one window a test can hold open deliberately.
     */
    private static DefaultGraphDeployment deployment(JoinTestEngine engine,
                                                     CompletableFuture<NodeResult> gate,
                                                     AtomicInteger settled,
                                                     ExecutionStore store) {
        return deployment(engine, gate, settled, new ConcurrentHashMap<>(), ConcurrentHashMap.newKeySet(),
                ignored -> { }, store);
    }

    private static DefaultGraphDeployment deployment(JoinTestEngine engine,
                                                     CompletableFuture<NodeResult> gate,
                                                     AtomicInteger settled,
                                                     ConcurrentHashMap<UUID, String> entered,
                                                     Set<UUID> completed,
                                                     Consumer<String> onWorkEntry) {
        return deployment(engine, gate, settled, entered, completed, onWorkEntry, null);
    }

    private static DefaultGraphDeployment deployment(JoinTestEngine engine,
                                                     CompletableFuture<NodeResult> gate,
                                                     AtomicInteger settled,
                                                     ConcurrentHashMap<UUID, String> entered,
                                                     Set<UUID> completed,
                                                     Consumer<String> onWorkEntry,
                                                     ExecutionStore store) {
        var behaviors = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults())
                // One shared gate for every traversal: releasing it releases all of them at once, so a
                // unit the barrier ended and a unit it admitted are distinguished by the barrier alone
                // and never by which of them happened to be scheduled first.
                .register("work", message -> {
                    String payload = String.valueOf(message.payload());
                    entered.put(message.traversalId(), payload);
                    onWorkEntry.accept(payload);
                    return gate;
                })
                .register("settled", message -> {
                    completed.add(message.traversalId());
                    settled.incrementAndGet();
                    return CompletableFuture.completedFuture(NodeResult.continueWith("settled"));
                });
        return new DefaultGraphDeployment(DeploymentId.of("barrier-" + java.util.UUID.randomUUID()),
                engine, behaviors, new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                GRAPH.getBytes(StandardCharsets.UTF_8), CAPACITY, store,
                DefaultGraphDeployment.DEFAULT_INBOX_RETENTION);
    }

    /** Waits for the port's own in-flight evidence to reach {@code expected}. */
    private static void awaitInFlight(DefaultGraphDeployment deployment, long expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        long observed = -1;
        while (System.nanoTime() - deadline < 0) {
            observed = deployment.observe().toCompletableFuture().get(10, TimeUnit.SECONDS).inFlight();
            if (observed == expected) {
                return;
            }
            Thread.sleep(5);
        }
        assertEquals(expected, observed, "in-flight count never settled");
    }
}
