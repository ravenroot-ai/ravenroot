package ai.ravenroot.api.node;

import ai.ravenroot.api.execution.NodeResult;

import java.util.Objects;

/** Node result plus the terminal outcome of the single redeemed effect. */
public record ToolCallContinuationResult(NodeResult nodeResult, boolean effectSucceeded) {
    public ToolCallContinuationResult {
        Objects.requireNonNull(nodeResult, "nodeResult");
    }
}
