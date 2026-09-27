package ai.ravenroot.pekko;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises a real graph-authored counter machine loop and cancels it between elementary steps. */
class BigIntCounterLoopCancellationTest {
    private static final SecurityContext IDENTITY = new SecurityContext(
            "counter-loop", "tenant-a", "alice", PrincipalType.USER, "urn:ravenroot:test");

    @Test
    void separateArithmeticAndDecisionNodesRemainCancellableBetweenIterations() throws Exception {
        var monitor = new ExecutionMonitor();
        var decrements = new AtomicInteger();
        var branches = new AtomicInteger();
        UUID traversalId = UUID.randomUUID();

        try (var engine = new PekkoExecutionEngine("ravenroot-bigint-counter-loop-test");
             var application = new DefaultRavenrootApplication(engine, monitor);
             AutoCloseable subscription = monitor.subscribe(event -> {
                 if (!traversalId.equals(event.traversalId())) return;
                 if (event.type() == ExecutionEventType.NODE_COMPLETED && "decrement".equals(event.nodeId())) {
                     decrements.incrementAndGet();
                 }
                 if (event.type() == ExecutionEventType.NODE_COMPLETED && "branch".equals(event.nodeId())) {
                     branches.incrementAndGet();
                 }
             });
             InputStream graph = getClass().getResourceAsStream("/bigint-counter-loop.graphml")) {
            assertNotNull(graph, "the integration fixture must be packaged");
            application.startGraphMl(IDENTITY, traversalId, graph,
                    Map.of("counter", "9".repeat(4_096), "unrelated", "preserved"));

            assertTrue(awaitAtLeast(decrements, 40, Duration.ofSeconds(20)),
                    "the authored decrement/test/decision cycle must execute repeatedly");
            assertTrue(branches.get() > 0, "the separate decision node must route the loop");
            assertTrue(application.cancelTraversal(traversalId), "the live counter traversal must accept cancel");
            assertTrue(application.liveExecutions("tenant-a").stream()
                            .noneMatch(execution -> traversalId.equals(execution.traversalId())),
                    "the accepted cancellation must disappear from the authoritative live read surface");
            assertTrue(awaitQuiescence(decrements, Duration.ofSeconds(20)),
                    "no further counter iteration may start after terminal cancellation");
        }
    }

    private static boolean awaitAtLeast(AtomicInteger counter, int target, Duration bound)
            throws InterruptedException {
        long deadline = System.nanoTime() + bound.toNanos();
        while (System.nanoTime() < deadline) {
            if (counter.get() >= target) return true;
            Thread.sleep(10);
        }
        return counter.get() >= target;
    }

    private static boolean awaitQuiescence(AtomicInteger counter, Duration bound) throws InterruptedException {
        long deadline = System.nanoTime() + bound.toNanos();
        while (System.nanoTime() < deadline) {
            int before = counter.get();
            Thread.sleep(250);
            if (counter.get() == before) return true;
        }
        return false;
    }
}
