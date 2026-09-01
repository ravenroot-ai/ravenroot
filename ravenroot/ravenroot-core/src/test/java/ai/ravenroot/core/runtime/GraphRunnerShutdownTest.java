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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GraphRunner.close()} must terminate, whatever the nodes underneath it do.
 *
 * <p>It used to be {@code CompletableFuture.allOf(stops).join()}: no timeout, no escalation. A stop is
 * bounded by the node's own work, so any node that never completes made close() block forever — and a
 * node killed by an {@link Error} is exactly that node, because the actor that would have completed
 * its stop stage is the thing that died. Both adapters shipped it.</p>
 *
 * <p>These assertions use a stub engine rather than a real adapter deliberately, and this is the one
 * place a core stub is allowed to model stop and cancel. The property under test is the runner's
 * <em>shutdown policy</em> — how long it waits and what it does when the wait expires — and pinning a
 * policy needs a node that stays wedged on demand, not a real one that might not. Whether an adapter
 * can actually strand a stop is asserted where it belongs, against the real engines in
 * {@code ExecutionEngineContract}.</p>
 *
 * <p>Every case is wrapped in {@link org.junit.jupiter.api.Assertions#assertTimeoutPreemptively}: on
 * without bounded shutdown these are not slow tests, they are tests that never return, and a build that hangs
 * reports nothing at all.</p>
 */
class GraphRunnerShutdownTest {

    private static final Duration BOUND = Duration.ofMillis(300);
    private static final Duration NEVER_HANGS = Duration.ofSeconds(20);

    @Test
    void escalatesToCancellationWhenAStopWillNotComplete() {
        var engine = new WedgeableEngine(false);

        assertTimeoutPreemptively(NEVER_HANGS, () -> runnerWithLiveWorkers(engine).close());

        // Every live instance was asked to stop, every one was then asked to cancel, and the second
        // request is what actually ended them. A close() that merely gave up would leave them running.
        // All four, and the fourth is the interesting one. Three branches are still inside their
        // invocation; start's invocation FINISHED and released its instance, but this engine wedges
        // stops, so that release asked for a termination that never arrived. An instance in that state
        // is still an actor somebody must account for, so the runner keeps owing it a termination
        // until the stop confirms -- otherwise a node whose onStop hangs would be dropped by the
        // runner and escalated by nobody, which is an orphan produced by the very release meant to
        // prevent one.
        assertEquals(engine.spawned(), engine.cancelled,
                "every actor still outstanding at close must be escalated to a cancellation, "
                        + "including one whose release asked for a stop that never landed");
        engine.nodes.values().forEach(node ->
                assertEquals(NodeLifecycleState.TERMINATED, node.state));
    }

    @Test
    void reportsTheNodesItCouldNotTerminateInsteadOfBlockingOnThem() {
        var engine = new WedgeableEngine(true);

        var failure = assertTimeoutPreemptively(NEVER_HANGS, () -> {
            var runner = runnerWithLiveWorkers(engine);
            return assertThrows(IllegalStateException.class, runner::close);
        });

        // The diagnostic names the graph node ids, not the engine's generated refs: the reader of
        // this message is looking at a graph, and a uuid-suffixed actor name tells them nothing. Since
        // demand-driven workers require the invocation too, because one logical node may have many instances alive and
        // "work did not stop" would not say which of them is holding the shutdown.
        assertTrue(failure.getMessage().contains("work"), failure.getMessage());
        assertTrue(failure.getMessage().contains("work-b"), failure.getMessage());
        assertTrue(failure.getMessage().contains("work-c"), failure.getMessage());
        // See escalatesToCancellationWhenAStopWillNotComplete: start's instance was released but its
        // stop never landed, so it is still outstanding and is escalated with the rest.
        assertEquals(engine.spawned(), engine.cancelled);
    }

    @Test
    void propagatesAFailedStopRatherThanEscalatingIt() {
        var engine = new WedgeableEngine(false);
        engine.failStopsWith = new IllegalStateException("onStop threw");

        var failure = assertTimeoutPreemptively(NEVER_HANGS, () -> {
            var runner = runnerWithLiveWorkers(engine);
            return assertThrows(Exception.class, runner::close);
        });

        // A node whose onStop threw has stopped, badly. Escalating that into a cancellation would
        // replace a real diagnosis with a generic one and hide the defect in the node.
        assertEquals("onStop threw", rootCause(failure).getMessage());
        assertEquals(0, engine.cancelled, "a failed stop is terminal, so there is nothing to escalate");
    }

    @Test
    void closesPromptlyWhenEveryNodeStopsNormally() {
        var engine = new WedgeableEngine(false);
        engine.wedgeStops = false;

        assertTimeoutPreemptively(NEVER_HANGS, () -> runnerWithLiveWorkers(engine).close());

        assertEquals(engine.spawned(), engine.stopped);
        assertEquals(0, engine.cancelled, "a stop that completes must not be escalated");
    }

    private static GraphRunner runner(ExecutionEngine engine) {
        return new GraphRunner(GraphManager.from(graph()), engine, new BehaviorRegistry(),
                new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(), BOUND);
    }

    /**
     * A fan-out, so that more than one worker instance is alive at the same moment.
     *
     * <p>It used to be a straight {@code start -> work -> end} chain, which was enough while every
     * graph node had a resident actor from construction. Now an actor exists only while an invocation
     * is in flight, so a runner that has run nothing has nothing to stop, and every assertion below
     * about stopping, escalating and reporting would pass vacuously. The three parallel branches are
     * parked inside their nodes by {@code wedgeSends}, which is what puts three instances in the
     * registry at the moment {@code close()} is called.
     */
    private static GraphDefinition graph() {
        return new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.passthrough("work"),
                        GraphNode.passthrough("work-b"), GraphNode.passthrough("work-c"),
                        GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "work"), GraphEdge.to("start", "work-b"),
                        GraphEdge.to("start", "work-c"), GraphEdge.to("work", "end")));
    }

    /**
     * Starts a traversal and leaves it parked inside its worker nodes, so the runner really owns live
     * instances when the assertion under test runs. The returned stage is deliberately not waited on:
     * the whole point is that it never completes.
     */
    private static GraphRunner runnerWithLiveWorkers(WedgeableEngine engine) {
        engine.wedgeSends = true;
        GraphRunner runner = runner(engine);
        runner.execute(TestIdentities.TENANT_A, "payload");
        return runner;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * An engine whose nodes answer messages normally but can be told never to finish stopping.
     *
     * <p>{@code cancel} always completes, because that is the guarantee the SPI actually makes about
     * it — unless this stub is built to wedge that too, which is how the "nothing worked" branch is
     * reached without waiting for a real node to misbehave.</p>
     */
    private static final class WedgeableEngine implements ExecutionEngine {
        private final Map<NodeRef, StubNode> nodes = new LinkedHashMap<>();
        private final boolean wedgeCancels;
        private boolean wedgeStops = true;
        /**
         * Parks deliveries to the {@code work*} nodes, so their invocations stay live and their worker
         * instances with them.
         *
         * <p>Selective on purpose. Parking the START node instead would stop the traversal at its
         * first hop and leave exactly one instance alive, and every assertion here is about a close()
         * that has several actors to settle. Letting start complete and parking the three branches it
         * fans out to is what puts three of them in the registry at once.
         */
        private boolean wedgeSends;
        private RuntimeException failStopsWith;
        private int stopped;
        private int cancelled;
        private EngineState state = EngineState.RUNNING;

        private WedgeableEngine(boolean wedgeCancels) {
            this.wedgeCancels = wedgeCancels;
        }

        private int spawned() {
            return nodes.size();
        }

        @Override
        public String id() {
            return "wedgeable";
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
            var ref = new NodeRef(logicalName + "-" + UUID.randomUUID());
            nodes.put(ref, new StubNode(node));
            return ref;
        }

        @Override
        public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
            if (wedgeSends && target.value().startsWith("work")) {
                return new CompletableFuture<>();
            }
            return nodes.get(target).node.onMessage(message, context(target));
        }

        @Override
        public Optional<NodeStatus> status(NodeRef target) {
            return Optional.ofNullable(nodes.get(target)).map(node -> new NodeStatus(target, node.state,
                    node.state.terminal() ? NodeTerminationReason.STOPPED : null, 0));
        }

        @Override
        public synchronized CompletionStage<Void> stop(NodeRef target) {
            stopped++;
            StubNode node = nodes.get(target);
            if (failStopsWith != null) {
                return CompletableFuture.failedFuture(failStopsWith);
            }
            if (wedgeStops) {
                return new CompletableFuture<>();
            }
            node.state = NodeLifecycleState.TERMINATED;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public synchronized CompletionStage<Void> cancel(NodeRef target) {
            cancelled++;
            if (wedgeCancels) {
                return new CompletableFuture<>();
            }
            nodes.get(target).state = NodeLifecycleState.TERMINATED;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> drain() {
            state = EngineState.DRAINING;
            return CompletableFuture.allOf(new ArrayList<>(nodes.keySet()).stream()
                    .map(this::stop)
                    .map(CompletionStage::toCompletableFuture)
                    .toArray(CompletableFuture[]::new));
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
                    return WedgeableEngine.this.scheduler();
                }

                @Override
                public Mailbox mailbox() {
                    return () -> 0;
                }

                @Override
                public ai.ravenroot.api.execution.CancellationSignal cancellation() {
                    return StubEngineLifecycle.NEVER_CANCELLED;
                }
            };
        }
    }

    private static final class StubNode {
        private final RavenNode node;
        private NodeLifecycleState state = NodeLifecycleState.RUNNING;

        private StubNode(RavenNode node) {
            this.node = node;
        }
    }
}
