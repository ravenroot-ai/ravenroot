package ai.ravenroot.pekko;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.application.LiveExecution;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recorded pause semantics, asserted one clause at a time.
 *
 * <p>The three clauses were decided before any of this was implemented — the in-flight node
 * finishes, resume is a transition of its own, and cancel says what may already have escaped — so
 * this class asserts them rather than restating them. The first two are behaviour and are here; the
 * third is {@code CancelResult}'s note, which is data and is asserted where that type is.</p>
 *
 * <p>{@code gate} is a behaviour that blocks until this test releases it, which is what makes "the
 * in-flight node finishes" observable at all: the pause is issued while the node is provably still
 * running, and the assertion is that its completion still arrives afterwards.</p>
 */
class TraversalPauseResumeTest {

    private static final SecurityContext IDENTITY = new SecurityContext(
            "pause-resume-request", "tenant-a", "alice", PrincipalType.USER, "urn:ravenroot:test");

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kkind" for="node" attr.name="kind" attr.type="string"/>
              <key id="kbehavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="koutcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="pause-resume" edgedefault="directed">
                <node id="start"><data key="kkind">START</data></node>
                <node id="gate">
                  <data key="kkind">BEHAVIOR</data>
                  <data key="kbehavior">gate</data>
                </node>
                <node id="tail">
                  <data key="kkind">BEHAVIOR</data>
                  <data key="kbehavior">tail</data>
                </node>
                <node id="end"><data key="kkind">END</data></node>
                <edge id="e1" source="start" target="gate"><data key="koutcome">continue</data></edge>
                <edge id="e2" source="gate" target="tail"><data key="koutcome">continue</data></edge>
                <edge id="e3" source="tail" target="end"><data key="koutcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @Test
    void pauseLetsTheInFlightNodeFinishAndResumeIsItsOwnTransition() throws Exception {
        var monitor = new ExecutionMonitor();
        var gateEntered = new CountDownLatch(1);
        var gateCompleted = new CountDownLatch(1);
        var tailStarted = new CountDownLatch(1);
        var executionCompleted = new CountDownLatch(1);
        var releaseGate = new CompletableFuture<NodeResult>();
        var behaviors = new BehaviorRegistry()
                .register("gate", message -> {
                    gateEntered.countDown();
                    return releaseGate;
                })
                .register("tail", message -> CompletableFuture.completedFuture(
                        NodeResult.continueWith(message.payload())));

        UUID traversalId = UUID.randomUUID();
        try (var engine = new PekkoExecutionEngine("ravenroot-pause-resume-test");
             var application = new DefaultRavenrootApplication(engine, monitor, behaviors);
             AutoCloseable subscription = monitor.subscribe(event -> {
                 if (event.type() == ExecutionEventType.NODE_COMPLETED && "gate".equals(event.nodeId())) {
                     gateCompleted.countDown();
                 }
                 if (event.type() == ExecutionEventType.NODE_STARTED && "tail".equals(event.nodeId())) {
                     tailStarted.countDown();
                 }
                 if (event.type() == ExecutionEventType.EXECUTION_COMPLETED) {
                     executionCompleted.countDown();
                 }
             })) {

            application.startGraphMl(IDENTITY, traversalId,
                    new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)), "payload",
                    ExecutionPolicy.STANDARD);
            assertTrue(gateEntered.await(10, TimeUnit.SECONDS), "the gate node must be in flight");

            assertTrue(application.pauseTraversal(traversalId), "a running traversal must accept a pause");
            assertFalse(application.pauseTraversal(traversalId),
                    "pausing a paused traversal must report that it changed nothing");

            // A paused traversal is still live: this is what makes it inspectable rather than lost,
            // and it is why pause is not a slower cancel.
            assertEquals(List.of(traversalId), application.liveExecutions("tenant-a").stream()
                            .map(LiveExecution::traversalId).toList(),
                    "a paused traversal must still be listed as live");

