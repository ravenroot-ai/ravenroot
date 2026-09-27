package ai.ravenroot.api.runner;

import java.util.concurrent.CompletionStage;

/**
 * Runner protocol v1 execution SPI. A driver is an operator-approved enforcement boundary, not a
 * graph extension. Dispatch requires an accepted live claim; reconciliation must never execute.
 */
public interface RunnerDriver extends AutoCloseable {
    /**
     * Fails closed when the selected substrate cannot currently admit attested work.
     * Implementations may use bounded read-only observations; success grants no job authority.
     */
    default void verifyAvailability() { }
    /**
     * Returns enforceable capabilities, not a grant of control-plane approval.
     * @return exact tenant-scoped runner advertisement
     */
    RunnerRegistration registration();
    /**
     * Installed immutable runtime profile names; an empty set cannot receive explicit Workspace work.
     * @return operator-installed profile identifiers, not image names or graph-provided grants
     */
    default java.util.Set<String> runtimeProfiles() { return java.util.Set.of(); }
    /**
     * Adds an observed physical identity to a heartbeat without granting execution authority.
     * @param assignment current accepted fenced assignment
     * @return the same logical assignment with bounded physical observations
     */
    default RunnerAssignment observe(RunnerAssignment assignment) { return assignment; }
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
     * Stops a resource under its sticky control-plane stop request, including an idle runtime.
     * @param assignment accepted resource ownership carrying a sticky stop request
     * @return physical stop acknowledgement, or failure preserving unknown ownership
     */
    default CompletionStage<Void> stopWorkspace(RunnerAssignment assignment) {
        return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Workspace stop is not supported"));
    }
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
