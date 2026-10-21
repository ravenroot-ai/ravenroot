package ai.ravenroot.core.runtime.builtin;
import ai.ravenroot.api.catalog.*;
import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
/** Explicit new context after a structured transform; runtime owns fenced custody and lineage. */
final class CorrelationDeriveNodeBehaviorFactory implements NodeBehaviorFactory {
    @Override public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor(CorrelationOperationProperties.DERIVE, "Derive business context", "Correlation",
                "Extracts a new business identity from structured input and transfers bounded custody to one authored successor. The original context stays immutable.", "actor", false,
                CorrelationOperationProperties.deriveDescriptors(CorrelationLimits.DEFAULTS), Set.of("durable", "application-correlation"))
                .withOutcomes(NodeOutcomeDescriptor.literal("continue", "One pinned successor begins a distinct traversal with audited original causality."));
    }
    @Override public NodeHandler create(GraphNode node) {
        CorrelationOperationProperties.auditReason(node.properties());
        return message -> CompletableFuture.completedFuture(new NodeResult("continue", message.payload(), message.attributes()));
    }
}
