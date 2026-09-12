package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.ToolCallContinuationAction;
import ai.ravenroot.core.graph.GraphNode;

import java.util.Optional;

/** Creates one handler per graph node so configuration and state are never shared accidentally. */
public interface NodeBehaviorFactory {
    NodeTypeDescriptor descriptor();

    /**
     * Validates cross-property configuration and required runtime capabilities before traversal.
     * @param node exact graph node being admitted
     */
    default void validate(GraphNode node) {
        // Most behaviors are fully described by the generic property schema.
    }

    NodeHandler create(GraphNode node);

    /** Fail-closed opt-in for trusted, package-owned durable checkpoint decoding. */
    default Optional<ToolCallContinuationAction> createToolCallContinuation(GraphNode node) {
        return Optional.empty();
    }
}