            // Clause one: the in-flight node finishes. It was already running when the pause landed,
            // and nothing asked it to stop, so its completion still arrives.
            releaseGate.complete(NodeResult.continueWith("gate result"));
            assertTrue(gateCompleted.await(10, TimeUnit.SECONDS),
                    "the node that was in flight when the pause landed must still complete");

            // And then the traversal stops: the hop the gate's completion would have triggered does
            // not start. Asserted as an absence over a bound, which is the only shape this claim has.
            assertFalse(tailStarted.await(3, TimeUnit.SECONDS),
                    "no node after the in-flight one may start while the traversal is paused");

            // Clause two: resume is an operation of its own, and its answer distinguishes releasing a
            // hold from finding none.
            assertTrue(application.resumeTraversal(traversalId), "a paused traversal must accept a resume");
            assertTrue(tailStarted.await(10, TimeUnit.SECONDS), "the held hop must run once resumed");
            assertTrue(executionCompleted.await(10, TimeUnit.SECONDS), "the traversal must finish after resuming");
            assertFalse(application.resumeTraversal(traversalId),
                    "resuming a traversal that is not paused must report that it changed nothing");
        }
    }

    /**
     * A paused traversal is still cancellable, and cancelling it does not leave it parked.
     *
     * <p>This is the case the pause gate could most easily have broken: a hop waiting on a stage
     * nobody completes is indistinguishable from a hang, so a cancel that only marked the traversal
     * would report success and leave it holding for the life of the process — the same false success
     * the cancellation rule prevents.</p>
     */
    @Test
    void cancellingAPausedTraversalReleasesTheHopItWasHolding() throws Exception {
        var monitor = new ExecutionMonitor();
        var gateEntered = new CountDownLatch(1);
        var executionCancelled = new CountDownLatch(1);
        var tailStarted = new CountDownLatch(1);
        var releaseGate = new CompletableFuture<NodeResult>();
        var behaviors = new BehaviorRegistry()
                .register("gate", message -> {
                    gateEntered.countDown();
                    return releaseGate;
                })
                .register("tail", message -> CompletableFuture.completedFuture(
                        NodeResult.continueWith(message.payload())));

        UUID traversalId = UUID.randomUUID();
        try (var engine = new PekkoExecutionEngine("ravenroot-pause-cancel-test");
             var application = new DefaultRavenrootApplication(engine, monitor, behaviors);
             AutoCloseable subscription = monitor.subscribe(event -> {
                 if (event.type() == ExecutionEventType.NODE_STARTED && "tail".equals(event.nodeId())) {
                     tailStarted.countDown();
                 }
                 if (event.type() == ExecutionEventType.EXECUTION_CANCELLED) {
                     executionCancelled.countDown();
                 }
             })) {

            application.startGraphMl(IDENTITY, traversalId,
                    new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)), "payload",
                    ExecutionPolicy.STANDARD);
            assertTrue(gateEntered.await(10, TimeUnit.SECONDS), "the gate node must be in flight");
            assertTrue(application.pauseTraversal(traversalId));
            releaseGate.complete(NodeResult.continueWith("gate result"));
            // Established before the cancel, not assumed: without this the test would pass against a
            // pause that never took effect, because a traversal that simply ran to completion also
            // never has a parked hop to release.
            assertFalse(tailStarted.await(3, TimeUnit.SECONDS),
                    "the traversal must be provably parked before the cancel this test is about");

            assertTrue(application.cancelTraversal(traversalId), "a paused traversal must still be cancellable");
            assertTrue(executionCancelled.await(15, TimeUnit.SECONDS),
                    "cancelling a paused traversal must end it rather than leave it holding, and it "
                            + "ends as a cancellation rather than as a failure: the event type is the "
                            + "only dimension this stream is labelled by, so a stop published as "
                            + "EXECUTION_FAILED would be counted as one");
            assertFalse(tailStarted.await(1, TimeUnit.SECONDS),
                    "the released hop must be refused by the cancellation, not run by the resume path");
            assertTrue(application.liveExecutions("tenant-a").isEmpty(),
                    "a cancelled traversal must no longer be listed as live");
        }
    }
}
