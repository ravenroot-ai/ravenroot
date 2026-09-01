package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeRuntimeNatureProperty;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.Mailbox;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.Scheduler;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the elastic view's per-node number must mean.
 *
 * <p>The quantity is precise: the number is <em>the actor's instantiations</em>
 * — always 1 for a nature that is a singleton however much traffic crosses it, and the sum of the
 * instances for a nature that runs in parallel — because the question it answers is "how much work is
 * this role carrying right now". The count of arrivals is a different quantity and gets its own field.
 *
 * <p><b>Why the divergence is demonstrated on the resident nature and not on {@code WORKER}.</b> An
 * ordinary worker node mints one instance per invocation, so its live-instance count and its
 * in-flight-arrival count hold the same value by construction — the eager-worker defect
 * describes ("queued behind one") is no longer reachable there. It is still reachable, unchanged, for
 * a nature that is resident by contract: {@code GraphRunner} spawns exactly one actor per resident node
 * at construction and every arrival shares it. So a resident node under concurrent load is the state in
 * which the two quantities genuinely disagree, and {@link #tenArrivalsQueuedBehindOneResidentActorAreNotTenInstances}
 * distinguishes the live-instance metric from the arrival counter: the resident case must report
 * one live instance for ten queued arrivals. The {@code WORKER} case below asserts
 * the agreement, which is a real property and not a tautology: it is what proves the new number is
 * being read from the instance registry rather than from the arrival counter that happens to match it.
 */
class ElasticViewInstanceCountTest {

    private static final String RESIDENT_BEHAVIOR = "resident-probe";
    private static final int ARRIVALS = 10;

    /**
     * Ten concurrent arrivals at one resident node, with a single thread to run them.
     *
     * <p>Ten traversals are dispatched while the one resident actor is blocked, so ten arrivals are
     * in flight and exactly one actor exists. Reading the arrival count for both values reports
     * "10 active", which is the mislabelling: it reports the depth of the queue as the size of the
     * workforce.
     */
    @Test
    void tenArrivalsQueuedBehindOneResidentActorAreNotTenInstances() throws Exception {
        var release = new CountDownLatch(1);
        var arrived = new CountDownLatch(ARRIVALS);
        // One thread, so nine of the ten arrivals are provably waiting rather than running.
        ExecutorService pool = Executors.newFixedThreadPool(1);
        var monitor = new ExecutionMonitor();
        var started = new CopyOnWriteArrayList<ExecutionEvent>();
        monitor.subscribe(event -> {
            if (event.type() == ExecutionEventType.NODE_STARTED && "probe".equals(event.nodeId())) {
                started.add(event);
                arrived.countDown();
            }
        });

        var engine = new PooledEngine(pool);
        var graph = GraphManager.from(residentProbeGraph());
        var traversals = new CopyOnWriteArrayList<CompletableFuture<?>>();
        try (var runner = new GraphRunner(graph, engine, blockingRegistry(release), monitor)) {
            for (int i = 0; i < ARRIVALS; i++) {
                traversals.add(runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture());
            }
            assertTrue(arrived.await(30, TimeUnit.SECONDS),
                    "all " + ARRIVALS + " arrivals must have been dispatched before the counts are read");

            int peakInstances = started.stream().mapToInt(ExecutionEvent::activeInstances).max().orElse(0);
            int peakArrivals = started.stream().mapToInt(ExecutionEvent::inFlightArrivals).max().orElse(0);

            assertEquals(1, peakInstances,
                    "a resident node has exactly one actor by construction -- GraphRunner spawns it once "
                            + "and every arrival shares it -- so the number the view shows must stay 1 "
                            + "however many traversals cross it. Reading " + peakInstances + " means the "
                            + "arrival counter is still being reported as the instance count");
            assertEquals(ARRIVALS, peakArrivals,
                    "the arrivals are real and must not be lost: they are the second number, reported "
                            + "under its own name");
            assertTrue(peakArrivals > peakInstances,
                    "the two quantities must be able to disagree, or nothing distinguishes them: "
                            + peakArrivals + " arrivals queued behind " + peakInstances + " instance");

            release.countDown();
            CompletableFuture.allOf(traversals.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            engine.close();
            graph.close();
        }
    }

    /**
     * The same load on the default nature, where the two numbers agree by construction.
     *
     * <p>Ten concurrent invocations of a {@code WORKER} node are ten live instances, so "instances" and
     * "arrivals" coincide numerically. The assertion is still worth making: it is what shows the view's
     * number tracks the registry rather than merely happening to equal it, because the same run also
     * pins the resident case above to 1 from the same code path.
     */
    @Test
    void tenConcurrentInvocationsOfAWorkerNodeAreTenInstances() throws Exception {
        var release = new CountDownLatch(1);
        var arrived = new CountDownLatch(ARRIVALS);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        var monitor = new ExecutionMonitor();
        var started = new CopyOnWriteArrayList<ExecutionEvent>();
        monitor.subscribe(event -> {
            if (event.type() == ExecutionEventType.NODE_STARTED && "probe".equals(event.nodeId())) {
                started.add(event);
                arrived.countDown();
            }
        });

        var engine = new PooledEngine(pool);
        var graph = GraphManager.from(workerProbeGraph());
        var traversals = new CopyOnWriteArrayList<CompletableFuture<?>>();
        try (var runner = new GraphRunner(graph, engine, blockingRegistry(release), monitor)) {
            for (int i = 0; i < ARRIVALS; i++) {
                traversals.add(runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture());
            }
            assertTrue(arrived.await(30, TimeUnit.SECONDS), "all arrivals must have been dispatched");

            int peakInstances = started.stream().mapToInt(ExecutionEvent::activeInstances).max().orElse(0);
            int peakArrivals = started.stream().mapToInt(ExecutionEvent::inFlightArrivals).max().orElse(0);

            assertEquals(ARRIVALS, peakInstances,
                    "each invocation of a WORKER node gets its own instance, so ten concurrent "
                            + "invocations really are ten live instances -- a scarce thread pool makes them "
                            + "wait, it does not make them fewer");
            assertEquals(ARRIVALS, peakArrivals, "and ten arrivals are in flight for the same node");

            release.countDown();
            CompletableFuture.allOf(traversals.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            engine.close();
            graph.close();
        }
    }

    /**
     * The instance count falls back to zero once the work is done, on the {@code WORKER} node's
     * terminal event.
     *
     * <p>A count that only ever rises is a leak indicator, not a workload indicator, and the view sizes
     * its nodes by this number: one that never came down would leave a finished graph looking permanently
     * busy. The resident nature's terminal event is a genuinely different case, asserted separately by
     * {@link #theResidentInstanceCountStaysAtOneOnTheNodesTerminalEvent} rather than reused here: a
     * resident actor is never released between invocations, so its terminal event must report 1, not 0
     * — the opposite of what this test pins.
     */
    @Test
    void theInstanceCountReturnsToZeroWhenTheWorkerInstanceIsReleased() throws Exception {
        var monitor = new ExecutionMonitor();
        var terminal = new CopyOnWriteArrayList<ExecutionEvent>();
        monitor.subscribe(event -> {
            if (event.type() == ExecutionEventType.NODE_COMPLETED && "probe".equals(event.nodeId())) {
                terminal.add(event);
            }
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        var engine = new PooledEngine(pool);
        var graph = GraphManager.from(workerProbeGraph());
        try (var runner = new GraphRunner(graph, engine, blockingRegistry(new CountDownLatch(0)), monitor)) {
            runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture().get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            engine.close();
            graph.close();
        }
        assertEquals(1, terminal.size(), "the probe node completed exactly once");
        assertEquals(0, terminal.get(0).activeInstances(),
                "the instance was released before the completion was published, so no instance is alive "
                        + "for this node any more");
        assertEquals(0, terminal.get(0).inFlightArrivals(),
                "and nothing is in flight for it either");
    }

    /**
     * The resident nature's terminal event pins the invariant that its live-instance count is always
     * 1, never 0.
     *
     * <p>This is the counterpart the previous test does not cover: a resident node's one actor is
     * spawned once by {@code GraphRunner} and lives for the runner's whole lifetime — completing an
     * invocation retires that arrival, not the actor. Before this test, nothing pinned the resident
     * node's terminal event at all; the only assertion on a terminal event exercised {@code WORKER},
     * whose actor really is released per invocation and really does drop to 0. Asserting only the
     * value, without pinning the ordering that produces it, would leave this passing even if the
     * resident path called {@code release}-then-publish the way the WORKER path does:
     * this is exactly the regression under test, and reusing the WORKER nature's number (0)
     * here would make that regression pass silently instead of failing this test.
     */
    @Test
    void theResidentInstanceCountStaysAtOneOnTheNodesTerminalEvent() throws Exception {
        var monitor = new ExecutionMonitor();
        var terminal = new CopyOnWriteArrayList<ExecutionEvent>();
        monitor.subscribe(event -> {
            if (event.type() == ExecutionEventType.NODE_COMPLETED && "probe".equals(event.nodeId())) {
                terminal.add(event);
            }
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        var engine = new PooledEngine(pool);
        var graph = GraphManager.from(residentProbeGraph());
        try (var runner = new GraphRunner(graph, engine, blockingRegistry(new CountDownLatch(0)), monitor)) {
            runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture().get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            engine.close();
            graph.close();
        }
        assertEquals(1, terminal.size(), "the probe node completed exactly once");
        assertEquals(1, terminal.get(0).activeInstances(),
                "a resident node's actor is never released between invocations -- it was spawned once "
                        + "by GraphRunner and stays alive for the runner's whole lifetime -- so the "
                        + "terminal event of one invocation must still report the actor as instantiated. "
                        + "Reading 0 here would mean the resident and WORKER terminal paths had been made "
                        + "to agree by dropping the resident count too, which is the regression this test "
                        + "exists to catch");
        assertEquals(0, terminal.get(0).inFlightArrivals(),
                "the one arrival that was in flight has settled, so nothing is queued any more -- this "
                        + "is the number that is allowed to fall to 0, unlike activeInstances above");
    }

    // ------------------------------------------------------------------ fixtures

    /** {@code start -> probe(runtime.nature=SOURCE) -> end}. */
    private static GraphDefinition residentProbeGraph() {
        return probeGraph(Map.of(NodeRuntimeNatureProperty.NAME, NodeRuntimeNature.SOURCE.name()));
    }

    /** {@code start -> probe} with no declared nature, so the default {@code WORKER} applies. */
    private static GraphDefinition workerProbeGraph() {
        return probeGraph(Map.of());
    }

    private static GraphDefinition probeGraph(Map<String, Object> properties) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("probe", NodeKind.BEHAVIOR, RESIDENT_BEHAVIOR, properties),
                GraphNode.error("error"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "probe"),
                GraphEdge.to("probe", "end")));
    }

    /** A catalog whose one behavior may be either nature, and whose body waits on {@code release}. */
    private static BehaviorRegistry blockingRegistry(CountDownLatch release) {
        var descriptor = new NodeTypeDescriptor(RESIDENT_BEHAVIOR, "Resident probe", "Test", "d", "actor",
                false, List.of(), Set.of(), NodeRuntimeNature.WORKER,
                Set.of(NodeRuntimeNature.WORKER, NodeRuntimeNature.SOURCE));
        return new BehaviorRegistry().registerFactory(new NodeBehaviorFactory() {
            @Override
            public NodeTypeDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public NodeHandler create(GraphNode node) {
                return message -> {
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                };
            }
        });
    }

    /**
     * An engine whose {@code send} hands the body to a pool and returns immediately, so a traversal can
     * be dispatched while earlier ones are still waiting for a thread. That is the whole point: it
     * separates "arrived" from "running", which an inline engine cannot.
     *
     * <p><b>The probe node gets its own single thread, and the rest of the graph gets another pool.</b>
     * One shared single-thread pool does not model the required isolation; it defeats it: the first traversal's
     * {@code start} node would take the only thread, block there, and the other nine would never reach
     * the probe at all — leaving one arrival where the test needs ten. Separating the two makes the
     * scarcity land exactly where required, on the node under observation.
     */
    private static final class PooledEngine implements ExecutionEngine {
        private final ExecutorService probePool;
        private final ExecutorService others = Executors.newCachedThreadPool();
        private final Map<NodeRef, RavenNode> behaviours = new ConcurrentHashMap<>();
        private final AtomicInteger spawns = new AtomicInteger();

        PooledEngine(ExecutorService probePool) {
            this.probePool = probePool;
        }

        @Override
        public String id() {
            return "pooled";
        }

        @Override
        public Set<EngineCapability> capabilities() {
            return Set.of();
        }

        @Override
        public Scheduler scheduler() {
            return (delay, task) -> () -> true;
        }

        @Override
        public EngineState state() {
            return EngineState.RUNNING;
        }

        @Override
        public NodeRef spawn(String logicalName, RavenNode node) {
            spawns.incrementAndGet();
            var ref = new NodeRef(logicalName + "-" + UUID.randomUUID());
            behaviours.put(ref, node);
            return ref;
        }

        @Override
        public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
            var future = new CompletableFuture<NodeResult>();
            RavenNode node = behaviours.get(target);
            ExecutorService pool = "probe".equals(message.nodeId()) ? probePool : others;
            pool.submit(() -> {
                try {
                    node.onMessage(message, context(target)).whenComplete((result, error) -> {
                        if (error != null) {
                            future.completeExceptionally(error);
                        } else {
                            future.complete(result);
                        }
                    });
                } catch (RuntimeException failure) {
                    future.completeExceptionally(failure);
                }
            });
            return future;
        }

        @Override
        public Optional<NodeStatus> status(NodeRef target) {
            return Optional.empty();
        }

        @Override
        public CompletionStage<Void> stop(NodeRef target) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> cancel(NodeRef target) {
            return stop(target);
        }

        @Override
        public CompletionStage<Void> drain() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            others.shutdownNow();
        }

        private NodeContext context(NodeRef ref) {
            return new NodeContext() {
                @Override
                public NodeRef self() {
                    return ref;
                }

                @Override
                public Scheduler scheduler() {
                    return PooledEngine.this.scheduler();
                }

                @Override
                public Mailbox mailbox() {
                    return () -> 0;
                }

                @Override
                public ai.ravenroot.api.execution.CancellationSignal cancellation() {
                    return new ai.ravenroot.api.execution.CancellationSignal() {
                        @Override
                        public boolean cancelled() {
                            return false;
                        }

                        @Override
                        public void onCancel(Runnable listener) {
                        }
                    };
                }
            };
        }
    }
}
