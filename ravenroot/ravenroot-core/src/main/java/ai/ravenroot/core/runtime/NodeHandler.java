package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface NodeHandler {
    CompletionStage<NodeResult> handle(NodeMessage message);
}
