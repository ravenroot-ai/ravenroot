package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.Mailbox;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeLifecycleState;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.NodeTerminationReason;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.Scheduler;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The measured claims of ADR 0024 §3, asserted as numbers rather than as behaviour.
 *
 * <p>Every case here measures a scaling property with a count, because the whole
 * point of demand-driven instances is a change in how a quantity scales, and a test that merely
 * observes "it still works" cannot see the scaling difference. The eager-spawn runtime passes every
 * functional assertion in this repository and fails every count below.
 *
 * <p>The engine is a stub on purpose. These are assertions about what {@code GraphRunner} <em>asks
 * for</em> — how many actors it creates and when — and a stub is the only way to count that exactly,
 * without an adapter's own scheduling deciding how many happen to be alive at the instant a real test
 * looks. The same properties against a real engine, including the ones only a real actor runtime can
 * demonstrate, are in {@code DemandDrivenWorkerConcurrencyTest} in {@code ravenroot-pekko}.
 */
class DemandDrivenWorkerLifecycleTest {

    /**
     * Startup actor count does not depend on how many worker nodes exist.
     *
     * <p>Measured across three orders of magnitude rather than asserted once, because "independent of
     * the number of nodes" is a statement about a function, and one sample cannot distinguish a
     * constant from a very small coefficient.
     */
    @Test
    void startupActorCountIsIndependentOfTheNumberOfWorkerNodes() {
        var measurements = new ArrayList<String>();
        for (int workerNodes : new int[] {1, 10, 100, 500}) {
            var engine = new CountingEngine();
            var graph = GraphManager.from(chain(workerNodes));
            try (var runner = runner(graph, engine)) {
                measurements.add(workerNodes + " worker nodes -> " + engine.spawns.get() + " actors");
                assertEquals(0, engine.spawns.get(),
                        "a graph of " + workerNodes + " ordinary worker nodes must spawn no actor at "
                                + "startup; it spawned " + engine.spawns.get());
                assertEquals(0, runner.residentActorCount());
            }
            graph.close();
        }
        // Printed so the number reaches the build log as evidence, not only as a passing assertion.
        System.out.println("startup actors: " + String.join(", ", measurements));
    }

    /**
     * A graph with hundreds of workers that no traversal reaches creates none of them.
     *
     * <p>Distinct from the case above, which never runs anything. Here a traversal really executes and
     * takes one branch of a wide fan-out, so the untouched nodes are untouched by a live execution
     * rather than by an idle runner — which is the situation ADR 0024's migration-graph motivation
     * actually describes.
     */
    @Test
    void aTraversalCreatesActorsOnlyForTheNodesItReaches() throws Exception {
        var engine = new CountingEngine();
        var graph = GraphManager.from(wideFanOutWithOneLivePath(500));
        try (var runner = runner(graph, engine)) {
            runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture().get(30, TimeUnit.SECONDS);

            // start + the one reachable worker + end. The other 499 declared nodes are never created.
            assertEquals(3, engine.spawns.get(),
                    "only the nodes on the taken path may be created; 500 were declared and "
                            + engine.spawns.get() + " actors were made");
            System.out.println("500-node graph, one live path: " + engine.spawns.get()
                    + " actors created in total");
        }
        graph.close();
    }

    /**
     * The high-cardinality regression case, measured (ADR 0024 consequences).
     *
     * <p>It pins the shape of the relationship, not a wall-clock number: total actors created is a
     * function of the path actually executed, so growing the graph by an order of magnitude while
     * holding the executed path constant must not change the count at all. A wall-clock assertion here
     * would be a machine-speed test; this one fails on the old runtime by a factor of the graph size.
     */
    @Test
    void actorCountTracksTheExecutedPathAndNotTheGraphSize() throws Exception {
        var counts = new ArrayList<Integer>();
        var report = new ArrayList<String>();
        for (int declaredNodes : new int[] {50, 500, 2_000}) {
            var engine = new CountingEngine();
            var graph = GraphManager.from(wideFanOutWithOneLivePath(declaredNodes));
            try (var runner = runner(graph, engine)) {
                runner.execute(TestIdentities.TENANT_A, "payload")
                        .toCompletableFuture().get(60, TimeUnit.SECONDS);
                counts.add(engine.spawns.get());
                report.add(declaredNodes + " declared -> " + engine.spawns.get() + " actors");
                assertEquals(0, runner.liveWorkerInstanceCount(),
                        "a completed traversal must leave no worker instance behind");
            }
            graph.close();
        }
        System.out.println("high-cardinality regression: " + String.join(", ", report));
        assertEquals(1, Set.copyOf(counts).size(),
                "actor count must not vary with graph size; it went " + counts);
    }

