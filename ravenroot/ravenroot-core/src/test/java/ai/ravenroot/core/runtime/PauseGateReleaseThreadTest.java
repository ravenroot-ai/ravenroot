package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentityKind;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Releasing a pause gate must not run the parked hop on the thread that released it.
 *
 * <h2>What is being measured, and why it is not a duration</h2>
 * <p>The defect is latency charged to the wrong thread, not a wrong result, so a threshold in
 * milliseconds would be a statement about this machine rather than about the code. What is asserted
 * instead is <em>which thread</em> did the work: the control call runs on a thread this test names,
 * and no graph write and no invocation identity may be charged to that name. The one write that may
 * be, and must be, is the control call settling the durable hold it is releasing — that write is the
 * operation being performed rather than a hop's work misattributed, it cannot be deferred without
 * the call reporting a release it does not know committed, and the test asserts it lands here rather
 * than merely tolerating it. Both probes sit at
 * the two operations in the released hop's prologue —
 * {@code identitySource.nextNodeInvocationId()} and {@code state.nodeStarted}, whose write
 * {@link ExecutionRecorder#record} joins on its caller's thread.</p>
 *
 * <h2>How the test knows a hop really is parked</h2>
 * <p>This is the half that decides whether the test guards anything at all. A resume that lands
 * before the hop reaches the gate removes a gate nobody is waiting on: the continuation then runs on
 * the traversal's own thread, the assertions hold, and the test has measured nothing. So parking is
 * established from the fixture, never inferred from the subject:</p>
 * <ol>
 *   <li>{@link SameThreadExecutionEngine} carries the traversal on the submitting thread and creates
 *       no thread of its own — asserted, not assumed: the {@code hold} behaviour records the thread
 *       that ran it and the test checks it was the submitter;</li>
 *   <li>the submitter has terminated by the time the control call is made;</li>
 *   <li>the node past the gate has not run, and no terminal event has been published.</li>
 * </ol>
 * <p>Together those say the traversal has neither finished nor any thread left to advance it, which
 * for this graph is only possible parked at the gate. If any of the three stops holding — because
 * the engine, the submission path or the graph changed — the test reddens on that assertion instead
 * of passing quietly.</p>
 *
 * <h2>Why both control paths</h2>
 * <p>{@code resumeTraversal} and {@code cancelTraversal} both reach {@code releasePauseGate}, and
 * they release the hop into different work: resume into a hop's prologue, cancel into the failure
 * propagation, which terminates the coordinator and notifies the {@link ExecutionMonitor}, whose
 * delivery to listeners is synchronous by contract. So the cancel case also records the thread the
 * {@code EXECUTION_FAILED} notification arrives on.</p>
 */
class PauseGateReleaseThreadTest {

    /** Generously above any healthy duration: expiry is a failed assertion, never a quiet pass. */
    private static final Duration BOUND = Duration.ofSeconds(30);

    private static final String CONTROL_THREAD = "pause-gate-control";
    private static final String SUBMITTER_THREAD = "pause-gate-submitter";

    /**
     * {@code hold} exists to open the pause window deterministically: while a thread is inside it the
     * traversal is registered, so {@code pauseTraversal} is answered {@code true} without racing for
     * it. {@code past-gate} is the node the released hop dispatches, and the only node whose
     * invocation may not be charged to the control thread.
     */
    private static final String HOLD_THEN_ONE_MORE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="node-behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="hold-then-one-more" edgedefault="directed">
                <node id="start"><data key="node-kind">START</data></node>
                <node id="hold">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">hold</data>
                </node>
                <node id="past-gate">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">past-gate</data>
                </node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="e1" source="start" target="hold"><data key="edge-outcome">continue</data></edge>
                <edge id="e2" source="hold" target="past-gate"><data key="edge-outcome">continue</data></edge>
                <edge id="e3" source="past-gate" target="end"><data key="edge-outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @Test
    void resumingAParkedTraversalRunsNoGraphWorkOnTheResumingThread() throws Exception {
        try (var fixture = new ParkedTraversal()) {
            fixture.parkAHopAtTheGate();

            var resumed = new AtomicBoolean();
            fixture.onControlThread(() -> resumed.set(fixture.application.resumeTraversal(fixture.traversalId)));

            assertTrue(resumed.get(), "the traversal was paused here, so the resume must be accepted");
            assertTrue(fixture.terminal.await(BOUND.toSeconds(), TimeUnit.SECONDS),
                    "the released hop must carry the traversal to a terminal event; node runs past the "
                            + "gate so far: " + fixture.pastGateRuns.get());
            // Without this the guard would hold vacuously on a release that freed nothing.
            assertEquals(1, fixture.pastGateRuns.get(),
                    "the parked continuation must actually have run, or nothing was released");

            fixture.assertControlThreadRanNoGraphWork();
        }
    }

    @Test
    void cancellingAParkedTraversalRunsNoGraphWorkOnTheCancellingThread() throws Exception {
        try (var fixture = new ParkedTraversal()) {
            fixture.parkAHopAtTheGate();

            var cancelled = new AtomicBoolean();
            fixture.onControlThread(() -> cancelled.set(fixture.application.cancelTraversal(fixture.traversalId)));

            assertTrue(cancelled.get(), "a live traversal must not be reported as absent");
            assertTrue(fixture.terminal.await(BOUND.toSeconds(), TimeUnit.SECONDS),
                    "the released hop must end the traversal rather than leave it parked for good");
            // The cancel path's own proof that something was released: the gate's hop re-entered
            // run(), read the refusal and failed the traversal instead of running the next node.
            assertEquals(0, fixture.pastGateRuns.get(),
                    "a cancelled traversal must not run the node past the gate");

            fixture.assertControlThreadRanNoGraphWork();
            assertFalse(CONTROL_THREAD.equals(fixture.terminalEventThread.get()),
                    "the monitor's terminal notification is delivered synchronously, so it must not be "
                            + "delivered on the control thread; it was delivered on "
                            + fixture.terminalEventThread.get());
        }
    }

    /**
     * A traversal parked at its pause gate, with a probe on each thing the released hop would do.
     *
     * <p>The probes are deliberately on the runner's collaborators — the identity source and the
     * store — rather than on anything the runner reports. A guard hooked to the subject's own output
     * stops guarding the day the subject changes, and says nothing when it does.</p>
     */
    private final class ParkedTraversal implements AutoCloseable {

        private final UUID traversalId = UUID.randomUUID();
        private final AtomicInteger pastGateRuns = new AtomicInteger();
        private final CountDownLatch holdEntered = new CountDownLatch(1);
        private final CountDownLatch releaseHold = new CountDownLatch(1);
        private final CountDownLatch terminal = new CountDownLatch(1);
        private final AtomicReference<String> holdThread = new AtomicReference<>();
        private final AtomicReference<String> terminalEventThread = new AtomicReference<>();
        private final AtomicReference<Throwable> submissionFailure = new AtomicReference<>();
        private final List<String> invocationMintingThreads = new CopyOnWriteArrayList<>();

        private final ExecutionMonitor monitor = new ExecutionMonitor();
        private final ThreadRecordingExecutionStore store =
                new ThreadRecordingExecutionStore(new InMemoryExecutionStore());
        private final SameThreadExecutionEngine engine = new SameThreadExecutionEngine();
        private final DefaultRavenrootApplication application;
        private final AutoCloseable subscription;
        private final Thread submitter;

        ParkedTraversal() {
            var registry = new BehaviorRegistry()
                    .register("hold", message -> {
                        holdThread.set(Thread.currentThread().getName());
                        holdEntered.countDown();
                        awaitOrFail(releaseHold);
                        return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                    })
                    .register("past-gate", message -> {
                        pastGateRuns.incrementAndGet();
                        return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                    });

            ExecutionIdentitySource identities = kind -> {
                if (kind == ExecutionIdentityKind.NODE_INVOCATION) {
                    invocationMintingThreads.add(Thread.currentThread().getName());
                }
                return UUID.randomUUID();
            };

            this.application = new DefaultRavenrootApplication(engine, monitor, registry,
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(), identities, store);
            this.subscription = monitor.subscribe(event -> {
                if (traversalId.equals(event.traversalId())
                        && (event.type() == ExecutionEventType.EXECUTION_COMPLETED
                            || event.type() == ExecutionEventType.EXECUTION_FAILED
                            || event.type() == ExecutionEventType.EXECUTION_CANCELLED)) {
                    terminalEventThread.set(Thread.currentThread().getName());
                    terminal.countDown();
                }
            });
            this.submitter = new Thread(() -> {
                try {
                    application.startGraphMl(TestIdentities.TENANT_A, traversalId,
                            new ByteArrayInputStream(HOLD_THEN_ONE_MORE.getBytes(StandardCharsets.UTF_8)),
                            "payload");
                } catch (RuntimeException | Error startupFailure) {
                    submissionFailure.set(startupFailure);
                }
            }, SUBMITTER_THREAD);
        }

        /**
         * Leaves the traversal with exactly one hop waiting on its pause gate, and proves it did.
         *
         * @throws InterruptedException if the test thread is interrupted while waiting
         */
        void parkAHopAtTheGate() throws InterruptedException {
            submitter.start();
            assertTrue(holdEntered.await(BOUND.toSeconds(), TimeUnit.SECONDS),
                    "the traversal must have reached the holding node");
            assertEquals(SUBMITTER_THREAD, holdThread.get(),
                    "the engine must carry the traversal on the submitting thread; otherwise the "
                            + "parking argument below does not hold");

            assertTrue(application.pauseTraversal(traversalId),
                    "a traversal with a node in flight is registered, so the pause must be accepted");

            releaseHold.countDown();
            submitter.join(BOUND.toMillis());
            assertFalse(submitter.isAlive(),
                    "the submission must have returned: no other thread can advance this traversal");
            assertNull(submissionFailure.get(), "the submission must not have failed");

            assertEquals(0, pastGateRuns.get(),
                    "the hop past the gate must not have run: it is the one that must be parked");
            assertEquals(1L, terminal.getCount(),
                    "the traversal must not have terminated: it is parked, not finished");
        }

        /** Runs one control call on a thread whose name the assertions can look for. */
        void onControlThread(Runnable control) throws InterruptedException {
            var thread = new Thread(control, CONTROL_THREAD);
            thread.start();
            thread.join(BOUND.toMillis());
            assertFalse(thread.isAlive(), "the control call must return");
        }

        /**
         * The measurement: no durable write and no invocation identity charged to the control thread.
         */
        void assertControlThreadRanNoGraphWork() {
            assertEquals(0L, store.graphWritesFrom(CONTROL_THREAD),
                    "a control endpoint must not pay for journal writes; writes were issued from "
                            + store.writingThreads());
            // The separation above is only honest if the excluded write is the one it names. This
            // traversal is held at a boundary the runtime writes down, so releasing it settles that
            // hold, and the settlement is the control operation itself rather than graph work
            // misattributed. Asserting it landed here keeps the exclusion from being a hole an
            // unrelated write could later slip through.
            assertEquals(1L, store.holdSettlementsFrom(CONTROL_THREAD),
                    "the control call's own settlement of the hold it released must be charged to it, "
                            + "and it is the only store write that may be; writes were issued from "
                            + store.writingThreads());
            assertEquals(0L, invocationMintingThreads.stream().filter(CONTROL_THREAD::equals).count(),
                    "a control endpoint must not run a hop's prologue; invocation ids were minted on "
                            + invocationMintingThreads);
        }

        @Override
        public void close() throws Exception {
            releaseHold.countDown();
            subscription.close();
            application.close();
            engine.close();
        }

        private void awaitOrFail(CountDownLatch latch) {
            try {
                if (!latch.await(BOUND.toSeconds(), TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the holding node was never released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }
}
