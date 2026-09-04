package ai.ravenroot.testkit;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionDomain;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.NodeCancelledException;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeLifecycleState;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeNotAcceptingException;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.NodeTerminationReason;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.GraphRunner;
import ai.ravenroot.core.runtime.JoinFailureException;
import ai.ravenroot.core.runtime.JoinSpec;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.runtime.NodeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests every adapter against the mandatory Ravenroot engine semantics. */
public abstract class ExecutionEngineContract {
    /**
     * The identity every conformance message and traversal runs as. An engine adapter is not a
     * security boundary — it transports {@code NodeMessage} opaquely — so one fixed context is enough
     * here. SEC-07 propagation itself is asserted in core and at the application layer, where the
     * identity is actually established.
     */
    protected static final ai.ravenroot.api.security.SecurityContext TCK_IDENTITY =
            new ai.ravenroot.api.security.SecurityContext("tck-request", "tck-tenant", "tck-subject",
                    ai.ravenroot.api.security.PrincipalType.WORKLOAD, "urn:ravenroot:tck");

    private ExecutionEngine engine;

    protected abstract ExecutionEngine createEngine(String systemName);

    protected final ExecutionEngine engine() {
        if (engine == null) {
            engine = createEngine("ravenroot-tck-" + UUID.randomUUID());
        }
        return engine;
    }