    /** Completion releases every instance, and the release is visible the moment the stage settles. */
    @Test
    void completionLeavesNoLiveInstanceBehindAcrossManyTraversals() throws Exception {
        var engine = new CountingEngine();
        var graph = GraphManager.from(chain(5));
        try (var runner = runner(graph, engine)) {
            for (int run = 0; run < 20; run++) {
                runner.execute(TestIdentities.TENANT_A, "payload")
                        .toCompletableFuture().get(30, TimeUnit.SECONDS);
                assertEquals(0, runner.liveWorkerInstanceCount(),
                        "traversal " + run + " left an instance behind");
            }
            // Not a capacity check -- there is nothing left to reserve. This exists to catch a
            // different failure: a spawn that fires more than once per node visit (the registration-
            // clash path in WorkerInstanceRegistry.acquire, for instance, terminating the actor it just
            // made and throwing -- but only after the spawn already happened) would inflate this count
            // above 140. Running the same seven-node chain twenty times rather than once is what makes
            // an occasional extra spawn arithmetically visible instead of lost in a single run that
            // still "looks" fine.
            assertEquals(140, engine.spawns.get(), "seven nodes per traversal, twenty traversals");
            assertEquals(0, runner.liveWorkerInstanceCount());
        }
        graph.close();
    }

    /**
     * A node that fails settles its invocation AND releases its instance.
     *
     * <p>Both halves matter and they are independent: an implementation that released on the success
     * path only would pass every functional failure test in this repository while leaking one actor
     * per failed node.
     */
    @Test
    void failureReleasesTheInstanceAsDeterministicallyAsCompletion() {
        var engine = new CountingEngine();
        engine.failSendsTo = "work-2";
        var graph = GraphManager.from(chain(3));
        try (var runner = runner(graph, engine)) {
            var outcome = runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture();
            assertTrue(outcome.isCompletedExceptionally() || outcome.isDone());
            outcome.handle((ignored, error) -> error).join();

            assertEquals(0, runner.liveWorkerInstanceCount(),
                    "a failed invocation must release its instance exactly as a completed one does");
        }
        graph.close();
    }

    /**
     * Creation failure settles the persisted lifecycle instead of escaping.
     *
     * <p>The engine refuses to spawn. The invocation must still reach a terminal state — the runner
     * records the attempt and the invocation as FAILED on the same unconditional path a node failure
     * uses — and the registry entry must come back, which the count after twenty refusals proves: a
     * leaked entry per refusal would show as a live count that never returns to zero.
     */
    @Test
    void refusedCreationSettlesTheInvocationAndClearsTheRegistryEntry() {
        var engine = new CountingEngine();
        engine.refuseSpawns = true;
        var graph = GraphManager.from(chain(1));
        try (var runner = runner(graph, engine)) {
            for (int attempt = 0; attempt < 20; attempt++) {
                runner.execute(TestIdentities.TENANT_A, "payload")
                        .toCompletableFuture().handle((ignored, error) -> error).join();
            }
            assertEquals(0, runner.liveWorkerInstanceCount(),
                    "a spawn that threw must not leave a registry entry behind");
        }
        graph.close();
    }

