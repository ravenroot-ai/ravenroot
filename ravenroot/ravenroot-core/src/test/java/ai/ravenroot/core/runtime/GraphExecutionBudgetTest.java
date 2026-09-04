package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.catalog.NodeRetryProperty;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphMlLimits;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.JoinSemantics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphExecutionBudgetTest {

    @Test
    void programmaticNodeLimitRefusesBeforeAnyActorIsSpawned() {
        var engine = new JoinTestEngine();
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), GraphNode.behavior("work", "missing"),
                GraphNode.end("end")), List.of(GraphEdge.to("start", "work"), GraphEdge.to("work", "end")));
        GraphExecutionLimits limits = limits(graphMl(2, 25_000, 100_000), PayloadLimits.DEFAULTS,
                64, 100_000, 100_000, 64 * 1024 * 1024L);
        try (var manager = GraphManager.from(graph); engine) {
            GraphExecutionLimitException refused = assertThrows(GraphExecutionLimitException.class,
                    () -> runner(manager, engine, new BehaviorRegistry(), limits));
            assertEquals(GraphExecutionLimitException.Reason.NODES, refused.reason());
            assertEquals(0, engine.spawnCount());
        }
    }

    @Test
    void configuredFanOutRefusesBeforeAnyActorIsSpawned() {
        var engine = new JoinTestEngine();
        var graph = fanOut(3);
        GraphExecutionLimits limits = limits(GraphMlLimits.DEFAULTS, PayloadLimits.DEFAULTS,
                2, 100_000, 100_000, 64 * 1024 * 1024L);
        try (var manager = GraphManager.from(graph); engine) {
            GraphExecutionLimitException refused = assertThrows(GraphExecutionLimitException.class,
                    () -> runner(manager, engine, new BehaviorRegistry(), limits));
            assertEquals(GraphExecutionLimitException.Reason.FAN_OUT, refused.reason());
            assertEquals(0, engine.spawnCount());
        }
    }

    @Test
    @Timeout(10)
    void fanOutAmplificationIsReservedAtomicallyBeforeTheFirstChild() {
        var engine = new JoinTestEngine();
        GraphExecutionLimits limits = limits(GraphMlLimits.DEFAULTS, PayloadLimits.DEFAULTS,
                4, 100_000, 2, 64 * 1024 * 1024L);
        try (var manager = GraphManager.from(fanOut(3));
             var runner = runner(manager, engine, new BehaviorRegistry(), limits);
             engine) {
            Throwable failure = assertThrows(CompletionException.class,
                    () -> runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture().join());
            GraphExecutionLimitException refused = findLimit(failure);
            assertEquals(GraphExecutionLimitException.Reason.AMPLIFIED_DELIVERIES, refused.reason());
            assertEquals(1, engine.spawnCount(), "only the already-admitted start actor may have existed");
        }
    }

    @Test
    @Timeout(10)
    void cycleReentryConsumesOneSharedTraversalStepBudget() {
        var engine = new JoinTestEngine();
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.behavior("loop", "missing"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "loop"), GraphEdge.to("loop", "loop"),
                        GraphEdge.to("loop", "end")),
                Map.of(JoinSemantics.MARKER_PROPERTY, JoinSemantics.DECLARED));
        GraphExecutionLimits limits = limits(GraphMlLimits.DEFAULTS, PayloadLimits.DEFAULTS,
                4, 9, 100, 64 * 1024 * 1024L);
        try (var manager = GraphManager.from(graph);
             var runner = runner(manager, engine, new BehaviorRegistry(), limits);
             engine) {
            Throwable failure = assertThrows(CompletionException.class,
                    () -> runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture().join());
            GraphExecutionLimitException refused = findLimit(failure);
            assertEquals(GraphExecutionLimitException.Reason.TRAVERSAL_STEPS, refused.reason());
            if (engine.spawnCount() > 9) {
                throw new AssertionError("cycle spawned past its step ceiling: " + engine.spawnCount());
            }
        }
    }

    @Test
    @Timeout(10)
    void oversizedNodeOutputFailsBeforeItsSuccessorIsSpawned() {
        var engine = new JoinTestEngine();
        var registry = new BehaviorRegistry().register("large", message ->
                java.util.concurrent.CompletableFuture.completedFuture(
                        NodeResult.continueWith("x".repeat(128))));
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.behavior("large", "large"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "large"), GraphEdge.to("large", "end")));
        var payload = new PayloadLimits(64, 32, 1_000, 10_000, 32 * 1024, 256);
        GraphExecutionLimits limits = limits(GraphMlLimits.DEFAULTS, payload,
                4, 100, 100, 1024 * 1024);
        try (var manager = GraphManager.from(graph);
             var runner = runner(manager, engine, registry, limits);
             engine) {
            assertThrows(CompletionException.class,
                    () -> runner.execute(TestIdentities.TENANT_A, "ok").toCompletableFuture().join());
            assertEquals(2, engine.spawnCount(), "the end actor must not be allocated for rejected output");
        }
    }

    @Test
    void liveActorAndInFlightReservationsRefuseAtomically() {
        GraphExecutionLimits limits = new GraphExecutionLimits(GraphMlLimits.DEFAULTS, PayloadLimits.DEFAULTS,
                4, 256, 1, 2, 1, 100, 100, 100, 8);
        var budget = new ExecutionBudget(limits);

        ExecutionBudget.Actor actor = budget.reserveActor();
        assertEquals(GraphExecutionLimitException.Reason.LIVE_ACTORS,
                assertThrows(GraphExecutionLimitException.class, budget::reserveActor).reason());
        actor.close();
        budget.reserveActor().close();

        ExecutionBudget.Hop root = budget.reserveRoot(1);
        assertEquals(GraphExecutionLimitException.Reason.IN_FLIGHT_HOPS,
                assertThrows(GraphExecutionLimitException.class, () -> budget.reserveFanOut(2, 1)).reason());
        budget.reserveFanOut(1, 1).getFirst().close();
        root.close();
    }

    @Test
    void actorCapacityRemainsChargedUntilTerminationActuallySettles() {
        GraphExecutionLimits defaults = GraphExecutionLimits.DEFAULTS;
        var limits = new GraphExecutionLimits(defaults.graphMl(), defaults.payload(),
                defaults.maxFanOut(), defaults.maxResidentActors(), 1,
                defaults.maxInFlightHopsPerTraversal(), defaults.maxQueuedAdmissionsPerNode(),
                defaults.maxTraversalSteps(), defaults.maxAmplifiedDeliveries(),
                defaults.maxCumulativePayloadBytes(), defaults.maxRecoveryDeliveriesPerAttempt());
        var budget = new ExecutionBudget(limits);
        var terminated = new CompletableFuture<Void>();
        var registry = new WorkerInstanceRegistry(
                (name, node) -> new ai.ravenroot.api.execution.NodeRef(name), ignored -> terminated);
        var identity = new WorkerInstanceIdentity("tenant", null, "v1", UUID.randomUUID(),
                UUID.randomUUID(), "node", UUID.randomUUID(), UUID.randomUUID());
        var instance = registry.acquire(identity,
                (message, context) -> CompletableFuture.completedFuture(NodeResult.continueWith(null)),
                budget.reserveActor());

        var release = registry.release(instance).toCompletableFuture();
        assertEquals(GraphExecutionLimitException.Reason.LIVE_ACTORS,
                assertThrows(GraphExecutionLimitException.class, budget::reserveActor).reason(),
                "asking an actor to stop must not release its capacity before termination");

        terminated.complete(null);
        release.join();
        budget.reserveActor().close();
        registry.close();
    }

    @Test
    void traversalActorCapacityRemainsChargedUntilRetirementIsConfirmed() {
        GraphExecutionLimits defaults = GraphExecutionLimits.DEFAULTS;
        var limits = new GraphExecutionLimits(defaults.graphMl(), defaults.payload(),
                defaults.maxFanOut(), defaults.maxResidentActors(), 1,
                defaults.maxInFlightHopsPerTraversal(), defaults.maxQueuedAdmissionsPerNode(),
                defaults.maxTraversalSteps(), defaults.maxAmplifiedDeliveries(),
                defaults.maxCumulativePayloadBytes(), defaults.maxRecoveryDeliveriesPerAttempt());
        var budget = new ExecutionBudget(limits);
        var registry = new TraversalInstanceRegistry(
                (name, node) -> new ai.ravenroot.api.execution.NodeRef(name));
        UUID traversalId = UUID.randomUUID();
        var identity = new TraversalInstanceIdentity("tenant", null, "v1", UUID.randomUUID(),
                traversalId, "node");
        registry.acquire(identity,
                (message, context) -> CompletableFuture.completedFuture(NodeResult.continueWith(null)),
                budget::reserveActor);

        var retiring = registry.deregister(traversalId);
        assertEquals(GraphExecutionLimitException.Reason.LIVE_ACTORS,
                assertThrows(GraphExecutionLimitException.class, budget::reserveActor).reason());

        registry.retired(retiring);
        budget.reserveActor().close();
        registry.deregisterAll();
    }

    @Test
    void retiringWorkerCapacityIsSharedAcrossSuccessiveTraversalBudgets() {
        GraphExecutionLimits limits = actorLimit(1);
        var runnerCapacity = new RunnerActorCapacity(1);
        var firstBudget = new ExecutionBudget(limits, runnerCapacity);
        var secondBudget = new ExecutionBudget(limits, runnerCapacity);
        var terminated = new CompletableFuture<Void>();
        var registry = new WorkerInstanceRegistry(
                (name, node) -> new ai.ravenroot.api.execution.NodeRef(name), ignored -> terminated);
        var first = registry.acquire(workerIdentity(), successfulNode(), firstBudget.reserveActor());

        registry.release(first);
        assertEquals(1, runnerCapacity.retained());
        assertEquals(GraphExecutionLimitException.Reason.LIVE_ACTORS,
                assertThrows(GraphExecutionLimitException.class, secondBudget::reserveActor).reason(),
                "a fresh traversal must count the previous traversal's retiring actor");

        terminated.complete(null);
        secondBudget.reserveActor().close();
        assertEquals(0, runnerCapacity.retained());
        registry.close();
    }

    @Test
    void retiringTraversalNatureCapacityIsSharedAcrossSuccessiveTraversalBudgets() {
        GraphExecutionLimits limits = actorLimit(1);
        var runnerCapacity = new RunnerActorCapacity(1);
        var firstBudget = new ExecutionBudget(limits, runnerCapacity);
        var secondBudget = new ExecutionBudget(limits, runnerCapacity);
        var registry = new TraversalInstanceRegistry(
                (name, node) -> new ai.ravenroot.api.execution.NodeRef(name));
        UUID traversal = UUID.randomUUID();
        registry.acquire(new TraversalInstanceIdentity("tenant", null, "v1", UUID.randomUUID(),
                        traversal, "node"), successfulNode(), firstBudget::reserveActor);

        var retiring = registry.deregister(traversal);
        assertEquals(GraphExecutionLimitException.Reason.LIVE_ACTORS,
                assertThrows(GraphExecutionLimitException.class, secondBudget::reserveActor).reason());

        registry.retired(retiring);
        secondBudget.reserveActor().close();
        assertEquals(0, runnerCapacity.retained());
        registry.deregisterAll();
    }

    @Test
    @Timeout(10)
    void workerPermitIsReleasedExactlyOnceWhenTerminationAndRunnerCloseRace() {
        var runnerCapacity = new RunnerActorCapacity(1);
        var budget = new ExecutionBudget(actorLimit(1), runnerCapacity);
        var terminated = new CompletableFuture<Void>();
        var registry = new WorkerInstanceRegistry(
                (name, node) -> new ai.ravenroot.api.execution.NodeRef(name), ignored -> terminated);
        var instance = registry.acquire(workerIdentity(), successfulNode(), budget.reserveActor());

        CompletableFuture<Void> released = registry.release(instance).toCompletableFuture();
        registry.deregisterAll();
        terminated.complete(null);
        released.join();

        assertEquals(0, runnerCapacity.retained());
        ExecutionBudget.Actor replacement = budget.reserveActor();
        replacement.close();
        replacement.close();
        assertEquals(0, runnerCapacity.retained(), "duplicate cleanup must not underflow capacity");
    }

    @Test
    @Timeout(10)
    void traversalPermitIsReleasedExactlyOnceWhenRetirementAndRunnerCloseRace() throws Exception {
        var runnerCapacity = new RunnerActorCapacity(1);
        var budget = new ExecutionBudget(actorLimit(1), runnerCapacity);
        var registry = new TraversalInstanceRegistry(
                (name, node) -> new ai.ravenroot.api.execution.NodeRef(name));
        UUID traversal = UUID.randomUUID();
        registry.acquire(new TraversalInstanceIdentity("tenant", null, "v1", UUID.randomUUID(),
                        traversal, "node"), successfulNode(), budget::reserveActor);
        var retiring = registry.deregister(traversal);
        var start = new CountDownLatch(1);
        Thread retired = Thread.ofVirtual().start(() -> {
            await(start);
            registry.retired(retiring);
        });
        Thread closed = Thread.ofVirtual().start(() -> {
            await(start);
            registry.deregisterAll();
        });

        start.countDown();
        retired.join(TimeUnit.SECONDS.toMillis(5));
        closed.join(TimeUnit.SECONDS.toMillis(5));

        assertEquals(0, runnerCapacity.retained());
        budget.reserveActor().close();
        assertEquals(0, runnerCapacity.retained());
    }

    @Test
    @Timeout(10)
    void retryConsumesTraversalStepBeforeSecondSend() {
        assertRetryRefused(GraphExecutionLimitException.Reason.TRAVERSAL_STEPS,
                retryLimits(2, 100, 1_000_000));
    }

    @Test
    @Timeout(10)
    void nonRootRetryConsumesAmplifiedDeliveryBeforeSecondSend() {
        assertRetryRefused(GraphExecutionLimitException.Reason.AMPLIFIED_DELIVERIES,
                retryLimits(100, 1, 1_000_000));
    }

    @Test
    @Timeout(10)
    void retryConsumesExactPayloadAndAttributesBeforeSecondSend() {
        PayloadLimits payload = PayloadLimits.DEFAULTS;
        long oneDelivery = payload.enforceAndMeasure("payload") + payload.enforceAndMeasure(Map.of());
        assertRetryRefused(GraphExecutionLimitException.Reason.PAYLOAD_BYTES,
                retryLimits(100, 100, oneDelivery * 2));
    }

    @Test
    @Timeout(10)
    void graphRetryChargesTheReceivedAttributeMapAsWellAsItsPayload() {
        Object input = "payload";
        Map<String, Object> attributes = Map.of("trace", List.of(1L, 2L, 3L));
        PayloadLimits payloadLimits = PayloadLimits.DEFAULTS;
        long plainDelivery = payloadLimits.enforceAndMeasure(input)
                + payloadLimits.enforceAndMeasure(Map.of());
        long attributedDelivery = payloadLimits.enforceAndMeasure(input)
                + payloadLimits.enforceAndMeasure(attributes);
        long beforeRetry = Math.addExact(Math.multiplyExact(2, plainDelivery), attributedDelivery);
        var entries = new AtomicInteger();
        var events = new CopyOnWriteArrayList<ExecutionEvent>();
        var monitor = new ExecutionMonitor();
        monitor.subscribe(events::add);
        var behaviors = new BehaviorRegistry()
                .register("annotate", ignored -> CompletableFuture.completedFuture(
                        new NodeResult("continue", input, attributes)))
                .register("retry", ignored -> {
                    entries.incrementAndGet();
                    return CompletableFuture.failedFuture(new RetryableBlip());
                });
        var graph = new GraphDefinition(List.of(GraphNode.start("start"),
                GraphNode.behavior("annotate", "annotate"),
                new GraphNode("retry", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "retry", Map.of(
                        NodeRetryProperty.MAX_ATTEMPTS, "2",
                        NodeRetryProperty.INITIAL_BACKOFF, "0",
                        NodeRetryProperty.BACKOFF_MULTIPLIER, "1.0",
                        NodeRetryProperty.MAX_BACKOFF, "0",
                        NodeRetryProperty.RETRY_ON, RetryableBlip.class.getSimpleName())),
                GraphNode.end("end")), List.of(GraphEdge.to("start", "annotate"),
                GraphEdge.to("annotate", "retry"), GraphEdge.to("retry", "end")));
        try (var engine = new JoinTestEngine(); var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, behaviors, monitor,
                     ExecutionIdentitySource.randomUuids(), UnknownBehaviorPolicy.passThrough(),
                     ExecutionPolicy.STANDARD, retryLimits(100, 100, beforeRetry))) {
            Throwable failure = assertThrows(CompletionException.class,
                    () -> runner.execute(TestIdentities.TENANT_A, input).toCompletableFuture().join());

            assertEquals(GraphExecutionLimitException.Reason.PAYLOAD_BYTES, findLimit(failure).reason());
            assertEquals(1, entries.get());
            assertEquals(0, events.stream()
                    .filter(event -> event.type() == ExecutionEventType.NODE_RETRY_SCHEDULED).count());
            assertEquals(3, engine.spawnCount(), "the attributed retry must be refused before another actor");
        }
    }

    @Test
    void retryChargeAddsExactCombinedPayloadAndAttributeBytesWithoutAHop() {
        var budget = new ExecutionBudget(retryLimits(10, 10, 1_000));
        ExecutionBudget.Hop hop = budget.reserveRoot(11);

        budget.reserveRetry(true, 37);

        assertEquals(new GraphExecutionBudgetSnapshot(2, 1, 48, 1, 0), budget.snapshot());
        hop.close();
    }

    @Test
    void retryPayloadOverflowFailsClosedWithoutChangingCounters() {
        var budget = new ExecutionBudget(retryLimits(10, 10, 1_000));
        budget.reserveRoot(11);
        GraphExecutionBudgetSnapshot before = budget.snapshot();

        GraphExecutionLimitException refused = assertThrows(GraphExecutionLimitException.class,
                () -> budget.reserveRetry(true, Long.MAX_VALUE));

        assertEquals(GraphExecutionLimitException.Reason.PAYLOAD_BYTES, refused.reason());
        assertEquals(before, budget.snapshot());
    }

    @Test
    @Timeout(10)
    void concurrentRetryReservationsHaveOneAtomicWinner() throws Exception {
        var budget = new ExecutionBudget(retryLimits(2, 1, 100));
        budget.reserveRoot(1);
        var start = new CountDownLatch(1);
        var successes = new AtomicInteger();
        var refusals = new AtomicInteger();
        Runnable contender = () -> {
            try {
                start.await();
                budget.reserveRetry(true, 7);
                successes.incrementAndGet();
            } catch (GraphExecutionLimitException refused) {
                refusals.incrementAndGet();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        Thread first = Thread.ofVirtual().start(contender);
        Thread second = Thread.ofVirtual().start(contender);
        start.countDown();
        first.join(TimeUnit.SECONDS.toMillis(5));
        second.join(TimeUnit.SECONDS.toMillis(5));

        assertEquals(1, successes.get());
        assertEquals(1, refusals.get());
        assertEquals(new GraphExecutionBudgetSnapshot(2, 1, 8, 1, 0), budget.snapshot());
    }

    @Test
    @Timeout(10)
    void concurrentBranchRetriesHaveOneBudgetAndLifecycleWinnerBeforeAnySecondSend() {
        var entered = new CountDownLatch(2);
        var calls = new AtomicInteger();
        var events = new CopyOnWriteArrayList<ExecutionEvent>();
        var retryScheduled = new CountDownLatch(1);
        var monitor = new ExecutionMonitor();
        monitor.subscribe(event -> {
            events.add(event);
            if (event.type() == ExecutionEventType.NODE_RETRY_SCHEDULED) retryScheduled.countDown();
        });
        var behaviors = new BehaviorRegistry().register("racing-retry", ignored -> {
            calls.incrementAndGet();
            entered.countDown();
            await(entered);
            return CompletableFuture.failedFuture(new RetryableBlip());
        });
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"), retryNode("left"), retryNode("right"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "left"), GraphEdge.to("start", "right"),
                        GraphEdge.to("left", "end"), GraphEdge.to("right", "end")));
        GraphExecutionLimits limits = retryLimits(4, 3, 1_000_000);

        try (var engine = new JoinTestEngine(); var manager = GraphManager.from(graph);
             var graphRunner = new GraphRunner(manager, engine, behaviors, monitor,
                     ExecutionIdentitySource.randomUuids(), UnknownBehaviorPolicy.passThrough(),
                     ExecutionPolicy.STANDARD, limits)) {
            Throwable failure = assertThrows(CompletionException.class,
                    () -> graphRunner.execute(TestIdentities.TENANT_A, "payload")
                            .toCompletableFuture().join());
            await(retryScheduled);

            assertEquals(GraphExecutionLimitException.Reason.TRAVERSAL_STEPS, findLimit(failure).reason());
            assertEquals(2, calls.get(), "neither losing nor cancelled branch may reach a second send");
            assertEquals(1, events.stream()
                    .filter(event -> event.type() == ExecutionEventType.NODE_RETRY_SCHEDULED).count(),
                    "one synchronized reservation and retry commit wins the final capacity");
            assertEquals(2, events.stream()
                    .filter(event -> event.type() == ExecutionEventType.NODE_STARTED
                            && ("left".equals(event.nodeId()) || "right".equals(event.nodeId()))).count());
            assertEquals(3, engine.spawnCount(), "no second-attempt actor may be allocated");
        }
    }

    @Test
    void admissionQueueRefusesBeforeAllocatingAnotherWaiter() {
        var registry = new TraversalAdmissionRegistry();
        var key = new TraversalAdmissionRegistry.Key("tenant", "deployment", "version", UUID.randomUUID(), "n");
        TraversalAdmissionRegistry.Lease first = registry.acquire(key, 1, 1, 1).toCompletableFuture().join();
        var waiting = registry.acquire(key, 1, 1, 1).toCompletableFuture();

        GraphExecutionLimitException refusal = findLimit(assertThrows(CompletionException.class,
                () -> registry.acquire(key, 1, 1, 1).toCompletableFuture().join()));
        assertEquals(GraphExecutionLimitException.Reason.ADMISSION_QUEUE, refusal.reason());

        first.close();
        waiting.join().close();
        registry.close();
    }

    @Test
    void wideAndDeepTopologiesStayLinearUnderAdmission() throws Exception {
        var engine = new JoinTestEngine();
        GraphExecutionLimits limits = limits(GraphMlLimits.DEFAULTS, PayloadLimits.DEFAULTS,
                16, 100_000, 100_000, 64 * 1024 * 1024L);
        try (engine) {
            for (int width = 1; width <= 48; width++) {
                GraphDefinition graph = fanOut(width);
                try (var manager = GraphManager.from(graph)) {
                    if (width <= 16) {
                        try (var admitted = runner(manager, engine, new BehaviorRegistry(), limits)) {
                            if (width == 16) {
                                admitted.execute(TestIdentities.TENANT_A, "payload")
                                        .toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
                            }
                        }
                    } else {
                        GraphExecutionLimitException refused = assertThrows(GraphExecutionLimitException.class,
                                () -> runner(manager, engine, new BehaviorRegistry(), limits));
                        assertEquals(GraphExecutionLimitException.Reason.FAN_OUT, refused.reason());
                    }
                }
            }
            try (var manager = GraphManager.from(deepChain(512));
                 var admitted = runner(manager, engine, new BehaviorRegistry(), limits)) {
                admitted.execute(TestIdentities.TENANT_A, "payload")
                        .toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    @Property(tries = 100)
    void randomizedFanOutAdmissionNeverCreatesAPartialRejectedGraph(
            @ForAll @IntRange(min = 1, max = 80) int width) {
        var engine = new JoinTestEngine();
        GraphExecutionLimits limits = limits(GraphMlLimits.DEFAULTS, PayloadLimits.DEFAULTS,
                16, 100_000, 100_000, 64 * 1024 * 1024L);
        try (var manager = GraphManager.from(fanOut(width)); engine) {
            if (width <= 16) {
                try (var ignored = runner(manager, engine, new BehaviorRegistry(), limits)) { }
            } else {
                assertThrows(GraphExecutionLimitException.class,
                        () -> runner(manager, engine, new BehaviorRegistry(), limits));
                assertEquals(0, engine.spawnCount());
            }
        }
    }

    private static GraphRunner runner(GraphManager manager, JoinTestEngine engine, BehaviorRegistry registry,
                                      GraphExecutionLimits limits) {
        return new GraphRunner(manager, engine, registry, new ExecutionMonitor(),
                ExecutionIdentitySource.randomUuids(), UnknownBehaviorPolicy.passThrough(),
                ExecutionPolicy.STANDARD, limits);
    }

    private static void assertRetryRefused(GraphExecutionLimitException.Reason reason,
                                           GraphExecutionLimits limits) {
        var entries = new AtomicInteger();
        var events = new CopyOnWriteArrayList<ExecutionEvent>();
        var monitor = new ExecutionMonitor();
        monitor.subscribe(events::add);
        var behaviors = new BehaviorRegistry().register("retry", ignored -> {
            entries.incrementAndGet();
            return CompletableFuture.failedFuture(new RetryableBlip());
        });
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("retry", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "retry", Map.of(
                        NodeRetryProperty.MAX_ATTEMPTS, "2",
                        NodeRetryProperty.INITIAL_BACKOFF, "0",
                        NodeRetryProperty.BACKOFF_MULTIPLIER, "1.0",
                        NodeRetryProperty.MAX_BACKOFF, "0",
                        NodeRetryProperty.RETRY_ON, RetryableBlip.class.getSimpleName())),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "retry"), GraphEdge.to("retry", "end")));
        try (var engine = new JoinTestEngine(); var manager = GraphManager.from(graph);
             var graphRunner = new GraphRunner(manager, engine, behaviors, monitor,
                     ExecutionIdentitySource.randomUuids(), UnknownBehaviorPolicy.passThrough(),
                     ExecutionPolicy.STANDARD, limits)) {
            Throwable failure = assertThrows(CompletionException.class,
                    () -> graphRunner.execute(TestIdentities.TENANT_A, "payload")
                            .toCompletableFuture().join());
            assertEquals(reason, findLimit(failure).reason());
            assertEquals(1, entries.get(), "the refused retry must not reach a second send");
            assertEquals(0, events.stream()
                    .filter(event -> event.type() == ExecutionEventType.NODE_RETRY_SCHEDULED).count(),
                    "the limit refusal must precede the retry lifecycle commit");
            assertEquals(1, events.stream()
                    .filter(event -> event.type() == ExecutionEventType.NODE_STARTED
                            && "retry".equals(event.nodeId())).count(),
                    "the refused retry must not publish a second attempt start");
            assertEquals(2, engine.spawnCount(),
                    "only start and the first retry-node attempt may be spawned");
            assertEquals(1, events.stream()
                    .filter(event -> event.type() == ExecutionEventType.NODE_FAILED
                            && "retry".equals(event.nodeId())).count());
        }
    }

    private static GraphExecutionLimits retryLimits(long steps, long amplification, long bytes) {
        GraphExecutionLimits defaults = GraphExecutionLimits.DEFAULTS;
        return new GraphExecutionLimits(defaults.graphMl(), defaults.payload(), defaults.maxFanOut(),
                defaults.maxResidentActors(), defaults.maxLiveActorsPerTraversal(),
                defaults.maxInFlightHopsPerTraversal(), defaults.maxQueuedAdmissionsPerNode(),
                steps, amplification, bytes, defaults.maxRecoveryDeliveriesPerAttempt());
    }

    private static GraphExecutionLimits actorLimit(int actors) {
        GraphExecutionLimits defaults = GraphExecutionLimits.DEFAULTS;
        return new GraphExecutionLimits(defaults.graphMl(), defaults.payload(), defaults.maxFanOut(),
                defaults.maxResidentActors(), actors, defaults.maxInFlightHopsPerTraversal(),
                defaults.maxQueuedAdmissionsPerNode(), defaults.maxTraversalSteps(),
                defaults.maxAmplifiedDeliveries(), defaults.maxCumulativePayloadBytes(),
                defaults.maxRecoveryDeliveriesPerAttempt());
    }

    private static GraphNode retryNode(String id) {
        return new GraphNode(id, ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "racing-retry", Map.of(
                NodeRetryProperty.MAX_ATTEMPTS, "2",
                NodeRetryProperty.INITIAL_BACKOFF, "PT1H",
                NodeRetryProperty.BACKOFF_MULTIPLIER, "1.0",
                NodeRetryProperty.MAX_BACKOFF, "PT1H",
                NodeRetryProperty.RETRY_ON, RetryableBlip.class.getSimpleName()));
    }

    private static WorkerInstanceIdentity workerIdentity() {
        return new WorkerInstanceIdentity("tenant", null, "v1", UUID.randomUUID(),
                UUID.randomUUID(), "node", UUID.randomUUID(), UUID.randomUUID());
    }

    private static ai.ravenroot.api.execution.RavenNode successfulNode() {
        return (message, context) -> CompletableFuture.completedFuture(NodeResult.continueWith(null));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for concurrent release");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class RetryableBlip extends RuntimeException {
        private RetryableBlip() {
            super("retryable");
        }
    }

    private static GraphDefinition fanOut(int width) {
        var nodes = new ArrayList<GraphNode>();
        var edges = new ArrayList<GraphEdge>();
        nodes.add(GraphNode.start("start"));
        nodes.add(GraphNode.end("end"));
        for (int index = 0; index < width; index++) {
            String id = "work-" + index;
            nodes.add(GraphNode.behavior(id, "missing"));
            edges.add(new GraphEdge("start", id, "continue"));
            edges.add(new GraphEdge(id, "end", "continue"));
        }
        return new GraphDefinition(nodes, edges);
    }

    private static GraphDefinition deepChain(int depth) {
        var nodes = new ArrayList<GraphNode>();
        var edges = new ArrayList<GraphEdge>();
        nodes.add(GraphNode.start("start"));
        String previous = "start";
        for (int index = 0; index < depth; index++) {
            String id = "step-" + index;
            nodes.add(GraphNode.behavior(id, "missing"));
            edges.add(GraphEdge.to(previous, id));
            previous = id;
        }
        nodes.add(GraphNode.end("end"));
        edges.add(GraphEdge.to(previous, "end"));
        return new GraphDefinition(nodes, edges);
    }

    private static GraphExecutionLimits limits(GraphMlLimits graphMl, PayloadLimits payload, int fanOut,
                                                long steps, long deliveries, long bytes) {
        return new GraphExecutionLimits(graphMl, payload, fanOut, 256, 256, 1024, 1024,
                steps, deliveries, bytes, 8);
    }

    private static GraphMlLimits graphMl(int nodes, int edges, int properties) {
        GraphMlLimits defaults = GraphMlLimits.DEFAULTS;
        return new GraphMlLimits(defaults.maxBytes(), nodes, edges, properties, defaults.maxDepth(),
                defaults.maxStringLength(), defaults.maxKeys(), defaults.maxElements(), defaults.maxAttributes(),
                defaults.maxNamespaceDeclarations());
    }

    private static GraphExecutionLimitException findLimit(Throwable failure) {
        if (failure instanceof GraphExecutionLimitException limit) return limit;
        for (Throwable suppressed : failure.getSuppressed()) {
            GraphExecutionLimitException found = findLimit(suppressed);
            if (found != null) return found;
        }
        return failure.getCause() == null ? null : findLimit(failure.getCause());
    }
}