    /**
     * ADR 0023: Test is structural evidence, not output simulation. The engine still spawns
     * and traverses every graph node, while the runner's command wrapper must intercept delivery before
     * deployment-trusted factories (and therefore secret, tool or adapter boundaries) are touched.
     */
    @Test
    void testPassthroughTraversesActorsWithoutConstructingOrInvokingProductionBehaviors() throws Exception {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("secret", "production-secret"),
                GraphNode.behavior("tool", "production-tool"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "secret"),
                GraphEdge.to("secret", "tool"),
                GraphEdge.to("tool", "end")));
        var factoryCalls = new AtomicInteger();
        var handlerCalls = new AtomicInteger();
        var registry = new BehaviorRegistry()
                .registerFactory(new SideEffectProbeFactory("production-secret", factoryCalls, handlerCalls))
                .registerFactory(new SideEffectProbeFactory("production-tool", factoryCalls, handlerCalls));
        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine(), registry, new ExecutionMonitor(),
                     ExecutionIdentitySource.randomUuids(), ExecutionPolicy.TEST_PASSTHROUGH)) {
            var result = runner.execute(TCK_IDENTITY, "original").toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals("original", result.payload(), "Test pass-through must preserve the submitted payload");
            assertEquals(Set.of("start", "secret", "tool", "end"), result.visitedNodes(),
                    "Test must preserve structural actor traversal");
            assertTrue(result.defaultedNodes().isEmpty(),
                    "an intentional bypass is not an unknown-behavior fallback");
            assertEquals(Set.of("start", "secret", "tool", "end"), result.bypassedNodes());
            assertEquals(0, factoryCalls.get(), "Test crossed the production adapter construction boundary");
            assertEquals(0, handlerCalls.get(), "Test invoked a production behavior");
        }
    }

    @Test
    void standardExecutionRemainsDistinctAndInvokesRegisteredBehavior() throws Exception {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"), GraphNode.behavior("work", "production-tool"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "work"), GraphEdge.to("work", "end")));
        var factoryCalls = new AtomicInteger();
        var handlerCalls = new AtomicInteger();
        var registry = new BehaviorRegistry().registerFactory(
                new SideEffectProbeFactory("production-tool", factoryCalls, handlerCalls));
        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine(), registry, new ExecutionMonitor(),
                     ExecutionIdentitySource.randomUuids(), ExecutionPolicy.STANDARD)) {
            var result = runner.execute(TCK_IDENTITY, "original").toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals("production-output", result.payload());
            assertEquals(1, factoryCalls.get());
            assertEquals(1, handlerCalls.get());
            assertTrue(result.defaultedNodes().isEmpty());
        }
    }

    private record SideEffectProbeFactory(String behavior, AtomicInteger factoryCalls,
                                          AtomicInteger handlerCalls) implements NodeBehaviorFactory {
        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor(behavior, behavior, "TCK", "production side-effect probe",
                    "actor", false, List.of(), Set.of("network", "credential-reference", "side-effect"));
        }

        @Override
        public NodeHandler create(GraphNode node) {
            factoryCalls.incrementAndGet();
            return message -> {
                handlerCalls.incrementAndGet();
                return CompletableFuture.completedFuture(NodeResult.continueWith("production-output"));
            };
        }
    }

    @AfterEach
    final void closeEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    final void executesLifecycleExactlyOnce() throws Exception {
        var starts = new AtomicInteger();
        var stops = new AtomicInteger();
        var node = new RavenNode() {
            @Override
            public void onStart(NodeContext context) {
                starts.incrementAndGet();
            }

            @Override
            public java.util.concurrent.CompletionStage<NodeResult> onMessage(
                    NodeMessage message, NodeContext context) {
                return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            }

            @Override
            public void onStop(NodeContext context) {
                stops.incrementAndGet();
            }
        };
        var ref = engine().spawn("lifecycle", node);

        assertEquals("payload", send(ref, "payload").payload());
        engine().stop(ref).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(1, starts.get());
        assertEquals(1, stops.get());
    }

    @Test
    final void serializesNodeProcessingUntilAsyncCompletion() throws Exception {
        var firstStarted = new CountDownLatch(1);
        var firstResult = new CompletableFuture<NodeResult>();
        var invocations = new AtomicInteger();
        List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
        var ref = engine().spawn("ordered", (message, context) -> {
            int invocation = invocations.incrementAndGet();
            order.add("start-" + invocation);
            if (invocation == 1) {
                firstStarted.countDown();
                return firstResult.whenComplete((ignored, error) -> order.add("end-1"));
            }
            order.add("end-2");
            return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        });

        var first = engine().send(ref, message("first"));
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        var second = engine().send(ref, message("second"));
        Thread.sleep(100);
        assertEquals(1, invocations.get(), "a second invocation must wait for the first completion");

        firstResult.complete(NodeResult.continueWith("first"));
        assertEquals("first", first.toCompletableFuture().get(5, TimeUnit.SECONDS).payload());
        assertEquals("second", second.toCompletableFuture().get(5, TimeUnit.SECONDS).payload());
        assertEquals(List.of("start-1", "end-1", "start-2", "end-2"), order);
    }

    @Test
    final void propagatesFailureAndContinuesProcessing() throws Exception {
        var invocation = new AtomicInteger();
        var ref = engine().spawn("failure", (message, context) -> invocation.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new IllegalStateException("expected"))
                : CompletableFuture.completedFuture(NodeResult.continueWith("recovered")));

        var failure = assertThrows(Exception.class,
                () -> engine().send(ref, message("first")).toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertTrue(rootCause(failure) instanceof IllegalStateException);
        // Resume is the mandatory supervision decision, so a failed invocation must not have moved
        // the node's own lifecycle. Asserting the state, not just that a later message works, is what
        // stops an adapter from quietly restarting the node behind a passing functional test.
        assertEquals(NodeLifecycleState.RUNNING, status(ref).state());
        assertEquals(0, status(ref).acceptedMessages());
        assertEquals("recovered", send(ref, "second").payload());
    }

    /**
     * An {@code Error} is not an {@code Exception}, and the adapters' catch clauses only ever said
     * {@code RuntimeException}.
     *
     * <p>So an {@code AssertionError} — from an assertion, a test double, a library compiled against
     * a different version — went straight past the adapter into the actor runtime, which supervised it
     * by stopping the actor. Three guarantees fell at once: the caller's reply was registered at
     * admission but never settled, so it waited forever; the lifecycle was stranded in a non-terminal
     * state that neither {@code stop} nor {@code cancel} could move, because the only thing that could
     * complete it was the actor that had just died; and resume, the mandatory supervision decision,
     * silently became stop-on-failure for one category of failure.</p>
     *
     * <p>Measured on the unfixed adapter: this send never completes, {@code stop} never completes, and
     * {@code close()} takes 20s — two full termination bounds — and still leaves the node
     * {@code CANCELLING}.</p>
     */
    @Test
    final void resumesAfterANodeThrowsAnErrorRatherThanAnException() throws Exception {
        var invocation = new AtomicInteger();
        var stops = new AtomicInteger();
        var ref = engine().spawn("error", new RavenNode() {
            @Override
            public CompletionStage<NodeResult> onMessage(NodeMessage message, NodeContext context) {
                if (invocation.incrementAndGet() == 1) {
                    throw new AssertionError("thrown by the node");
                }
                return CompletableFuture.completedFuture(NodeResult.continueWith("recovered"));
            }

            @Override
            public void onStop(NodeContext context) {
                stops.incrementAndGet();
            }
        });

        var failure = assertThrows(ExecutionException.class,
                () -> engine().send(ref, message("first")).toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertInstanceOf(AssertionError.class, rootCause(failure),
                "the Error must reach the caller, not the actor runtime");

        // Identical to what propagatesFailureAndContinuesProcessing asserts for an Exception. The
        // whole point is that the two categories are indistinguishable from outside the node.
        assertEquals(NodeLifecycleState.RUNNING, status(ref).state());
        assertEquals(0, status(ref).acceptedMessages());
        assertEquals("recovered", send(ref, "second").payload());

        engine().stop(ref).toCompletableFuture().get(5, TimeUnit.SECONDS);
        var terminal = status(ref);
        assertEquals(NodeLifecycleState.TERMINATED, terminal.state());
        assertEquals(NodeTerminationReason.STOPPED, terminal.reason());
        assertEquals(1, stops.get());
    }

    /**
     * The reported defect end to end: {@code GraphRunner.close()} must return even when a node dies.
     *
     * <p>This is the shape the defect was actually found in — a graph, a behaviour that throws, and a
     * try-with-resources that never left its block. The core suite pins the runner's shutdown policy
     * against a stub; this pins that a real adapter never puts the runner in the position of needing
     * it, because the node terminates properly instead.</p>
     */
    @Test
    final void closesAGraphRunnerWhoseNodeThrewAnError() throws Exception {
        var registry = new BehaviorRegistry().register("explode", message -> {
            throw new AssertionError("thrown by the behaviour");
        });
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("boom", "explode"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "boom"),
                GraphEdge.to("boom", "end")));

        try (var manager = GraphManager.from(graph)) {
            var runner = new GraphRunner(manager, engine(), registry, new ExecutionMonitor());

            var execution = runner.execute(TCK_IDENTITY, "payload").toCompletableFuture();
            assertInstanceOf(AssertionError.class, rootCause(assertThrows(ExecutionException.class,
                    () -> execution.get(5, TimeUnit.SECONDS))), "the traversal must fail, not hang");

            // Unbounded before: allOf(stops).join() on a stop stage nothing could complete.
            assertTimeoutPreemptively(Duration.ofSeconds(30), runner::close);
        }
    }

    @Test
    final void schedulesTasksAndCancelsThem() throws Exception {
        var executed = new CountDownLatch(1);
        engine().scheduler().schedule(Duration.ofMillis(20), executed::countDown);
        assertTrue(executed.await(5, TimeUnit.SECONDS));

        var cancelledExecution = new AtomicInteger();
        var task = engine().scheduler().schedule(Duration.ofSeconds(1), cancelledExecution::incrementAndGet);
        assertTrue(task.cancel());
        Thread.sleep(100);
        assertEquals(0, cancelledExecution.get());
    }

    @Test
    final void rejectsMessagesAfterStop() throws Exception {
        var ref = engine().spawn("stopped", (message, context) ->
                CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())));
        engine().stop(ref).toCompletableFuture().get(5, TimeUnit.SECONDS);

        var result = engine().send(ref, message("late")).toCompletableFuture();
        assertTrue(result.isCompletedExceptionally());
        var failure = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
        var refusal = assertInstanceOf(NodeNotAcceptingException.class, failure.getCause());
        assertEquals(ref, refusal.node());
        assertEquals(NodeLifecycleState.TERMINATED, refusal.state());
    }

    @Test
    final void distinguishesARefusedNodeFromANodeItNeverIssued() {
        var unknown = new NodeRef("never-spawned-" + UUID.randomUUID());

        var failure = assertThrows(ExecutionException.class,
                () -> engine().send(unknown, message("late")).toCompletableFuture().get(5, TimeUnit.SECONDS));

        // Losing a race against a stop is an ordinary outcome a caller may retry; addressing a
        // reference the engine never issued is a defect. One exception type for both forced callers
        // to parse a message string to tell them apart.
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertFalse(failure.getCause() instanceof NodeNotAcceptingException);
        assertTrue(engine().status(unknown).isEmpty());
    }

    @Test
    final void drainsMessagesAcceptedBeforeTheStop() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var stops = new AtomicInteger();
        var ref = engine().spawn("draining", new RavenNode() {
            @Override
            public CompletionStage<NodeResult> onMessage(NodeMessage message, NodeContext context) {
                started.countDown();
                return CompletableFuture.supplyAsync(() -> {
                    await(release);
                    return NodeResult.continueWith(message.payload());
                });
            }

            @Override
            public void onStop(NodeContext context) {
                stops.incrementAndGet();
            }
        });

        var first = engine().send(ref, message("first")).toCompletableFuture();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var second = engine().send(ref, message("second")).toCompletableFuture();
        assertEquals(2, status(ref).acceptedMessages());

        var stopped = engine().stop(ref).toCompletableFuture();
        assertEquals(NodeLifecycleState.DRAINING, status(ref).state());
        assertFalse(stopped.isDone(), "a drain cannot finish while it still owes accepted messages");
        // The drain refuses new work from the moment it starts; that is what separates it from a
        // quiesce, and it is the only reason the outstanding count can reach zero.
        assertInstanceOf(NodeNotAcceptingException.class, rootCause(assertThrows(ExecutionException.class,
                () -> engine().send(ref, message("third")).toCompletableFuture().get(5, TimeUnit.SECONDS))));

        release.countDown();

        assertEquals("first", first.get(5, TimeUnit.SECONDS).payload());
        assertEquals("second", second.get(5, TimeUnit.SECONDS).payload());
        stopped.get(5, TimeUnit.SECONDS);
        var terminal = status(ref);
        assertEquals(NodeLifecycleState.TERMINATED, terminal.state());
        assertEquals(NodeTerminationReason.STOPPED, terminal.reason());
        assertEquals(0, terminal.acceptedMessages());
        assertEquals(1, stops.get());
    }

    @Test
    final void cancelReleasesCallersWithoutWaitingForTheNodeComputation() throws Exception {
        var started = new CountDownLatch(1);
        var neverCompletes = new CompletableFuture<NodeResult>();
        var observedSignal = new AtomicBoolean();
        var listenerFired = new CountDownLatch(1);
        var stops = new AtomicInteger();
        var ref = engine().spawn("cancelled", new RavenNode() {
            @Override
            public CompletionStage<NodeResult> onMessage(NodeMessage message, NodeContext context) {
                context.cancellation().onCancel(listenerFired::countDown);
                started.countDown();
                return neverCompletes.thenApply(result -> {
                    observedSignal.set(context.cancellation().cancelled());
                    return result;
                });
            }

            @Override
            public void onStop(NodeContext context) {
                stops.incrementAndGet();
            }
        });

        var inFlight = engine().send(ref, message("in-flight")).toCompletableFuture();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var queued = engine().send(ref, message("queued")).toCompletableFuture();
        assertEquals(2, status(ref).acceptedMessages());

        // The bound is the whole point: this must return even though the node's own computation has
        // not completed and never will on its own.
        engine().cancel(ref).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertInstanceOf(NodeCancelledException.class, rootCause(
                assertThrows(ExecutionException.class, () -> inFlight.get(5, TimeUnit.SECONDS))));
        assertInstanceOf(NodeCancelledException.class, rootCause(
                assertThrows(ExecutionException.class, () -> queued.get(5, TimeUnit.SECONDS))));
        assertTrue(listenerFired.await(5, TimeUnit.SECONDS), "the cooperative signal must reach the node");

        var terminal = status(ref);
        assertEquals(NodeLifecycleState.TERMINATED, terminal.state());
        assertEquals(NodeTerminationReason.CANCELLED, terminal.reason());
        assertEquals(0, terminal.acceptedMessages());
        assertEquals(1, stops.get());

        if (engine().capabilities().contains(EngineCapability.PREEMPTIVE_CANCELLATION)) {
            assertTrue(neverCompletes.isCompletedExceptionally(),
                    "an engine declaring preemptive cancellation must abort the computation itself");
        } else {
            // The honest cooperative guarantee: the computation is untouched, so the late result is
            // discarded rather than delivered, and the node can see why through its signal.
            assertFalse(neverCompletes.isDone());
            neverCompletes.complete(NodeResult.continueWith("too late"));
            Thread.sleep(100);
            assertTrue(observedSignal.get(), "a node resuming after a cancellation must observe it");
            assertInstanceOf(NodeCancelledException.class, rootCause(
                    assertThrows(ExecutionException.class, () -> inFlight.get(5, TimeUnit.SECONDS))));
        }
    }

    @Test
    final void aLateFailureDoesNotRewriteAnObservedCancellation() throws Exception {
        var started = new CountDownLatch(1);
        var controlled = new CompletableFuture<NodeResult>();
        var ref = engine().spawn("late-failure", (message, context) -> {
            started.countDown();
            return controlled;
        });

        var reply = engine().send(ref, message("payload")).toCompletableFuture();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        engine().cancel(ref).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertInstanceOf(NodeCancelledException.class, rootCause(
                assertThrows(ExecutionException.class, () -> reply.get(5, TimeUnit.SECONDS))));

        controlled.completeExceptionally(new IllegalStateException("arrived after the cancellation"));
        Thread.sleep(100);

        // First settlement wins. A caller told its message was cancelled must never later be told the
        // same message failed instead, because it may already have acted on the cancellation.
        assertInstanceOf(NodeCancelledException.class, rootCause(
                assertThrows(ExecutionException.class, () -> reply.get(5, TimeUnit.SECONDS))));
    }

    @Test
    final void escalatesADrainIntoACancellationAndCompletesBothRequests() throws Exception {
        var started = new CountDownLatch(1);
        var neverCompletes = new CompletableFuture<NodeResult>();
        var ref = engine().spawn("escalated", (message, context) -> {
            started.countDown();
            return neverCompletes;
        });

        var reply = engine().send(ref, message("stuck")).toCompletableFuture();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        var stopped = engine().stop(ref).toCompletableFuture();
        assertEquals(NodeLifecycleState.DRAINING, status(ref).state());
        assertFalse(stopped.isDone(), "a node that never completes never drains, by design");

        var cancelled = engine().cancel(ref).toCompletableFuture();

        cancelled.get(5, TimeUnit.SECONDS);
        // Both requests describe the same node, so both must be answered — a caller that asked for a
        // graceful stop is not left waiting because someone else escalated.
        stopped.get(5, TimeUnit.SECONDS);
        assertInstanceOf(NodeCancelledException.class, rootCause(
                assertThrows(ExecutionException.class, () -> reply.get(5, TimeUnit.SECONDS))));
        assertEquals(NodeTerminationReason.CANCELLED, status(ref).reason(),
                "a node whose work was abandoned must not report that it drained cleanly");
    }

    @Test
    final void runsOnStopExactlyOnceWhenStopAndCancelRaceEachOther() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 50; round++) {
                var stops = new AtomicInteger();
                var ref = engine().spawn("both", new RavenNode() {
                    @Override
                    public CompletionStage<NodeResult> onMessage(NodeMessage message, NodeContext context) {
                        return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                    }

                    @Override
                    public void onStop(NodeContext context) {
                        stops.incrementAndGet();
                    }
                });
                engine().send(ref, message("warm")).toCompletableFuture().get(5, TimeUnit.SECONDS);

                var barrier = new CyclicBarrier(2);
                Future<CompletionStage<Void>> stop = pool.submit(() -> {
                    barrier.await();
                    return engine().stop(ref);
                });
                Future<CompletionStage<Void>> cancel = pool.submit(() -> {
                    barrier.await();
                    return engine().cancel(ref);
                });

                stop.get(5, TimeUnit.SECONDS).toCompletableFuture().get(5, TimeUnit.SECONDS);
                cancel.get(5, TimeUnit.SECONDS).toCompletableFuture().get(5, TimeUnit.SECONDS);

                // Which of the two wins is genuinely non-deterministic and is deliberately not
                // asserted. What must hold either way is that the node stopped once and reached a
                // terminal state with some reason.
                assertEquals(1, stops.get(), "onStop must run exactly once whichever request wins");
                var terminal = status(ref);
                assertEquals(NodeLifecycleState.TERMINATED, terminal.state());
                assertNotNull(terminal.reason());
                assertEquals(0, terminal.acceptedMessages());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The window this samples is a few instructions wide, so the sampling is deliberately heavy.
     *
     * <p>The defect it exists for is a {@code send} that passes the admission test, is descheduled,
     * and hands its message to a node that has stopped in the meantime: the message is dropped and its
     * caller waits forever. Measured against a {@code NodeLifecycle.accept} whose admission test and
     * hand-off were deliberately split into two lock acquisitions, three senders over 150 rounds
     * detected it in 3 runs out of 8 — a test that reports a defect less than half the time it is
     * present is not evidence of anything. At the configuration this test actually ships with
     * (rounds = 16384, senders = 8, each jittered so the participants do not all leave the barrier in
     * lockstep), detection was measured at 8 runs out of 8 trials on the checkout used for this
     * measurement; ADR 0012 separately recorded 7 out of 8 at the same configuration on a different
     * run. Both figures support the same conclusion — the shipped configuration reliably reproduces
     * the defect — but detection is inherently environment-sensitive because the race window is a few
     * instructions wide, so treat neither number as exactly reproducible and do not assume a lower
     * round count preserves it: only 150 rounds and 16384 rounds have actually been measured, at
     * 3 out of 8 and (8 or 7) out of 8 respectively.</p>
     *
     * <p>Statistical sampling is still sampling. The interleaving itself is pinned deterministically
     * by {@code NodeLifecycleTest}, which holds a message open mid-enqueue and asserts that no
     * transition can overtake it; this test is what proves the adapters actually route through that
     * guarantee.</p>
     */
    @Test
    final void settlesEverySendThatRacesAStop() throws Exception {
        int rounds = 16_384;
        int senders = 8;
        var pool = Executors.newFixedThreadPool(senders + 1);
        var engine = engine();
        try {
            for (int round = 0; round < rounds; round++) {
                var ref = engine.spawn("racing", (message, context) ->
                        CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())));
                var barrier = new CyclicBarrier(senders + 1);
                var sends = new ArrayList<Future<CompletionStage<NodeResult>>>();
                for (int sender = 0; sender < senders; sender++) {
                    sends.add(pool.submit(() -> {
                        barrier.await();
                        jitter();
                        return engine.send(ref, message("racer"));
                    }));
                }
                Future<CompletionStage<Void>> stop = pool.submit(() -> {
                    barrier.await();
                    jitter();
                    return engine.stop(ref);
                });

                for (Future<CompletionStage<NodeResult>> sent : sends) {
                    var reply = sent.get(5, TimeUnit.SECONDS).toCompletableFuture();
                    // The winner is not asserted: whether a send lands before the stop is real
                    // non-determinism and any test claiming to know is a test that will lie. The
                    // invariant is that the caller is answered at all, and answered with one of the
                    // two outcomes the contract allows. A send admitted and then dropped because the
                    // node stopped between the admission test and the enqueue would hang here.
                    try {
                        assertNotNull(reply.get(5, TimeUnit.SECONDS));
                    } catch (ExecutionException failure) {
                        assertInstanceOf(NodeNotAcceptingException.class, failure.getCause(),
                                "a send that loses the race must be refused, not failed some other way");
                    }
                }
                stop.get(5, TimeUnit.SECONDS).toCompletableFuture().get(5, TimeUnit.SECONDS);
                var terminal = status(ref);
                assertEquals(NodeLifecycleState.TERMINATED, terminal.state());
                assertEquals(0, terminal.acceptedMessages(),
                        "a terminated node cannot still owe an answer to anyone");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    final void settlesEveryAcceptedMessageWhenCancellationRacesCompletion() throws Exception {
        int rounds = 60;
        int messages = 4;
        var pool = Executors.newFixedThreadPool(2);
        var engine = engine();
        try {
            for (int round = 0; round < rounds; round++) {
                var ref = engine.spawn("cancel-race", (message, context) ->
                        CompletableFuture.supplyAsync(() -> NodeResult.continueWith(message.payload())));
                var replies = new ArrayList<CompletableFuture<NodeResult>>();
                for (int i = 0; i < messages; i++) {
                    replies.add(engine.send(ref, message("payload")).toCompletableFuture());
                }
                var cancelled = pool.submit(() -> engine.cancel(ref));

                cancelled.get(5, TimeUnit.SECONDS).toCompletableFuture().get(5, TimeUnit.SECONDS);
                for (CompletableFuture<NodeResult> reply : replies) {
                    try {
                        // An accepted message either completed before the cancellation reached it or
                        // was abandoned by it. Both are correct; a third outcome, or none, is not.
                        assertEquals("payload", reply.get(5, TimeUnit.SECONDS).payload());
                    } catch (ExecutionException failure) {
                        assertInstanceOf(NodeCancelledException.class, failure.getCause());
                    }
                }
                assertEquals(0, status(ref).acceptedMessages());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    final void drainsEveryNodeAndRefusesFurtherSpawns() throws Exception {
        var first = engine().spawn("first", passthrough());
        var second = engine().spawn("second", passthrough());
        assertEquals(EngineState.RUNNING, engine().state());
        assertEquals("a", send(first, "a").payload());

        engine().drain().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(EngineState.DRAINING, engine().state());
        assertEquals(NodeTerminationReason.STOPPED, status(first).reason());
        assertEquals(NodeTerminationReason.STOPPED, status(second).reason());
        assertThrows(IllegalStateException.class, () -> engine().spawn("late", passthrough()));
        assertInstanceOf(NodeNotAcceptingException.class, rootCause(assertThrows(ExecutionException.class,
                () -> engine().send(first, message("late")).toCompletableFuture().get(5, TimeUnit.SECONDS))));
    }

    @Test
    final void reportsClosedAndReleasesTheNodeStateItRetained() throws Exception {
        var own = createEngine("ravenroot-tck-close-" + UUID.randomUUID());
        NodeRef ref = own.spawn("closing", passthrough());
        assertEquals(NodeLifecycleState.RUNNING, own.status(ref).orElseThrow().state());
        own.stop(ref).toCompletableFuture().get(5, TimeUnit.SECONDS);
        // While the engine is up, the terminal status outlives the node on purpose: without it a
        // caller cannot distinguish a node that terminated from a reference the engine never issued.
        assertEquals(NodeLifecycleState.TERMINATED, own.status(ref).orElseThrow().state());
        assertInstanceOf(NodeNotAcceptingException.class, rootCause(assertThrows(ExecutionException.class,
                () -> own.send(ref, message("late")).toCompletableFuture().get(5, TimeUnit.SECONDS))));

        own.close();

        assertEquals(EngineState.CLOSED, own.state());
        // A closed engine owes nobody that distinction any more, and keeping it would leave an engine
        // an application has finished with as large as everything it ever ran.
        assertTrue(own.status(ref).isEmpty(), "close() must release the node state it retained");
        var afterClose = assertThrows(ExecutionException.class,
                () -> own.send(ref, message("late")).toCompletableFuture().get(5, TimeUnit.SECONDS));
        // Reporting "unknown node" here would accuse the caller of a defect the engine caused by
        // forgetting, so a closed engine says so about itself instead.
        assertInstanceOf(IllegalStateException.class, afterClose.getCause());
        assertFalse(afterClose.getCause() instanceof NodeNotAcceptingException);
        assertFalse(afterClose.getCause() instanceof IllegalArgumentException);
        assertThrows(IllegalStateException.class, () -> own.spawn("later", passthrough()));
        own.close();
        assertEquals(EngineState.CLOSED, own.state());
    }

    /**
     * Retaining a terminal status is required; retaining every node ever spawned is a leak.
     *
     * <p>The two are easy to conflate, and conflating them is what this asserts against. The engine's
     * registry is the mechanism that makes {@code status} answer at all, so an engine that only ever
     * adds to it grows for as long as the process runs. It is not a slow leak either: the application
     * layer builds a new {@code GraphRunner} per graph execution over one shared engine, and every
     * runner spawns one node per graph vertex.</p>
     *
     * <p>The bound itself is a policy and is pinned where the policy lives; what an engine has to
     * satisfy is only that the bound exists. Measured here: 4096 spawn/send/stop cycles retain 1024
     * references on both adapters. Before this was fixed they retained all 4096, and 4096 more after
     * {@code close()}.</p>
     */
    @Test
    final void boundsTheNodeStateItRetainsAsNodesTerminate() throws Exception {
        int cycles = 4096;
        var engine = engine();
        var refs = new ArrayList<NodeRef>(cycles);
        for (int cycle = 0; cycle < cycles; cycle++) {
            NodeRef ref = engine.spawn("retention", passthrough());
            refs.add(ref);
            assertEquals("payload", send(ref, "payload").payload());
            engine.stop(ref).toCompletableFuture().get(5, TimeUnit.SECONDS);
        }

        long retained = refs.stream().filter(ref -> engine.status(ref).isPresent()).count();

        assertTrue(retained <= cycles / 2,
                "an engine must not retain a node for every node it ever spawned, but " + retained
                        + " of " + cycles + " terminated nodes are still held");
        // The distinction the retention exists for still holds for what is retained, and the most
        // recently terminated node is the one a caller is most likely to still be holding.
        var newest = refs.get(cycles - 1);
        assertEquals(NodeLifecycleState.TERMINATED, engine.status(newest).orElseThrow().state());
        assertInstanceOf(NodeNotAcceptingException.class, rootCause(assertThrows(ExecutionException.class,
                () -> engine.send(newest, message("late")).toCompletableFuture().get(5, TimeUnit.SECONDS))));
        // And the honest cost of bounding it: past the bound a reference really is forgotten, and a
        // forgotten node is indistinguishable from one that never existed. That is a degradation, not
        // a free lunch, so it is asserted rather than left to be discovered.
        var oldest = refs.getFirst();
        assertTrue(engine.status(oldest).isEmpty());
        assertInstanceOf(IllegalArgumentException.class, rootCause(assertThrows(ExecutionException.class,
                () -> engine.send(oldest, message("late")).toCompletableFuture().get(5, TimeUnit.SECONDS))));
    }

    /**
     * The same bound on the path Ravenroot actually takes: a new {@code GraphRunner} per execution.
     *
     * <p>A raw spawn/stop loop is a fair model of the mechanism but not of the deployment. This runs
     * the shape the application layer runs — one long-lived engine, one runner and one set of nodes
     * per execution — because that is where "retention is bounded by spawn calls" stops being a
     * reassuring sentence and becomes growth proportional to traffic.</p>
     */
    @Test
    final void boundsTheNodeStateItRetainsAcrossManyGraphRunnerLifecycles() throws Exception {
        int executions = 600;
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.passthrough("middle"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "middle"),
                GraphEdge.to("middle", "end")));
        var engine = engine();
        var probes = new ArrayList<NodeRef>(executions);

        try (var manager = GraphManager.from(graph)) {
            for (int execution = 0; execution < executions; execution++) {
                // One directly held reference per execution, so the engine's retention stays
                // observable even though a runner does not publish the references it spawned.
                NodeRef probe = engine.spawn("probe", passthrough());
                probes.add(probe);
                try (var runner = new GraphRunner(manager, engine, new BehaviorRegistry(), new ExecutionMonitor())) {
                    runner.execute(TCK_IDENTITY, "payload").toCompletableFuture().get(10, TimeUnit.SECONDS);
                }
                engine.stop(probe).toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }

        long retained = probes.stream().filter(ref -> engine.status(ref).isPresent()).count();

        assertTrue(retained < executions,
                "retention must not grow one-for-one with executions, but " + retained + " of "
                        + executions + " probes from " + executions + " executions are still held");
        assertTrue(engine.status(probes.getFirst()).isEmpty(),
                "the first execution's node must not still be held after " + executions + " executions");
        assertEquals(NodeLifecycleState.TERMINATED, engine.status(probes.get(executions - 1)).orElseThrow().state(),
                "the most recent execution's node must still be distinguishable from one never issued");
    }

    @Test
    final void declaresAnImmutableAndStableCapabilitySet() {
        var first = engine().capabilities();
        var second = engine().capabilities();

        assertEquals(first, second, "capabilities must not change between calls");
        assertThrows(UnsupportedOperationException.class,
                () -> first.add(EngineCapability.TELEMETRY),
                "a caller must not be able to grant itself a capability the engine does not have");
    }

    @Test
    final void executesGraphMlFanOutFanInAndFallback() throws Exception {
        var registry = new BehaviorRegistry().register("append-a", message ->
                CompletableFuture.completedFuture(NodeResult.continueWith(message.payload() + "-a")));

        try (var manager = graphMlFixture();
             var runner = new GraphRunner(manager, engine(), registry, new ExecutionMonitor())) {
            var definition = manager.definition();
            assertEquals("retained-by-core", definition.node("future").properties().get("customAttribute"));
            assertTrue(definition.edges().stream()
                    .anyMatch(edge -> "branch-a".equals(edge.properties().get("routeLabel"))));

            var result = runner.execute(TCK_IDENTITY, "root").toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(5, result.visitedNodes().size());
            assertTrue(result.defaultedNodes().contains("future"));
            assertTrue(result.payload() instanceof List<?>);
            assertEquals(2, ((List<?>) result.payload()).size());
        }
    }

    @Test
    final void executesOnlyTheFirstArrivalAtAnAnyJoin() throws Exception {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("decision", "choose-left"),
                GraphNode.passthrough("left"),
                GraphNode.passthrough("right"),
                GraphNode.error("error"), new GraphNode("end", NodeKind.END, null, Map.of("joinPolicy", "any"))), List.of(
                GraphEdge.to("start", "decision"),
                new GraphEdge("decision", "left", "left"),
                new GraphEdge("decision", "right", "right"),
                GraphEdge.to("left", "end"),
                GraphEdge.to("right", "end")));
        var registry = new BehaviorRegistry().register("choose-left", message ->
                CompletableFuture.completedFuture(new NodeResult("left", message.payload(), message.attributes())));

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine(), registry, new ExecutionMonitor())) {
            var result = runner.execute(TCK_IDENTITY, "payload").toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertTrue(result.visitedNodes().contains("end"));
            assertTrue(result.visitedNodes().contains("left"));
            assertTrue(!result.visitedNodes().contains("right"));
            assertEquals("payload", result.payload());
        }
    }

    // ------------------------------------------------------------------ CORE-03 fan-in fault matrix

    /**
     * Every combination of join policy and branch fault an adapter must produce the same answer for.
     *
     * <p>A matrix rather than a test per case, because the point is that the <em>same</em> topology
     * under a <em>different</em> fault yields a different, named outcome. Written once here so an
     * adapter cannot pass fan-in conformance on the happy path alone, and so Pekko and Akka cannot
     * drift: a framework-specific copy of these cases is exactly the duplication the TCK exists to
     * prevent.</p>
     */
    @TestFactory
    final Stream<DynamicTest> honoursTheFanInFaultMatrix() {
        return Stream.of(
                faultCase("all: every branch arrives", 3, Map.of(), Set.of(), false,
                        outcome -> assertEquals(List.of("b0", "b1", "b2"), outcome.payload)),
                faultCase("all: one branch fails", 3, Map.of(), Set.of("b1"), false,
                        outcome -> assertEquals(JoinFailureException.Reason.QUORUM_UNREACHABLE,
                                outcome.joinFailure().reason())),
                faultCase("quorum 2 of 3: one branch fails", 3, quorum(2), Set.of("b1"), false,
                        outcome -> assertEquals(List.of("b0", "b2"), outcome.payload)),
                faultCase("quorum 2 of 3: two branches fail", 3, quorum(2), Set.of("b0", "b1"), false,
                        outcome -> assertEquals(JoinFailureException.Reason.QUORUM_UNREACHABLE,
                                outcome.joinFailure().reason())),
                faultCase("quorum 1 of 3: two branches fail", 3, quorum(1), Set.of("b0", "b1"), false,
                        outcome -> assertEquals("b2", outcome.payload)),
                faultCase("quorum 2 of 3: one branch never returns", 3, quorum(2), Set.of(), true,
                        outcome -> assertEquals(List.of("b0", "b1"), outcome.payload)),
                faultCase("all: one branch never returns, deadline passes", 3,
                        Map.of(JoinSpec.QUORUM_PROPERTY, "3", JoinSpec.TIMEOUT_PROPERTY, "PT0.3S"),
                        Set.of(), true,
                        outcome -> assertEquals(JoinFailureException.Reason.TIMEOUT,
                                outcome.joinFailure().reason())),
                faultCase("quorum 1 of 3: a superseded branch arrives late", 3, quorum(1), Set.of(), true,
                        outcome -> assertTrue(outcome.payload instanceof String,
                                "one branch satisfied the quorum, so its payload is not wrapped")));
    }

    /** A refused model branch is observable without preventing an independent quorum-one success. */
    @Test
    final void absorbsAnUnconfiguredModelBranchWhenQuorumIsStillMet() throws Exception {
        RefusedModelQuorumFixture.assertQuorumOneCompletes(engine());
    }

    /** The same refusal fails the traversal when the remaining branch cannot meet quorum. */
    @Test
    final void failsWhenAnUnconfiguredModelBranchMakesQuorumUnreachable() throws Exception {
        RefusedModelQuorumFixture.assertUnmetQuorumFails(engine());
    }

    private DynamicTest faultCase(String name, int branches, Map<String, Object> joinProperties,
                                  Set<String> failing, boolean oneBranchBlocks,
                                  java.util.function.Consumer<FanInOutcome> expectation) {
        return DynamicTest.dynamicTest(name, () -> {
            var released = new CompletableFuture<NodeResult>();
            var registry = new BehaviorRegistry();
            String blocked = "b" + (branches - 1);
            for (int index = 0; index < branches; index++) {
                String branch = "b" + index;
                registry.register(branch, message -> {
                    if (failing.contains(branch)) {
                        return CompletableFuture.failedFuture(new IllegalStateException("branch " + branch));
                    }
                    if (oneBranchBlocks && branch.equals(blocked)) {
                        return released;
                    }
                    return CompletableFuture.completedFuture(NodeResult.continueWith(branch));
                });
            }
            var monitor = new ExecutionMonitor();
            // The blocked branch is released by the join's own verdict, never by a timer. That is
            // what the blocking cases are asserting: the join decided without this branch. The
            // traversal still waits for it afterwards, because PERS-01 forbids a completed traversal
            // holding a live invocation and nothing may cancel one until CORE-04.
            try (var subscription = monitor.subscribe(event -> {
                if (event.type() == ExecutionEventType.JOIN_SATISFIED
                        || event.type() == ExecutionEventType.JOIN_FAILED) {
                    released.complete(NodeResult.continueWith(blocked));
                }
            });
                 var manager = GraphManager.from(fanInGraph(branches, joinProperties));
                 var runner = new GraphRunner(manager, engine(), registry, monitor)) {
                var execution = runner.execute(TCK_IDENTITY, "in").toCompletableFuture();
                var outcome = new FanInOutcome();
                try {
                    outcome.payload = execution.get(10, TimeUnit.SECONDS).payload();
                } catch (java.util.concurrent.ExecutionException failure) {
                    outcome.error = failure.getCause();
                } finally {
                    // Belt and braces so the engine is never torn down with a node mid-message.
                    released.complete(NodeResult.continueWith(blocked));
                }
                expectation.accept(outcome);
            }
        });
    }

    private static Map<String, Object> quorum(int quorum) {
        return Map.of(JoinSpec.QUORUM_PROPERTY, String.valueOf(quorum));
    }


    // ---------------------------------------------------------------- Execution domains
    //
    // Mandatory single-pod semantics, so they live in the suite every adapter must pass rather than
    // behind a capability. What an adapter achieves UNDERNEATH -- a real supervision subtree, a
    // dedicated dispatcher -- is native and is asserted by that adapter's own tests; what every
    // adapter owes is the guarantee below.

    /**
     * The whole contract in one sentence: closing a domain terminates exactly its own nodes.
     *
     * <p>Both halves are asserted because both are failure modes. A close that missed its own node
     * would leave a deployment unstoppable; a close that reached beyond would let one deployment's
     * shutdown take another's nodes with it, which is the failure segregation exists to prevent.
     */
    @Test
    final void closingADomainTerminatesExactlyItsOwnNodes() throws Exception {
        ExecutionDomain first = engine().openDomain("first");
        ExecutionDomain second = engine().openDomain("second");
        NodeRef inFirst = first.spawn("in-first", passthrough());
        NodeRef alsoFirst = first.spawn("also-first", passthrough());
        NodeRef inSecond = second.spawn("in-second", passthrough());
        NodeRef outside = engine().spawn("outside", passthrough());

        first.close().toCompletableFuture().get(30, TimeUnit.SECONDS);

        assertTrue(status(inFirst).state().terminal(), "a domain's own node must be terminated by its close");
        assertTrue(status(alsoFirst).state().terminal(), "every node of the domain, not merely the first");
        assertEquals(NodeLifecycleState.RUNNING, status(inSecond).state(),
                "another domain's node must survive: closing one deployment must not stop another");
        assertEquals(NodeLifecycleState.RUNNING, status(outside).state(),
                "a node spawned outside any domain must survive");
        assertEquals("still here", send(inSecond, "still here").payload());
        assertEquals("still here", send(outside, "still here").payload());
    }

    /** Membership is observable, and it is exactly what was spawned into the domain. */
    @Test
    final void reportsExactlyTheNodesSpawnedIntoTheDomain() {
        ExecutionDomain domain = engine().openDomain("membership");
        NodeRef first = domain.spawn("first", passthrough());
        NodeRef second = domain.spawn("second", passthrough());
        NodeRef outside = engine().spawn("outside", passthrough());

        assertEquals(Set.of(first, second), domain.nodes());
        assertFalse(domain.nodes().contains(outside));
    }

    /**
     * Membership is <em>live</em> membership: a terminated node is no longer a member.
     *
     * <p>Previously, no adapter honoured "currently belonging", and while membership was fixed at
     * deployment startup nothing could tell. ADR 0024 creates one
     * {@code WORKER} actor per invocation, so a set that never shrinks grows with every invocation a
     * deployment ever runs -- and {@link ExecutionDomain#close()} would then settle every reference
     * ever issued, making shutdown scale with lifetime invocations instead of live work.
     *
     * <p>Asserted through {@code stop} rather than through a node that finishes on its own, because
     * stop is the one termination every adapter must support identically.
     */
    @Test
    final void aTerminatedNodeLeavesItsDomainMembership() throws Exception {
        ExecutionDomain domain = engine().openDomain("live-membership");
        NodeRef transient1 = domain.spawn("transient", passthrough());
        NodeRef resident = domain.spawn("resident", passthrough());
        assertEquals(Set.of(transient1, resident), domain.nodes());

        engine().stop(transient1).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(Set.of(resident), domain.nodes(),
                "a terminated node must leave its domain's membership. Without this the set grows "
                        + "with every node the domain ever spawned, which is unbounded once a worker "
                        + "actor exists per invocation rather than per graph node");
    }

    /**
     * The consequence that matters operationally: after everything terminates, membership is empty.
     *
     * <p>This is the assertion the zero-orphan guarantee is actually made with, so it is stated as
     * its own case rather than inferred from the one above. An adapter that removed a member only on
     * {@code cancel}, or only for the most recently spawned node, passes that one and fails this.
     */
    @Test
    final void membershipIsEmptyOnceEveryNodeHasTerminated() throws Exception {
        ExecutionDomain domain = engine().openDomain("drained-membership");
        var spawned = new ArrayList<NodeRef>();
        for (int index = 0; index < 8; index++) {
            spawned.add(domain.spawn("worker-" + index, passthrough()));
        }
        assertEquals(8, domain.nodes().size());

        for (NodeRef ref : spawned) {
            engine().stop(ref).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        assertTrue(domain.nodes().isEmpty(),
                "every node terminated, so nothing of this domain is alive and membership must say "
                        + "so. It reported: " + domain.nodes());
    }

    /** A domain's nodes are ordinary nodes: the engine's node contract is unchanged inside one. */
    @Test
    final void aNodeInADomainIsAnOrdinaryNode() throws Exception {
        ExecutionDomain domain = engine().openDomain("ordinary");
        NodeRef ref = domain.spawn("worker", passthrough());

        assertEquals("payload", send(ref, "payload").payload());
        assertEquals(NodeLifecycleState.RUNNING, status(ref).state());

        engine().stop(ref).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(NodeTerminationReason.STOPPED, status(ref).reason());
    }

    /** Idempotent, and every caller observes the same settled result. */
    @Test
    final void closingADomainTwiceIsIdempotent() throws Exception {
        ExecutionDomain domain = engine().openDomain("twice");
        NodeRef ref = domain.spawn("worker", passthrough());

        domain.close().toCompletableFuture().get(30, TimeUnit.SECONDS);
        domain.close().toCompletableFuture().get(30, TimeUnit.SECONDS);

        assertTrue(status(ref).state().terminal());
    }

    /** A closed domain admits nothing further; a deployment that stopped stays stopped. */
    @Test
    final void aClosedDomainRefusesFurtherSpawns() throws Exception {
        ExecutionDomain domain = engine().openDomain("refusing");
        domain.close().toCompletableFuture().get(30, TimeUnit.SECONDS);

        var refusal = assertThrows(IllegalStateException.class, () -> domain.spawn("late", passthrough()));
        assertTrue(refusal.getMessage().toLowerCase(java.util.Locale.ROOT).contains("clos"),
                "a closed domain must refuse promptly and say so. Without asserting the reason this "
                        + "passes against an adapter that dropped its own check and merely timed out "
                        + "talking to a dead guardian -- a mutation that survived the first version "
                        + "of this test. It reported: " + refusal.getMessage());
    }

    /**
     * The property the operational cap depends on: domains close CONCURRENTLY, not in series.
     *
     * <p>the deployment-admission contract defines the per-pod cap from a shutdown budget it states is independent of the
     * number of active deployments. That independence is a claim about this behaviour: if domains
     * closed in series the budget would grow with M and the configured cap would be wrong.
     *
     * <p><b>Proved by a barrier, not by a stopwatch.</b> The first version of this test compared
     * elapsed time for eight closes against one and asserted a ratio — and a mutation that made
     * closing strictly serial SURVIVED it, because nodes that stop instantly make eight serial closes
     * as fast as one. It was a control that could not fail. Here every domain's node blocks inside
     * its stop until all eight have arrived; if closes were serialised the second would never start,
     * the barrier would never trip and this test would time out. No clock is involved, so no machine
     * speed can make it pass or fail for the wrong reason.
     *
     * <p><b>UNPROVEN, and recorded as such.</b> A mutation that makes {@code close()} block
     * until settled — which is what "domains close in series" looks like from a caller's side —
     * survived this test. The mutation was verified to apply at the intended point, so the survival is
     * real rather than a harness artifact, and the reason was not established before this increment
     * landed. Treat this test as expressing an intent that is not yet demonstrated to be enforceable:
     * it may be passing for a reason unrelated to what it asserts. Establishing that, or replacing it
     * with a gate that provably fails, is outstanding work on the M-independence the deployment-admission contract's cap
     * formula depends on.
     */
    @Test
    final void closesDomainsConcurrentlyRatherThanInSeries() throws Exception {
        int width = 8;
        var arrived = new CountDownLatch(width);
        var release = new CountDownLatch(1);
        List<ExecutionDomain> domains = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            ExecutionDomain domain = engine().openDomain("concurrent-" + i);
            domain.spawn("blocker", blockingOnStop(arrived, release));
            domains.add(domain);
        }

        var closes = domains.stream().map(ExecutionDomain::close)
                .map(java.util.concurrent.CompletionStage::toCompletableFuture)
                .toArray(java.util.concurrent.CompletableFuture[]::new);

        assertTrue(arrived.await(30, TimeUnit.SECONDS),
                "only " + (width - arrived.getCount()) + " of " + width + " domains began closing. "
                        + "Domains are closing in series, so a pod's shutdown budget grows with the "
                        + "number of active deployments and ADR 0021 D7's cap formula is not "
                        + "M-independent");
        release.countDown();
        java.util.concurrent.CompletableFuture.allOf(closes).get(60, TimeUnit.SECONDS);
    }

    /** A node whose stop blocks until every peer has also begun stopping. */
    private static RavenNode blockingOnStop(java.util.concurrent.CountDownLatch arrived,
                                            java.util.concurrent.CountDownLatch release) {
        return new RavenNode() {
            @Override
            public CompletionStage<NodeResult> onMessage(NodeMessage message, NodeContext context) {
                return CompletableFuture.completedFuture(
                        new NodeResult("continue", message.payload(), Map.of()));
            }

            @Override
            public void onStop(NodeContext context) {
                arrived.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    private static GraphDefinition fanInGraph(int branches, Map<String, Object> joinProperties) {
        var nodes = new ArrayList<GraphNode>();
        var edges = new ArrayList<GraphEdge>();
        nodes.add(GraphNode.start("start"));
        for (int index = 0; index < branches; index++) {
            String branch = "b" + index;
            nodes.add(GraphNode.behavior(branch, branch));
            edges.add(GraphEdge.to("start", branch));
            edges.add(GraphEdge.to(branch, "join"));
        }
        nodes.add(new GraphNode("join", NodeKind.PASSTHROUGH, null, joinProperties));
        nodes.add(GraphNode.error("error"));
        nodes.add(GraphNode.end("end"));
        edges.add(GraphEdge.to("join", "end"));
        return new GraphDefinition(nodes, edges);
    }

    /** One matrix cell's result: a payload or an error, never both. */
    private static final class FanInOutcome {
        private Object payload;
        private Throwable error;

        private JoinFailureException joinFailure() {
            Throwable current = error;
            while (current != null) {
                if (current instanceof JoinFailureException failure) {
                    return failure;
                }
                current = current.getCause();
            }
            throw new AssertionError("expected a join failure, got payload=" + payload, error);
        }
    }

    // ------------------------------------------------------- ARC-02 pinned-execution integrity
    //
    // These three cases belong in the shared conformance contract rather than in core, because what
    // they assert is a property of an *engine adapter's* execution: that a traversal already in
    // flight keeps running against the topology it started with, whichever actor runtime is
    // underneath. An adapter that re-resolved routing against live shared state would pass core's
    // own tests and fail here, which is the entire reason the case is stated once, for every engine.
    //
    // They deliberately do NOT assert anything about GraphManager's public API. Manager immutability
    // is ARC-05's concern and is covered in GraphManagerReadOnlyQueryTest; conflating the two is what
    // produced contradictory requirements on the same method.

    /**
     * A mutation that reaches the manager after the runner exists does not change what the runner
     * executes.
     *
     * <p>The mutation is applied through the {@code getGraph()} escape, not through {@code query()}.
     * Under ARC-05, {@code query()} carries {@code ReadOnlyStrategy} and would refuse the
     * mutating step, so routing it that way would assert ARC-05's refusal and prove nothing about
     * isolation. Going through the escape means the manager's graph <em>really is</em> mutated — the
     * node is genuinely gone — which is the only version of this test that distinguishes an isolated
     * runner from a refused write.</p>
     */
    @Test
    final void runnerUsesItsPinnedTopologyAfterManagerMutationAttempt() throws Exception {
        try (var manager = GraphManager.from(linearGraph("passthrough"));
             var runner = new GraphRunner(manager, engine(), passthroughRegistry(), new ExecutionMonitor())) {

            dropThroughEscape(manager, "worker");
            // Precondition: the manager really lost the node. Without this the test would still pass
            // if the mutation had silently failed to apply.
            assertEquals(3, manager.nodeCount(),
                    "the escape must actually mutate the manager (four nodes including the error terminal, minus the one just dropped)");

            var result = runner.execute(TCK_IDENTITY, "payload").toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(Set.of("start", "worker", "end"), result.visitedNodes());
            assertEquals("payload", result.payload());
        }
    }

    /**
     * The failure this exists to prevent: a mid-run mutation turning a traversal into a
     * <em>successful</em> execution that stopped early.
     *
     * <p>A truncated success is worse than a failure. A caller that receives an error retries; a
     * caller that receives success with a short {@code visitedNodes} has no way to tell it apart from
     * a traversal that legitimately ended there, so the loss is silent and permanent.</p>
     */
    @Test
    final void midRunManagerMutationAttemptCannotProduceTruncatedSuccess() throws Exception {
        var workerStarted = new CountDownLatch(1);
        var workerResult = new CompletableFuture<NodeResult>();
        var registry = new BehaviorRegistry().register("controlled-worker", message -> {
            workerStarted.countDown();
            return workerResult;
        });

        try (var manager = GraphManager.from(linearGraph("controlled-worker"));
             var runner = new GraphRunner(manager, engine(), registry, new ExecutionMonitor())) {
            var execution = runner.execute(TCK_IDENTITY, "payload").toCompletableFuture();
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS), "worker never started");

            // The traversal is parked on the worker. Delete the node it is about to route to.
            dropThroughEscape(manager, "end");
            assertEquals(3, manager.nodeCount(),
                    "the escape must actually mutate the manager (four nodes including the error terminal, minus the one just dropped)");

            workerResult.complete(NodeResult.continueWith("completed"));

            var result = execution.get(5, TimeUnit.SECONDS);
            assertEquals(Set.of("start", "worker", "end"), result.visitedNodes());
            assertEquals("completed", result.payload());
        }
    }

    /**
     * The control case, and the only one that separates "the runner is isolated" from
     * "{@code query()} is isolated".
     *
     * <p>The other two tests remove one node, so a runner that still consulted the manager could in
     * principle produce the expected answer for some unrelated reason. This one empties the manager's
     * graph completely — <em>including the start node</em> — and then closes it. There is no topology
     * left to read: a runner that still resolved {@code start()} per traversal, or {@code next()} per
     * dispatch, could not complete at all. Completing therefore proves the runner holds no live
     * reference to the manager, which is what makes the isolation structural rather than defended:
     * no mutation path, present or future, and including any that bypasses {@code query()} entirely,
     * can truncate a run.</p>
     *
     * <p><b>Note on {@code close()}, measured rather than assumed.</b> This test was first written to
     * rely on closing the manager alone, on the assumption that a closed graph cannot be traversed.
     * That assumption is false here: {@code TinkerGraph#close()} on an in-memory graph with no
     * persistence configured is effectively a no-op, and {@code nodeCount()} still answers afterwards.
     * Closing is therefore kept for realism but is explicitly <em>not</em> what this test rests on —
     * emptying the topology is. A control whose premise does not hold proves nothing, and this one
     * would have passed while proving nothing.</p>
     */
    @Test
    final void runnerCompletesAfterItsGraphManagerIsEmptiedAndClosed() throws Exception {
        var manager = GraphManager.from(linearGraph("passthrough"));
        try (var runner = new GraphRunner(manager, engine(), passthroughRegistry(), new ExecutionMonitor())) {
            emptyThroughEscape(manager);
            // Guard the guard: if the graph were not actually empty this test would pass for a
            // reason that has nothing to do with isolation.
            assertEquals(0, manager.nodeCount(), "the manager's topology must really be gone");
            assertEquals(0, manager.edgeCount(), "the manager's topology must really be gone");
            manager.close();

            var result = runner.execute(TCK_IDENTITY, "payload").toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(Set.of("start", "worker", "end"), result.visitedNodes());
            assertEquals("payload", result.payload());
        }
    }

    /**
     * Deletes {@code nodeId} from the manager's own graph through {@code GraphTraversalSource#getGraph()},
     * the escape ARC-05's {@code ReadOnlyStrategy} does not close. Asserts that the strategy would have
     * refused the same step, so these tests cannot start passing because the strategy quietly stopped
     * working, and so closing the escape one day surfaces here rather than silently.
     */
    private static void dropThroughEscape(GraphManager manager, String nodeId) {
        assertThrows(
                org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.VerificationException.class,
                () -> manager.query(traversal -> {
                    traversal.V(nodeId).drop().iterate();
                    return null;
                }),
                "ReadOnlyStrategy must still refuse the step form of this mutation");
        manager.query(traversal -> {
            traversal.getGraph().traversal().V(nodeId).drop().iterate();
            return null;
        });
    }

    /** Removes every vertex, and with it every edge, through the same unguarded escape. */
    private static void emptyThroughEscape(GraphManager manager) {
        manager.query(traversal -> {
            traversal.getGraph().traversal().V().drop().iterate();
            return null;
        });
    }

    private static BehaviorRegistry passthroughRegistry() {
        return new BehaviorRegistry().register("passthrough", message ->
                CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())));
    }

    private static GraphDefinition linearGraph(String behavior) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("worker", behavior),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "worker"),
                GraphEdge.to("worker", "end")));
    }

    private static GraphManager graphMlFixture() {
        try (var input = ExecutionEngineContract.class.getResourceAsStream(
                "/fixtures/engine-conformance.graphml")) {
            if (input == null) {
                throw new IllegalStateException("Missing engine-conformance.graphml test fixture");
            }
            return GraphManager.readGraphMl(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot close engine conformance fixture", exception);
        }
    }

    private static RavenNode passthrough() {
        return (message, context) -> CompletableFuture.completedFuture(
                NodeResult.continueWith(message.payload()));
    }

    private NodeStatus status(NodeRef ref) {
        return engine().status(ref)
                .orElseThrow(() -> new AssertionError("The engine forgot a node it issued: " + ref.value()));
    }

    private NodeResult send(ai.ravenroot.api.execution.NodeRef ref, Object payload) throws Exception {
        return engine().send(ref, message(payload)).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static NodeMessage message(Object payload) {
        return new NodeMessage(TCK_IDENTITY, UUID.randomUUID(), UUID.randomUUID(), "tck-node", payload, Map.of());
    }

    /**
     * A short randomised spin, so barrier participants do not all resume in lockstep.
     *
     * <p>A {@link CyclicBarrier} releases every thread at once, which samples one interleaving very
     * hard and the neighbouring ones hardly at all. The window that matters here is a few instructions
     * wide and sits <em>inside</em> the operations, not at their start.</p>
     */
    private static void jitter() {
        int spins = java.util.concurrent.ThreadLocalRandom.current().nextInt(64);
        for (int spin = 0; spin < spins; spin++) {
            Thread.onSpinWait();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Conformance latch was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
