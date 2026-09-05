package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.ToolCallContinuationAction;
import ai.ravenroot.core.graph.GraphNode;

import java.util.Optional;

/** Creates one handler per graph node so configuration and state are never shared accidentally. */
public interface NodeBehaviorFactory {
    NodeTypeDescriptor descriptor();

    NodeHandler create(GraphNode node);

    /** Fail-closed opt-in for trusted, package-owned durable checkpoint decoding. */
    default Optional<ToolCallContinuationAction> createToolCallContinuation(GraphNode node) {
        return Optional.empty();
    }
}
