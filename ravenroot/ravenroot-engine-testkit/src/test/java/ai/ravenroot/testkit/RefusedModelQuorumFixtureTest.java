package ai.ravenroot.testkit;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.Mailbox;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeLifecycleState;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.Scheduler;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/** Runs the cross-engine quorum fixture without an actor-runtime scheduling layer. */
class RefusedModelQuorumFixtureTest {
    @Test
    void quorumOneCompletesOnTheDirectEngine() throws Exception {
        try (var engine = new DirectEngine()) {
            RefusedModelQuorumFixture.assertQuorumOneCompletes(engine);
        }
    }

    @Test
    void unmetQuorumFailsOnTheDirectEngine() throws Exception {
        try (var engine = new DirectEngine()) {
            RefusedModelQuorumFixture.assertUnmetQuorumFails(engine);
        }
    }

    private static final class DirectEngine implements ai.ravenroot.api.execution.ExecutionEngine {
        private static final CancellationSignal NEVER_CANCELLED = new CancellationSignal() {
            @Override
            public boolean cancelled() {
                return false;
            }

            @Override
            public void onCancel(Runnable listener) {
            }
        };
        private final Map<NodeRef, RavenNode> nodes = new ConcurrentHashMap<>();

        @Override
        public String id() {
            return "direct-quorum-fixture";
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
            var reference = new NodeRef(logicalName + "-" + UUID.randomUUID());
            nodes.put(reference, node);
            return reference;
        }

        @Override
        public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
            RavenNode node = nodes.get(target);
            if (node == null) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("unknown node " + target.value()));
            }
            return node.onMessage(message, context(target));
        }

        @Override
        public Optional<NodeStatus> status(NodeRef target) {
            return nodes.containsKey(target)
                    ? Optional.of(new NodeStatus(target, NodeLifecycleState.RUNNING, null, 0))
                    : Optional.empty();
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
        public CompletionStage<Void> drain() {
            nodes.clear();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            nodes.clear();
        }

        private NodeContext context(NodeRef target) {
            return new NodeContext() {
                @Override
                public NodeRef self() {
                    return target;
                }

                @Override
                public Scheduler scheduler() {
                    return DirectEngine.this.scheduler();
                }

                @Override
                public Mailbox mailbox() {
                    return () -> 0;
                }

                @Override
                public CancellationSignal cancellation() {
                    return NEVER_CANCELLED;
                }
            };
        }
    }
}