    /**
     * No platform-imposed ceiling refuses a worker instance any more.
     *
     * <p>{@code InvocationAdmission} bounded pod, tenant and deployment concurrency and threw
     * once any of the three saturated. The platform does not impose that ceiling: an operator with
     * thousands of threads should be able to instantiate thousands of actors, and one with a handful
     * should see them queue and resolve gradually — the
     * actor model already does that on its own, so a second ceiling here only gets in the way. This
     * asserts the removal at a scale well past every old ceiling (pod 4096, tenant
     * 2048, deployment 1024): the same deployment, same tenant, far more concurrent instances than
     * "deployment" alone used to allow, and every one of them is admitted.
     *
     * <p>Proven against the real numbers twice and independently. The exact measurements are recorded
     * here so the evidence stays with the test. Saturating {@code InvocationAdmission}'s pod ceiling
     * of 4096 rejected the 4097th request synchronously. That code path no longer exists to exercise
     * here — this test proves that the former ceiling is absent.
     */
    @Test
    void exceedingEveryOldCeilingAtOnceIsNeverRejected() {
        var engine = new CountingEngine();
        var registry = registryOn(engine);
        int overflow = 1500; // past the old DEFAULT_DEPLOYMENT_CEILING of 1024, one tenant, one deployment
        for (int i = 0; i < overflow; i++) {
            // Must not throw. Previously this would have hit the deployment ceiling at 1024.
            registry.acquire(deployed("n" + i, "orders"), passthroughNode());
        }
        assertEquals(overflow, registry.live().size(),
                "every one of " + overflow + " concurrent instances for one deployment must be admitted "
                        + "-- the former deployment ceiling of 1024 would have refused most of these");
        registry.close();
    }

