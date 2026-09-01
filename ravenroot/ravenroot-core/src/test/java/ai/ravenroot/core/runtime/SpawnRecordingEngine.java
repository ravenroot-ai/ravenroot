package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.CancellationSignal;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An engine that records <em>which</em> nodes were spawned and when, relative to other lifecycle
 * events.
 *
 * <p>{@code JoinTestEngine} already counts spawns, which answers "did composition create an actor
 * before rejecting the graph". It cannot answer "which node, and did it exist before that source
 * started", and both halves matter for a nature whose runtime has two independent aspects. Kept
 * separate rather than bolted onto that fixture, because a shared fixture that grows a field per test
 * becomes a fixture nobody can change.
 */
final class SpawnRecordingEngine implements ExecutionEngine {

    /**
     * Interleaved lifecycle log. Assigned by the test before the deployment is built so that spawns
     * and source callbacks land in the same list in real order.
     */
    List<String> events = new CopyOnWriteArrayList<>();

    private final Map<NodeRef, RavenNode> behaviours = new ConcurrentHashMap<>();
    private final Map<NodeRef, NodeLifecycleState> states = new ConcurrentHashMap<>();
    private final List<String> spawnedLogicalNames = new CopyOnWriteArrayList<>();
    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    private volatile EngineState state = EngineState.RUNNING;

    /** The logical names handed to {@link #spawn}, in order. */
    List<String> spawnedLogicalNames() {
        return List.copyOf(spawnedLogicalNames);
    }

    List<Delivery> deliveries() { return List.copyOf(deliveries); }

    int liveNodeCount() {
        return (int) states.values().stream().filter(state -> !state.terminal()).count();
    }

    @Override
    public String id() {
        return "spawn-recording";
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
        if (!state.accepting()) {
            throw new IllegalStateException("Execution engine is " + state);
        }
        spawnedLogicalNames.add(logicalName);
        events.add("spawn:" + logicalName);
        var ref = new NodeRef(logicalName + "-" + UUID.randomUUID());
        behaviours.put(ref, node);
        states.put(ref, NodeLifecycleState.RUNNING);
        return ref;
    }

    @Override
    public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
        RavenNode node = behaviours.get(target);
        if (node == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown node: " + target.value()));
        }
        deliveries.add(new Delivery(message.nodeId(), message.traversalId(), target));
        return node.onMessage(message, context(target));
    }

    @Override
    public Optional<NodeStatus> status(NodeRef target) {
        return Optional.ofNullable(states.get(target))
                .map(value -> new NodeStatus(target, value,
                        value.terminal() ? NodeTerminationReason.STOPPED : null, 0));
    }

    @Override
    public CompletionStage<Void> stop(NodeRef target) {
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
                return SpawnRecordingEngine.this.scheduler();
            }

            @Override
            public Mailbox mailbox() {
                return () -> 0;
            }

            @Override
            public CancellationSignal cancellation() {
                return new CancellationSignal() {
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

    /** Unused by these tests; present so the fixture is a complete engine. */
    @SuppressWarnings("unused")
    private static Map<String, Object> noAttributes() {
        return Map.of();
    }

    record Delivery(String nodeId, UUID traversalId, NodeRef target) { }
}
