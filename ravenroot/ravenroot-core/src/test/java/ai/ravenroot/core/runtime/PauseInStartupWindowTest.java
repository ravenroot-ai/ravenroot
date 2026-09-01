package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.LiveExecution;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphManager;
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
 * A pause accepted inside the startup window must actually hold the traversal, and the hold
 * must be releasable.
 *
 * <h2>The window, and how it is entered rather than raced for</h2>
 * <p>The same window {@link CancelInStartupWindowTest} stands in, entered with the same fixture.
 * {@code DefaultRavenrootApplication.startGraphMl} puts the execution into {@code activeExecutions}
 * — which is what {@code liveExecutions} reads — and only afterwards writes the two durable
 * transitions, takes the recorder's lease, and calls {@code runner.execute}, which is where a
 * coordinator is registered. {@link SuspendFirstWriteExecutionStore} holds the first durable write
 * open, so the submission is parked inside the window for as long as this test likes.</p>
 *
 * <h2>Why this is not the cancel test with a different verb</h2>
 * <p>Cancellation needs one property: a traversal refused before it starts never starts. A pause
 * additionally requires the hold to be <em>releasable</em>. A gate installed for a traversal that
 * has no coordinator yet has to be the
 * gate the first hop reads, and {@code resumeTraversal} has to be able to open it. So this test
 * asserts three facts in order: the pause was accepted, no node effect followed it, and a resume
 * made the effect happen.</p>
 *
 * <h2>The effect is counted by the node, and its ordering with the resume too</h2>
 * <p>{@code effects} is incremented inside the behaviour, never read off the {@link ExecutionMonitor}
 * event stream — that stream is output of the code under test, and a guard hung off it stops guarding
 * when the code changes. The monitor is used only to know <em>when</em> to look, and every bound it
 * is awaited on reddens the test on expiry rather than passing quietly.</p>
 *
 * <p>The "no effect until the resume" half is asserted twice, deliberately, because the two shapes
 * fail differently. {@code effectRan.await} over a short bound is an absence over a bound — the only
 * shape that claim has while a traversal is held, and the shape {@code TraversalPauseResumeTest}
 * already uses. {@code effectSawTheResume} is an ordering read taken <em>inside</em> the behaviour:
 * the flag is set before {@code resumeTraversal} is called, so an effect that ran before the resume
 * records {@code false} however the two threads were scheduled, and no bound decides it.</p>
 *
 * <h2>Where {@code ALREADY_PAUSED} is killed</h2>
 * <p>The false outcome is produced one layer up, by
 * {@code AuthorizedRavenrootApplication.pauseExecution}, which reads {@code false} plus "still live"
 * as "already paused". That layer maps {@code true} to {@code PAUSED} unconditionally, so
 * {@code assertTrue(paused)} here is what removes the wrong outcome there; the test stays in core
 * because that is where both the window and the fixture that enters it live.</p>
 */
class PauseInStartupWindowTest {

    /** Generously above any healthy duration: expiry is a failed assertion, never a quiet pass. */
    private static final Duration BOUND = Duration.ofSeconds(30);

    /**
     * The bound the held traversal is watched over. Short on purpose and in the opposite direction
     * from {@link #BOUND}: this one is awaited for a <em>negative</em>, so a generous value would buy
     * nothing but wall-clock time, and its expiry is the pass rather than the failure.
     */
    private static final Duration HELD_BOUND = Duration.ofSeconds(2);

