package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.LiveExecution;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cancel accepted inside the startup window must either stop the traversal or say it did not.
 *
 * <h2>The window, and how it is entered rather than raced for</h2>
 * <p>{@code DefaultRavenrootApplication.startGraphMl} puts the execution into {@code activeExecutions}
 * — which is what {@code liveExecutions} reads and what {@code cancelTraversal} removes from — and only
 * afterwards writes the two durable transitions, takes the recorder's lease, and calls
 * {@code runner.execute}, which is where a coordinator is registered. Everything between those two
 * points is the window. {@link SuspendFirstWriteExecutionStore} holds the first durable write open, so
 * the test stands inside the window for as long as it likes instead of hoping to arrive there.</p>
 *
 * <h2>What is asserted, and why both halves are needed</h2>
 * <p>The measurement establishes two facts at once: the cancel answered {@code true}, and the graph
 * then ran a real node effect. So both are asserted here, and neither on its own would be enough:</p>
 * <ul>
 *   <li>dropping {@code assertTrue(cancelled)} would let the cheap repair pass — stop discarding
 *       {@code GraphRunner.cancelTraversal}'s boolean and answer {@code false}. That trades a false
 *       success for a false failure: the traversal really is about to start, and an operator told
 *       "nothing was active" would go looking for something else to cancel;</li>
 *   <li>dropping the effect assertion would leave exactly the defect, which answers {@code true}
 *       already.</li>
 * </ul>
 *
 * <h2>The effect is counted by the node, not by the runner's event stream</h2>
 * <p>{@code effects} is incremented inside the behaviour itself. The {@link ExecutionMonitor}
 * subscription below is used only to know <em>when</em> to look, never as the evidence, and its bound
 * expiring is an assertion failure rather than a quiet pass — a progress signal that stops firing must
 * redden the test, not silence it.</p>
 */
class CancelInStartupWindowTest {

    private static final Duration BOUND = Duration.ofSeconds(30);

    /**
     * One behaviour node between start and end. It is a {@code BEHAVIOR} node and the run is a
     * STANDARD one, deliberately: {@code TEST_PASSTHROUGH} answers every message without constructing
     * the behaviour, so under it no node effect exists to count.
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

    @Test
    void aCancelAcceptedBeforeTheTraversalIsRegisteredLeavesNoNodeEffectBehind() throws Exception {
        var effects = new AtomicInteger();
        var registry = new BehaviorRegistry().register("record-effect", message -> {
            effects.incrementAndGet();
            return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        });

        UUID traversalId = UUID.randomUUID();
        var terminal = new CountDownLatch(1);
        var monitor = new ExecutionMonitor();
        var store = new SuspendFirstWriteExecutionStore(new InMemoryExecutionStore());
        var submissionFailure = new AtomicReference<Throwable>();

        try (var engine = new SameThreadExecutionEngine();
             var application = new DefaultRavenrootApplication(engine, monitor, registry,
                     new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                     ExecutionIdentitySource.randomUuids(), store);
             AutoCloseable subscription = monitor.subscribe(event -> {
                 if (traversalId.equals(event.traversalId())
                         && (event.type() == ExecutionEventType.EXECUTION_COMPLETED
                             || event.type() == ExecutionEventType.EXECUTION_FAILED
                             || event.type() == ExecutionEventType.EXECUTION_CANCELLED)) {
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
            }, "cancel-startup-submitter");
            submitter.start();

            // Inside the window, established by the fixture rather than inferred from the subject.
            assertTrue(store.awaitFirstWrite(BOUND),
                    "the submission must have reached its first durable write");
            assertEquals(0, effects.get(),
                    "no node may have run yet: the submission is parked before runner.execute");

            // A reader who never saw the 202 can find this traversal and cancel it.
            assertEquals(List.of(traversalId),
                    application.liveExecutions("tenant-a").stream().map(LiveExecution::traversalId).toList(),
                    "the traversal must already be listed among the live ones inside the window");

            boolean cancelled = application.cancelTraversal(traversalId);

            store.releaseFirstWrite();
            submitter.join(BOUND.toMillis());
            assertFalse(submitter.isAlive(), "the submission must have returned");
            assertTrue(store.firstWriteWasHeldOpen(),
                    "the first write must have been held open by the gate and released on request; "
                            + "otherwise this run never stood inside the window");

            if (submissionFailure.get() == null) {
                // The stage exists, so a terminal event is owed. Expiry is a red, never a pass.
                assertTrue(terminal.await(BOUND.toSeconds(), TimeUnit.SECONDS),
                        "the traversal must reach a terminal execution event; effects so far: "
                                + effects.get());
            }

            assertTrue(cancelled,
                    "a traversal that is genuinely about to start must not be reported as absent");
            assertEquals(0, effects.get(),
                    "the cancel answered CANCELLED, so no node effect may follow it");
        }
    }
}
