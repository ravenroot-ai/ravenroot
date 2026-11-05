package ai.ravenroot.core.runtime.builtin;
import ai.ravenroot.api.catalog.*;
import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
/** Runtime-owned durable close signal; the ordinary pure handler owns no external resource. */
final class CorrelationCloseNodeBehaviorFactory implements NodeBehaviorFactory {
    @Override public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor(CorrelationOperationProperties.CLOSE, "Close business history", "Correlation",
                "Requests explicit closure after already-admitted causal work settles; new external events are refused immediately.", "actor", false,
                CorrelationOperationProperties.closeDescriptors(), Set.of("durable", "application-correlation"))
                .withOutcomes(NodeOutcomeDescriptor.literal("continue", "The original causal close signal and node completion committed together."));
    }
    @Override public NodeHandler create(GraphNode node) {
        CorrelationOperationProperties.auditReason(node.properties()); CorrelationOperationProperties.disposition(node.properties());
        return message -> CompletableFuture.completedFuture(new NodeResult("continue", message.payload(), message.attributes()));
    }
}
