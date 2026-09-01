package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.persistence.JoinStore;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Isolation of concurrent arrivals at one join, and of concurrent executions of one graph.
 *
 * <p>The single-fire property is measured against {@link LastWriteWinsJoinStore} rather than
 * asserted on its own, because a race test that never sees the defect it guards is indistinguishable
 * from a test whose branches never actually raced.</p>
 */
class JoinConcurrencyTest {

    /** Branches racing into one join. Wide enough that a lost compare-and-set is likely, not lucky. */
    private static final int BRANCHES = 8;

    /** Trials per measurement. Reported as a detection rate, not as a pass or fail. */
    private static final int TRIALS = 60;

    private final JoinTestEngine engine = new JoinTestEngine(BRANCHES);
    private final ExecutionMonitor monitor = new ExecutionMonitor();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    @Test
    void firesAQuorumOfOneExactlyOnceWhenEveryBranchArrivesTogether() throws Exception {
        for (int trial = 0; trial < TRIALS; trial++) {
            assertEquals(1, joinFirings(new ContendedJoinStore(new InMemoryJoinStore(), BRANCHES)),
                    "a compare-and-set join must fire exactly once on trial " + trial);
        }
    }

    /**
     * The measurement that gives the assertion above its meaning: the same scenario against a store
     * whose compare-and-set does not compare. The rate is reported rather than assumed, and a low
     * rate would mean the detector is weak and the branches are not really racing.
     */
    @Test
    void detectsTheLostUpdateInAStoreWithoutCompareAndSet() throws Exception {
        int detected = 0;
        var firings = new ArrayList<Integer>();
        for (int trial = 0; trial < TRIALS; trial++) {
            int fired = joinFirings(new ContendedJoinStore(new LastWriteWinsJoinStore(), BRANCHES));
            firings.add(fired);
            if (fired != 1) {
                detected++;
            }
        }
        System.out.println("[CORE-03] lost-update detection rate: " + detected + "/" + TRIALS
                + " firings=" + firings);
        assertTrue(detected * 2 > TRIALS,
                "the race detector caught the broken store only " + detected + "/" + TRIALS
                        + " times, which is too weak to stand as evidence");
    }

    @Test
    void isolatesConcurrentExecutionsOfTheSameGraphOnOneRunner() throws Exception {
        int executions = 64;
        var store = new InMemoryJoinStore();
        var graph = JoinMiniGraphs.fanIn(3);

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, tagged(), monitor,
                     ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC())) {
            var running = new ArrayList<CompletableFuture<GraphExecutionResult>>();
            for (int index = 0; index < executions; index++) {
                running.add(runner.execute(TestIdentities.TENANT_A, UUID.randomUUID(),
                        UUID.randomUUID(), "payload-" + index, "v1").toCompletableFuture());
            }
            for (int index = 0; index < executions; index++) {
                Object payload = running.get(index).get(10, TimeUnit.SECONDS).payload();
                // Every traversal must merge its own payload three times over, never a neighbour's.
                assertEquals(List.of("b0:payload-" + index, "b1:payload-" + index, "b2:payload-" + index),
                        payload, "traversal " + index + " saw another execution's arrivals");
            }
            assertEquals(0, store.totalRecordCount());
        }
    }

    /** How many times the join node ran in one execution where every branch is released at once. */
    private int joinFirings(JoinStore store) throws Exception {
        var localMonitor = new ExecutionMonitor();
        var gate = new CountDownLatch(1);
        var arrived = new CountDownLatch(BRANCHES);
        var graph = JoinMiniGraphs.fanIn(BRANCHES, JoinMiniGraphs.quorum(1));
        var joinRuns = new AtomicInteger();

        var registry = new BehaviorRegistry();
        for (int index = 0; index < BRANCHES; index++) {
            String branch = "b" + index;
            registry.register(branch, message -> {
                arrived.countDown();
                try {
                    // Every branch parks here, so they are all released into the join together
                    // rather than trickling in in dispatch order.
                    assertTrue(gate.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return CompletableFuture.completedFuture(NodeResult.continueWith("from-" + branch));
            });
        }

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry, localMonitor,
                     ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC())) {
            var execution = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture();
            assertTrue(arrived.await(10, TimeUnit.SECONDS), "every branch must be at the gate");
            gate.countDown();
            execution.get(10, TimeUnit.SECONDS);
        }
        joinRuns.set((int) localMonitor.eventsAfter(0).stream()
                .filter(event -> "join".equals(event.nodeId()))
                .filter(event -> event.type() == ExecutionEventType.NODE_STARTED)
                .count());
        return joinRuns.get();
    }

    private static BehaviorRegistry tagged() {
        var registry = new BehaviorRegistry();
        for (int index = 0; index < 4; index++) {
            String branch = "b" + index;
            registry.register(branch, message ->
                    CompletableFuture.completedFuture(NodeResult.continueWith(branch + ":" + message.payload())));
        }
        return registry;
    }
}
