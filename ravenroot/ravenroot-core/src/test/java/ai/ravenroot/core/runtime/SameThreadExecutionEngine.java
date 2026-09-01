package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.CancellationSignal;
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

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs nodes on the calling thread. Not a simplification: it removes the engine as a source of
 * timing from tests whose subject is <em>which thread</em> carries a traversal forward, so the only
 * concurrency left is the one under test.
 *
 * <p>Two tests depend on that property for different reasons, and both state it themselves rather
 * than assume it. {@code CancelInStartupWindowTest} needs the submitting thread against the
 * cancelling thread and nothing else. {@code PauseGateReleaseThreadTest} needs the submitting
 * thread to be the <em>only</em> thread that can carry a traversal forward, which is what turns
 * "the submitter has terminated and the next node has not run" into proof that a hop is parked.</p>
 */
final class SameThreadExecutionEngine implements ExecutionEngine {
    private final Map<NodeRef, RavenNode> nodes = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "same-thread";
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
    public NodeRef spawn(String logicalName, RavenNode node) {
        var ref = new NodeRef(logicalName + "-" + UUID.randomUUID());
        nodes.put(ref, node);
        return ref;
    }

    @Override
    public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
        RavenNode node = nodes.get(target);
        if (node == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("unknown node " + target.value()));
        }
        return node.onMessage(message, context(target));
    }

    @Override
    public CompletionStage<Void> stop(NodeRef target) {
        nodes.remove(target);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> cancel(NodeRef target) {
        return stop(target);
    }

    @Override
    public EngineState state() {
        return EngineState.RUNNING;
    }

    @Override
    public Optional<NodeStatus> status(NodeRef target) {
        return Optional.of(StubEngineLifecycle.running(target));
    }

    @Override
    public CompletionStage<Void> drain() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
        nodes.clear();
    }

    private NodeContext context(NodeRef ref) {
        return new NodeContext() {
            @Override
            public NodeRef self() {
                return ref;
            }

            @Override
            public Scheduler scheduler() {
                return SameThreadExecutionEngine.this.scheduler();
            }

            @Override
            public Mailbox mailbox() {
                return () -> 0;
            }

            @Override
            public CancellationSignal cancellation() {
                return StubEngineLifecycle.NEVER_CANCELLED;
            }
        };
    }
}
