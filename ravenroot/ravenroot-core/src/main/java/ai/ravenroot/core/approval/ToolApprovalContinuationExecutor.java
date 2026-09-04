package ai.ravenroot.core.approval;

import java.util.concurrent.CompletionStage;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.DurableToolApproval;

/**
 * Trusted decoder/effect boundary for one bounded, versioned durable tool continuation.
 *
 * <p>A host must opt in to each supported node/version pair. Dynamic or otherwise unsupported
 * bundle continuations remain durably waiting through {@link #NONE}; they are never interpreted by
 * generic recovery code.</p>
 */
public interface ToolApprovalContinuationExecutor {
    /** Whether this process can decode and execute the exact stored continuation version. */
    boolean supports(DurableToolApproval approval);

    /** Executes or resumes from the supplied immutable stored input. */
    CompletionStage<Boolean> execute(ToolApprovalContinuation continuation,
                                     PendingWork.HandlerTrigger claim);

    /** Releases execution resources after recovery has acknowledged the trigger claim. */
    default void afterAcknowledged(PendingWork.HandlerTrigger claim) { }

    /** Fail-closed compatibility implementation for hosts with no continuation executor. */
    ToolApprovalContinuationExecutor NONE = new ToolApprovalContinuationExecutor() {
        @Override public boolean supports(DurableToolApproval approval) { return false; }
        @Override public CompletionStage<Boolean> execute(ToolApprovalContinuation continuation,
                                                          PendingWork.HandlerTrigger claim) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("tool approval continuation unavailable"));
        }
    };
}
