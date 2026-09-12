package ai.ravenroot.api.node;

import java.util.concurrent.CompletionStage;

/** Trusted, package-owned decoder and executor for one versioned durable tool-call continuation. */
public interface ToolCallContinuationAction {
    /**
     * Performs strict no-effect validation before the approval grant is consumed.
     * @param input exact server-minted continuation input
     */
    void validate(ToolCallContinuationInput input);

    /**
     * Resumes a checkpoint after core validates its exact stored decision. Approved calls are
     * consumed before invocation; denied, expired, and cancelled calls resume as no-effect results.
     * @param input exact server-minted continuation input
     * @return immediate effect outcome and eventual node continuation
     */
    CompletionStage<ToolCallContinuationResult> resume(ToolCallContinuationInput input);
}