    /**
     * One behaviour node between start and end. A {@code BEHAVIOR} node under a STANDARD run,
     * deliberately: {@code TEST_PASSTHROUGH} answers every message without constructing the
     * behaviour, so under it there would be no node effect to count.
     */
    private static final String ONE_EFFECT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="node-behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="one-effect" edgedefault="directed">
                <node id="start"><data key="node-kind">START</data></node>
                <node id="effect">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">record-effect</data>
                </node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="e1" source="start" target="effect"><data key="edge-outcome">continue</data></edge>
                <edge id="e2" source="effect" target="end"><data key="edge-outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    /** No behaviour node at all: the second test never runs a traversal, it only installs a gate. */
    private static final String START_TO_END = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="start-to-end" edgedefault="directed">
                <node id="start"><data key="node-kind">START</data></node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="e1" source="start" target="end"><data key="edge-outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @Test
    void aPauseAcceptedBeforeTheTraversalIsRegisteredHoldsItUntilAResumeArrives() throws Exception {
        var effects = new AtomicInteger();
        var effectRan = new CountDownLatch(1);
        var resumeIssued = new AtomicBoolean();
        var effectSawTheResume = new AtomicBoolean();
        var registry = new BehaviorRegistry().register("record-effect", message -> {
            effects.incrementAndGet();
            effectSawTheResume.set(resumeIssued.get());
            effectRan.countDown();
            return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        });

        UUID traversalId = UUID.randomUUID();
        var started = new CountDownLatch(1);
        var terminal = new CountDownLatch(1);
        var monitor = new ExecutionMonitor();
        var store = new SuspendFirstWriteExecutionStore(new InMemoryExecutionStore());
        var submissionFailure = new AtomicReference<Throwable>();

        try (var engine = new SameThreadExecutionEngine();
             var application = new DefaultRavenrootApplication(engine, monitor, registry,
                     new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                     ExecutionIdentitySource.randomUuids(), store);
             AutoCloseable subscription = monitor.subscribe(event -> {
                 if (!traversalId.equals(event.traversalId())) {
                     return;
                 }
                 if (event.type() == ExecutionEventType.EXECUTION_STARTED) {
                     started.countDown();
                 }
                 if (event.type() == ExecutionEventType.EXECUTION_COMPLETED
                         || event.type() == ExecutionEventType.EXECUTION_FAILED) {
                     terminal.countDown();
                 }
             })) {

            var submitter = new Thread(() -> {
                try {
                    application.startGraphMl(TestIdentities.TENANT_A, traversalId,
                            new ByteArrayInputStream(ONE_EFFECT.getBytes(StandardCharsets.UTF_8)), "payload");
                } catch (RuntimeException | Error startupFailure) {
                    submissionFailure.set(startupFailure);
                }
            }, "pause-startup-submitter");
            submitter.start();

            // Inside the window, established by the fixture rather than inferred from the subject.
            assertTrue(store.awaitFirstWrite(BOUND),
                    "the submission must have reached its first durable write");
            assertEquals(0, effects.get(),
                    "no node may have run yet: the submission is parked before runner.execute");

            // A reader who never saw the 202 can find this traversal and hold it.
            assertEquals(List.of(traversalId),
                    application.liveExecutions("tenant-a").stream().map(LiveExecution::traversalId).toList(),
                    "the traversal must already be listed among the live ones inside the window");

            boolean paused = application.pauseTraversal(traversalId);

            store.releaseFirstWrite();
            submitter.join(BOUND.toMillis());
            assertFalse(submitter.isAlive(), "the submission must have returned");
            assertTrue(store.firstWriteWasHeldOpen(),
                    "the first write must have been held open by the gate and released on request; "
                            + "otherwise this run never stood inside the window");
            assertNull(submissionFailure.get(),
                    "the submission must have succeeded, or the hold below would be vacuous: "
                            + submissionFailure.get());

            // The traversal really did get past the window and into runner.execute, so the gate is
            // now the only thing between it and its first node.
            assertTrue(started.await(BOUND.toSeconds(), TimeUnit.SECONDS),
                    "the traversal must have started, or nothing was held back");
            assertFalse(effectRan.await(HELD_BOUND.toSeconds(), TimeUnit.SECONDS),
                    "a pause accepted inside the startup window must hold the traversal: no node "
                            + "effect may run before a resume arrives");
            assertTrue(paused,
                    "a traversal that nobody is holding must not be answered as already paused");
            assertEquals(List.of(traversalId),
                    application.liveExecutions("tenant-a").stream().map(LiveExecution::traversalId).toList(),
                    "a held traversal is still live: that is what makes it inspectable rather than lost");

            // Set before the call, so an effect that ran earlier records false whatever the schedule.
            resumeIssued.set(true);
            assertTrue(application.resumeTraversal(traversalId),
                    "a gate installed inside the window must be the gate a resume can open");
            assertTrue(effectRan.await(BOUND.toSeconds(), TimeUnit.SECONDS),
                    "the resume must let the held hop run; effects so far: " + effects.get());
            assertTrue(terminal.await(BOUND.toSeconds(), TimeUnit.SECONDS),
                    "the released traversal must reach a terminal execution event");
            assertEquals(1, effects.get(), "the released traversal must run its one node exactly once");
            assertTrue(effectSawTheResume.get(),
                    "the node effect must have followed the resume rather than preceded it");
        }
    }

    /**
     * The gate installed inside the window must not outlive the runner that owns it.
     *
     * <p>{@code releasePauseGate} removes a gate for every traversal that reaches
     * {@code GraphRunner.release()}, and a traversal whose submission failed before
     * {@code runner.execute} never gets there. The gate's lifetime is therefore bounded by the
     * runner's instead, in {@link GraphRunner#close()}, which prevents the cancellation map from
     * growing without bound.</p>
     *
     * <p>What this constrains, stated so it is not over-read: {@code close()}, not the composition
     * that reaches it. The two links that carry a failed submission there are existing code —
     * {@code DefaultRavenrootApplication.startGraphMl}'s startup-failure catch calls
     * {@code active.close()}, and {@code ActiveExecution.close()} calls {@code runner.close()} — and
     * no assertion here touches either. At the runner's own boundary a traversal parked in the window
     * and a traversal that never existed are the same thing: neither has a coordinator, which is
     * precisely why {@code pauseTraversal} stopped asking.</p>
     */
    @Test
    void noPauseGateOutlivesTheRunnerThatAFailedSubmissionCloses() throws Exception {
        var monitor = new ExecutionMonitor();
        try (var engine = new SameThreadExecutionEngine()) {

            // Control. Without it the subject below would pass just as well against a pause that
            // installed nothing at all, which is the vacuity this file's neighbours warn about.
            UUID controlId = UUID.randomUUID();
            try (var document = GraphManager.readGraphMlDocument(
                    new ByteArrayInputStream(START_TO_END.getBytes(StandardCharsets.UTF_8)));
                 var control = new GraphRunner(document.manager(), engine, new BehaviorRegistry(), monitor)) {
                assertTrue(control.pauseTraversal(controlId),
                        "a pause for a traversal this runner has not registered must be accepted");
                assertTrue(control.resumeTraversal(controlId),
                        "and it must have installed a gate a resume can find");
            }

            UUID abandonedId = UUID.randomUUID();
            try (var document = GraphManager.readGraphMlDocument(
                    new ByteArrayInputStream(START_TO_END.getBytes(StandardCharsets.UTF_8)))) {
                var runner = new GraphRunner(document.manager(), engine, new BehaviorRegistry(), monitor);
                assertTrue(runner.pauseTraversal(abandonedId),
                        "the pause this submission never got to use must still have been accepted");
                runner.close();
                assertFalse(runner.resumeTraversal(abandonedId),
                        "a gate whose traversal never ran must not survive the runner's close");
            }
        }
    }
}
