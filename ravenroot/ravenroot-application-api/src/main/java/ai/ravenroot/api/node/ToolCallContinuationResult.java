package ai.ravenroot.api.node;

import ai.ravenroot.api.execution.NodeResult;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Immediate terminal outcome of the redeemed effect plus its possibly suspended continuation.
 *
 * @param nodeResult eventual result of the resumed node invocation
 * @param effectSucceeded whether the exact redeemed effect completed successfully
 */
public record ToolCallContinuationResult(CompletionStage<NodeResult> nodeResult,
                                         boolean effectSucceeded) {
    /** Validates the continuation result. */
    public ToolCallContinuationResult {
        Objects.requireNonNull(nodeResult, "nodeResult");
    }
}
