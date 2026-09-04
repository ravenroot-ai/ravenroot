package ai.ravenroot.api.node;

import ai.ravenroot.api.execution.NodeResult;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Immediate terminal outcome of the redeemed effect plus its possibly suspended continuation. */
public record ToolCallContinuationResult(CompletionStage<NodeResult> nodeResult,
                                         boolean effectSucceeded) {
    public ToolCallContinuationResult {
        Objects.requireNonNull(nodeResult, "nodeResult");
    }
}
