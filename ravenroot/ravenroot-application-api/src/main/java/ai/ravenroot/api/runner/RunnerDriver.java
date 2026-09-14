package ai.ravenroot.api.runner;

import java.util.concurrent.CompletionStage;

/**
 * Runner protocol v1 execution SPI. A driver is an operator-approved enforcement boundary, not a
 * graph extension. Dispatch requires an accepted live claim; reconciliation must never execute.
 */
public interface RunnerDriver extends AutoCloseable {
    /**
     * Returns enforceable capabilities, not a grant of control-plane approval.
     * @return exact tenant-scoped runner advertisement
     */
    RunnerRegistration registration();
    /**
     * Executes once under the accepted live execution fence and seals a quiescent result.
     * @param assignment authenticated claimed job; never an unknown or report-only job
     * @return sealed result, or failure requiring reconciliation without redispatch
     */
    CompletionStage<RunnerResult> execute(RunnerAssignment assignment);
    /**
     * Stops the complete process tree; absence of a report is not evidence of quiescence.
     * @param assignment exact accepted job to stop
     * @return acknowledgement after stopping, or failure if quiescence is unproven
     */
    CompletionStage<Void> cancel(RunnerAssignment assignment);
    /**
     * Retrieves sealed evidence, or fails unknown. Must not repeat work to manufacture a result.
     * @param assignment authenticated job with current report-only authority
     * @return existing evidence sealed after proving quiescence
     */
    CompletionStage<RunnerResult> reconcile(RunnerAssignment assignment);
    /**
     * Removes only the owned workspace after authenticated terminal-process retention approval.
     * @param release bounded control-plane cleanup proof
     * @return cleanup completion, or unsupported/failed cleanup without deleting other workspaces
     */
    default CompletionStage<Void> release(RunnerWorkspaceRelease release) {
        return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("runner cleanup is not supported"));
    }
    /** Releases driver resources; callers must stop active assignments before closing. */
    @Override void close();
}
