package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.runtime.NodeHandler;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;

/** A bounded, asynchronous pause that never sleeps an actor-system worker thread. */
final class DelayNodeBehaviorFactory implements NodeBehaviorFactory {
    static final long DEFAULT_DURATION_MS = 1_000;
    static final long MAX_DURATION_MS = TimeUnit.HOURS.toMillis(24);

    private final LongFunction<Executor> delayedExecutor;

    DelayNodeBehaviorFactory() {
        this(durationMs -> CompletableFuture.delayedExecutor(durationMs, TimeUnit.MILLISECONDS));
    }

    DelayNodeBehaviorFactory(LongFunction<Executor> delayedExecutor) {
        this.delayedExecutor = delayedExecutor;
    }

    @Override
    public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor("delay", "Delay", "Control flow",
                "Waits asynchronously for a bounded duration, then passes the payload through unchanged.",
                "flow", false, List.of(
                NodePropertyDescriptor.optional("durationMs", "Duration (ms)", NodePropertyType.INTEGER,
                        "Milliseconds to wait (0-86400000). The wait does not block an actor-system thread.",
                        Long.toString(DEFAULT_DURATION_MS))),
                Set.of("control-flow", "non-blocking"))
                .withOutcomes(NodeOutcomeDescriptor.literal("continue",
                        "The wait elapsed and the payload passes through unchanged. The only outcome "
                                + "this node produces: the handler below returns it unconditionally."));
    }

    @Override
    public NodeHandler create(GraphNode node) {
        long durationMs = NodeProperties.number(node, "durationMs", DEFAULT_DURATION_MS);
        if (durationMs < 0 || durationMs > MAX_DURATION_MS) {
            throw new IllegalArgumentException("Node " + node.id() + " property 'durationMs' must be between 0 and "
                    + MAX_DURATION_MS);
        }
        return message -> CompletableFuture.supplyAsync(
                () -> new NodeResult("continue", message.payload(), message.attributes()),
                delayedExecutor.apply(durationMs));
    }
}
