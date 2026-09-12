package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface NodeHandler {
    CompletionStage<NodeResult> handle(NodeMessage message);

    /**
     * Cancellation-aware dispatch path. The default retains binary compatibility for built-in and
     * embedding handlers compiled against the original one-argument functional interface.
     */
    default CompletionStage<NodeResult> handle(NodeMessage message, CancellationSignal cancellation) {
        return handle(message);
    }
}
