package ai.ravenroot.api.node;

import java.util.concurrent.CompletionStage;

/** Trusted, package-owned decoder and executor for one versioned durable tool-call continuation. */
public interface ToolCallContinuationAction {
    /** Strict no-effect validation performed before the approval grant is consumed. */
    void validate(ToolCallContinuationInput input);

    /**
     * Resumes a checkpoint after core validates its exact stored decision. Approved calls are
     * consumed before invocation; denied, expired, and cancelled calls resume as no-effect results.
     */
    CompletionStage<ToolCallContinuationResult> resume(ToolCallContinuationInput input);
}
