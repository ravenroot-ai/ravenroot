package ai.ravenroot.pekko;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.application.LiveExecution;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three-node drawing that spins the server, stopped without restarting it.
 *
 * <p>The graph is the one an author draws to express repetition: a node with a self-edge on the
 * outcome that is actually selected, and one other outgoing edge on an outcome that never is.
 * {@code join.semantics=declared} is what makes that a repetition rather than a node waiting
 * for itself, so the marker is present and the loop really runs.</p>
 *
 * <p>It runs under {@link ExecutionPolicy#TEST_PASSTHROUGH}, the mode the product offers as the safe
 * way to try a graph, because that is the mode in which the defect is worst: passthrough answers
 * every message with {@code continue} without constructing the behaviour, so the {@code delay} the
 * author wrote to pace the loop is never executed and the loop runs as fast as the machine allows.</p>
 *
 * <p>The assertions are the contract requirement in order: the repetition is unbounded, the
 * identifier is obtainable from a read surface by someone who never recorded it, cancelling
 * that identifier actually stops the spinning, and the same process still runs a further graph
 * afterwards — which is what distinguishes a stop from a restart.</p>
 */
class RunawayLoopCancellationTest {

    private static final SecurityContext IDENTITY = new SecurityContext(
            "runaway-loop-request", "tenant-a", "alice", PrincipalType.USER, "urn:ravenroot:test");

    /**
     * Three nodes. {@code loop} carries a {@code delay} behaviour, exactly as the author who found
     * this wrote it: the pacing lives in the behaviour, which is what passthrough does not call.
     */
    private static final String THREE_NODE_LOOP = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kjs" for="graph" attr.name="join.semantics" attr.type="string"/>
              <key id="kkind" for="node" attr.name="kind" attr.type="string"/>
              <key id="kbehavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="koutcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="runaway-loop" edgedefault="directed">
                <data key="kjs">declared</data>
                <node id="start"><data key="kkind">START</data></node>
                <node id="loop">
                  <data key="kkind">BEHAVIOR</data>
                  <data key="kbehavior">delay</data>
                </node>
                <node id="end"><data key="kkind">END</data></node>
                <edge id="e1" source="start" target="loop"><data key="koutcome">continue</data></edge>
                <edge id="e2" source="loop" target="loop"><data key="koutcome">continue</data></edge>
                <edge id="e3" source="loop" target="end"><data key="koutcome">done</data></edge>
              </graph>
            </graphml>
            """;

    /** A graph with no loop at all, used only to prove the process is still serving afterwards. */
    private static final String STRAIGHT_LINE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kkind" for="node" attr.name="kind" attr.type="string"/>
              <key id="koutcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="straight-line" edgedefault="directed">
                <node id="start"><data key="kkind">START</data></node>
                <node id="end"><data key="kkind">END</data></node>
                <edge id="e1" source="start" target="end"><data key="koutcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @Test
    void aThreeNodeLoopIsStoppableFromAReadSurfaceWithoutRestartingTheProcess() throws Exception {
        var monitor = new ExecutionMonitor();
        var iterations = new AtomicInteger();
        var secondGraphCompleted = new CountDownLatch(1);
        // Both identifiers are minted here rather than read back, so the listener below can name the
        // second execution before it is submitted and cannot miss a completion that arrives first.
        UUID runawayId = UUID.randomUUID();
        UUID straightLineId = UUID.randomUUID();
        try (var engine = new PekkoExecutionEngine("ravenroot-runaway-loop-test");
             var application = new DefaultRavenrootApplication(engine, monitor);
             AutoCloseable subscription = monitor.subscribe(event -> {
                 if ("loop".equals(event.nodeId())
                         && (event.type() == ExecutionEventType.NODE_BYPASSED
                             || event.type() == ExecutionEventType.NODE_COMPLETED)) {
                     iterations.incrementAndGet();
                 }
                 if (event.type() == ExecutionEventType.EXECUTION_COMPLETED
                         && straightLineId.equals(event.traversalId())) {
                     secondGraphCompleted.countDown();
                 }
             })) {

            var submission = application.startGraphMl(IDENTITY, runawayId,
                    new ByteArrayInputStream(THREE_NODE_LOOP.getBytes(StandardCharsets.UTF_8)),
                    "payload", ExecutionPolicy.TEST_PASSTHROUGH);

            // 1. Nothing bounds the repetition: the loop is still going long after any sane graph
            //    would have finished. 200 is far above what the drawing itself could produce.
            assertTrue(awaitAtLeast(iterations, 200, Duration.ofSeconds(15)),
                    "the self-edge must repeat without bound; reached " + iterations.get() + " iterations");

            // 2. The identifier is obtainable from a read surface, not only from the 202 the
            //    submitter happened to keep. This is the caller who did not record anything.
            List<LiveExecution> live = application.liveExecutions("tenant-a");
            assertEquals(List.of(submission.executionId()),
                    live.stream().map(LiveExecution::traversalId).toList(),
                    "the running traversal must be listed with its identifier");

            // 3. Cancelling that identifier is accepted.
            assertTrue(application.cancelTraversal(live.get(0).traversalId()),
                    "an active traversal must be found and asked to stop");

            // 4. And it actually stops: the loop stops advancing.
            assertTrue(awaitQuiescence(iterations, Duration.ofSeconds(30)),
                    "the loop must stop advancing after the cancel; it was still at "
                            + iterations.get() + " and climbing");

            // 5. Stopped, not restarted: the same process still accepts and completes work.
            application.startGraphMl(IDENTITY, straightLineId,
                    new ByteArrayInputStream(STRAIGHT_LINE.getBytes(StandardCharsets.UTF_8)),
                    "payload", ExecutionPolicy.TEST_PASSTHROUGH);
            assertTrue(secondGraphCompleted.await(30, TimeUnit.SECONDS),
                    "the process must still run a graph after the runaway one was stopped");
        }
    }

    private static boolean awaitAtLeast(AtomicInteger counter, int target, Duration bound)
            throws InterruptedException {
        long deadline = System.nanoTime() + bound.toNanos();
        while (System.nanoTime() < deadline) {
            if (counter.get() >= target) {
                return true;
            }
            Thread.sleep(20);
        }
        return counter.get() >= target;
    }

    /**
     * Quiescence, not a single sample: the counter is read twice a second apart and must not have
     * moved. A loop that merely slowed down would pass a single-sample check.
     */
    private static boolean awaitQuiescence(AtomicInteger counter, Duration bound) throws InterruptedException {
        long deadline = System.nanoTime() + bound.toNanos();
        while (System.nanoTime() < deadline) {
            int before = counter.get();
            Thread.sleep(1_000);
            if (counter.get() == before) {
                return true;
            }
        }
        return false;
    }
}