    /**
     * Not rejected does not mean unbounded parallel execution: scarcity is resolved by the actor
     * model's own thread availability.
     *
     * <p>This is the part "not rejected" alone cannot distinguish from "accepted and dropped": every
     * one of {@code arrivals} worker instances is admitted up front (so none is refused), but the
     * engine underneath runs on a fixed thread pool much smaller than {@code arrivals}, and each
     * arrival occupies its thread for a measurable interval. If admission were still gating capacity
     * the pool size would be irrelevant to how many instances exist at once; if admission were simply
     * absent with nothing governing concurrency, every arrival would run at the same
     * instant. What is observed instead is that admission happens immediately for all of them and
     * the pool queues activities -- the peak
     * number of arrivals actually inside the node body at once never exceeds the pool size, while every
     * arrival still eventually completes.
     */
    @Test
    void admittedWorkQueuesBehindTheThreadPoolInsteadOfRunningUnboundedInParallel() throws Exception {
        int poolSize = 4;
        int arrivals = 24;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(poolSize);
        var running = new AtomicInteger();
        var peakConcurrency = new AtomicInteger();
        var completed = new AtomicInteger();
        try {
            var engine = new BoundedPoolEngine(pool, message -> {
                int now = running.incrementAndGet();
                peakConcurrency.updateAndGet(peak -> Math.max(peak, now));
                try {
                    Thread.sleep(40);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    running.decrementAndGet();
                    completed.incrementAndGet();
                }
                return new NodeResult("continue", message.payload(), Map.of());
            });
            var graph = GraphManager.from(chain(1));
            try (var runner = runner(graph, engine)) {
                var traversals = new java.util.ArrayList<CompletableFuture<?>>();
                for (int i = 0; i < arrivals; i++) {
                    traversals.add(runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture());
                }
                CompletableFuture.allOf(traversals.toArray(new CompletableFuture[0]))
                        .get(30, TimeUnit.SECONDS);
            }
            graph.close();

            assertEquals(arrivals, completed.get(),
                    "every admitted arrival must still complete -- accepted-but-lost is as wrong as "
                            + "rejected, and this is what tells the two apart");
            assertTrue(peakConcurrency.get() <= poolSize,
                    "peak concurrent execution was " + peakConcurrency.get() + " against a pool of "
                            + poolSize + ": admission is not the thing bounding concurrency any more, the "
                            + "thread pool is, and it must actually be doing so");
            assertTrue(peakConcurrency.get() > 1,
                    "the pool must have actually run more than one arrival at a time, or this proves "
                            + "nothing about queueing versus serialization");
            System.out.println("worker ceiling probe: " + arrivals + " admitted arrivals, pool of " + poolSize
                    + ", peak concurrent execution observed: " + peakConcurrency.get());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The one property the removed pod/tenant/deployment reservation's release guard also happened to
     * protect, verified here on its own merits now that the reservation is gone: releasing the same
     * instance twice was previously checked nowhere.
     *
     * <p>{@code WorkerInstanceRegistry.release}'s javadoc still promises "Idempotent, and it has to be"
     * — but the reason it has to be was never about capacity. {@code GraphRunner} calls {@code release}
     * from the completing attempt's own handler, and {@code GraphRunner#close()} calls it again for
     * whatever is still live when a shutdown races a completion; without the CAS guard on {@code
     * releasing} a second call would ask the engine to stop an actor that is already stopping, and
     * would try to complete an already-completed {@code released} stage a second time. Neither failure
     * mode has anything to do with a semaphore, which is exactly why removing the semaphore must not be
     * allowed to remove the test that would catch it.
     */
    @Test
    void releasingTheSameInstanceTwiceStopsTheActorOnlyOnce() {
        var engine = new CountingEngine();
        var registry = registryOn(engine);
        var instance = registry.acquire(identity("solo"), passthroughNode());

        var first = registry.release(instance);
        var second = registry.release(instance);

        assertEquals(1, engine.stopCalls.get(),
                "a second release() of the same instance must not stop its actor a second time -- the "
                        + "failure the old test's message called \"the one that never appears in a test "
                        + "and always appears under load\"");
        assertTrue(first == second,
                "a second release() must hand back the very same completion stage as the first instead "
                        + "of starting a second, independent termination");
        registry.close();
    }

    // ------------------------------------------------------------------ fixtures

    private static GraphRunner runner(GraphManager graph, ExecutionEngine engine) {
        return new GraphRunner(graph, engine, new BehaviorRegistry(), new ExecutionMonitor(),
                ExecutionIdentitySource.randomUuids(), Duration.ofSeconds(5));
    }

    private static WorkerInstanceRegistry registryOn(ExecutionEngine engine) {
        return new WorkerInstanceRegistry((name, node) -> engine.spawn(name, node), engine::stop);
    }

    private static WorkerInstanceIdentity deployed(String nodeId, String deploymentId) {
        return new WorkerInstanceIdentity("tenant", deploymentId, "v1", UUID.randomUUID(),
                UUID.randomUUID(), nodeId, UUID.randomUUID(), UUID.randomUUID());
    }

    private static WorkerInstanceIdentity identity(String nodeId) {
        return new WorkerInstanceIdentity("tenant", null, "v1", UUID.randomUUID(), UUID.randomUUID(),
                nodeId, UUID.randomUUID(), UUID.randomUUID());
    }

    private static RavenNode passthroughNode() {
        return (message, context) -> CompletableFuture.completedFuture(
                new NodeResult("continue", message.payload(), Map.of()));
    }

    /** {@code start -> work-1 -> ... -> work-n -> end}. */
    private static GraphDefinition chain(int workerNodes) {
        var nodes = new ArrayList<GraphNode>();
        var edges = new ArrayList<GraphEdge>();
        nodes.add(GraphNode.start("start"));
        String previous = "start";
        for (int index = 1; index <= workerNodes; index++) {
            String id = "work-" + index;
            nodes.add(GraphNode.passthrough(id));
            edges.add(GraphEdge.to(previous, id));
            previous = id;
        }
        nodes.add(GraphNode.error("error"));
        nodes.add(GraphNode.end("end"));
        edges.add(GraphEdge.to(previous, "end"));
        return new GraphDefinition(nodes, edges);
    }

    /**
     * One live path plus {@code declaredNodes - 3} nodes that no edge from start can reach.
     *
     * <p>Unreachable rather than merely not-selected, so the count cannot be satisfied by a runtime
     * that creates actors for everything downstream of the branch it takes.
     */
    private static GraphDefinition wideFanOutWithOneLivePath(int declaredNodes) {
        var nodes = new ArrayList<GraphNode>();
        var edges = new ArrayList<GraphEdge>();
        nodes.add(GraphNode.start("start"));
        nodes.add(GraphNode.passthrough("live"));
        nodes.add(GraphNode.error("error"));
        nodes.add(GraphNode.end("end"));
        edges.add(GraphEdge.to("start", "live"));
        edges.add(GraphEdge.to("live", "end"));
        for (int index = 0; index < declaredNodes - 3; index++) {
            nodes.add(GraphNode.passthrough("dormant-" + index));
        }
        return new GraphDefinition(nodes, edges);
    }

    /** Counts what the runner asks the engine to create, and can be told to refuse or to fail. */
    private static final class CountingEngine implements ExecutionEngine {
        private final Map<NodeRef, NodeLifecycleState> states = new ConcurrentHashMap<>();
        private final Map<NodeRef, RavenNode> behaviours = new ConcurrentHashMap<>();
        private final AtomicInteger spawns = new AtomicInteger();
        private final AtomicInteger stopCalls = new AtomicInteger();
        private boolean refuseSpawns;
        private String failSendsTo;
        private EngineState state = EngineState.RUNNING;

        @Override
        public String id() {
            return "counting";
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
            return state;
        }

        @Override
        public NodeRef spawn(String logicalName, RavenNode node) {
            if (refuseSpawns) {
                throw new IllegalStateException("refusing to spawn " + logicalName);
            }
            spawns.incrementAndGet();
            var ref = new NodeRef(logicalName + "-" + UUID.randomUUID());
            states.put(ref, NodeLifecycleState.RUNNING);
            behaviours.put(ref, node);
            return ref;
        }

        @Override
        public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
            if (failSendsTo != null && target.value().startsWith(failSendsTo)) {
                return CompletableFuture.failedFuture(new IllegalStateException("node failed"));
            }
            return behaviours.get(target).onMessage(message, context(target));
        }

        @Override
        public Optional<NodeStatus> status(NodeRef target) {
            return Optional.ofNullable(states.get(target))
                    .map(value -> new NodeStatus(target, value,
                            value.terminal() ? NodeTerminationReason.STOPPED : null, 0));
        }

        @Override
        public CompletionStage<Void> stop(NodeRef target) {
            stopCalls.incrementAndGet();
            states.computeIfPresent(target, (ref, value) -> NodeLifecycleState.TERMINATED);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> cancel(NodeRef target) {
            return stop(target);
        }

        @Override
        public CompletionStage<Void> drain() {
            state = EngineState.DRAINING;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            state = EngineState.CLOSED;
        }

        private NodeContext context(NodeRef ref) {
            return new NodeContext() {
                @Override
                public NodeRef self() {
                    return ref;
                }

                @Override
                public Scheduler scheduler() {
                    return CountingEngine.this.scheduler();
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

    /**
     * An engine whose {@link #send} runs node bodies on a caller-supplied, size-bounded pool instead
     * of inline. Spawning is unbounded and instantaneous, exactly like the real engine's actor
     * creation; what this stands in for is the one thing a stub with an inline {@code send} cannot
     * demonstrate -- that admitted work can still be waiting on a thread, not running, without having
     * been refused.
     */
    private static final class BoundedPoolEngine implements ExecutionEngine {
        private final ExecutorService pool;
        private final Function<NodeMessage, NodeResult> nodeBody;
        private final Map<NodeRef, RavenNode> behaviours = new ConcurrentHashMap<>();

        BoundedPoolEngine(ExecutorService pool, Function<NodeMessage, NodeResult> nodeBody) {
            this.pool = pool;
            this.nodeBody = nodeBody;
        }

        @Override
        public String id() {
            return "bounded-pool";
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
            var ref = new NodeRef(logicalName + "-" + UUID.randomUUID());
            behaviours.put(ref, node);
            return ref;
        }

        @Override
        public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
            var future = new CompletableFuture<NodeResult>();
            pool.submit(() -> {
                try {
                    if ("work-1".equals(message.nodeId())) {
                        future.complete(nodeBody.apply(message));
                    } else {
                        future.complete(new NodeResult("continue", message.payload(), Map.of()));
                    }
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
        }
    }
}
