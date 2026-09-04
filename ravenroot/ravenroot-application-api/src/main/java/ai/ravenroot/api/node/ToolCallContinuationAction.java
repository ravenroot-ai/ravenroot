package ai.ravenroot.api.node;

import java.util.concurrent.CompletionStage;

/** Trusted, package-owned decoder and executor for one versioned durable tool-call continuation. */
public interface ToolCallContinuationAction {
    /** Strict no-effect validation performed before the approval grant is consumed. */
    void validate(ToolCallContinuationInput input);

    /** Resumes a checkpoint only after core has validated and consumed its exact approval grant. */
    CompletionStage<ToolCallContinuationResult> resume(ToolCallContinuationInput input);
}
