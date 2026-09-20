package ai.ravenroot.api.runner;

import ai.ravenroot.api.persistence.ExecutionKey;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable process-scoped workspace ownership state. This is a logical sequencing barrier, not a
 * second agent lease. Each mutation must be committed using the workspace revision in the same
 * transaction as the job change. Job identities and historical results remain in the job store.
 * This value contains no filesystem path, credential, or authority derived from graph content.
 */
public final class ProcessWorkspace {
    private final ExecutionKey execution;
    private final UUID workspaceId;
    private final String runnerId;
    private final long revision;
    private final RunnerJob currentJob;

    private ProcessWorkspace(ExecutionKey execution, UUID workspaceId, String runnerId, long revision,
                             RunnerJob currentJob) {
        this.execution = execution;
        this.workspaceId = workspaceId;
        this.runnerId = runnerId;
        this.revision = revision;
        this.currentJob = currentJob;
    }

    /**
     * Creates a new process workspace pinned to its designated runner. The caller must allocate a
     * fresh workspace ID and enforce a unique tenant/process key in the authoritative store.
     * @param execution tenant and process scope
     * @param workspaceId opaque, independently generated workspace identity
     * @param runnerId designated runner; migration requires a separate verified transfer
     * @return empty workspace ownership state
     */
    public static ProcessWorkspace create(ExecutionKey execution, UUID workspaceId, String runnerId) {
        return new ProcessWorkspace(Objects.requireNonNull(execution, "execution"),
                Objects.requireNonNull(workspaceId, "workspaceId"), RunnerPolicy.identifier(runnerId), 1, null);
    }

    /**
     * Admits the next queued job only after the preceding job's quiescence barrier. A later
     * traversal of the same process reuses this workspace; another process or runner is rejected.
     * The adapter must also enforce globally unique tenant/job and tenant/attempt acceptance keys.
     * @param job newly accepted queued job
     * @return workspace reserving all mutation ownership for that job
     */
    public ProcessWorkspace admit(RunnerJob job) {
        Objects.requireNonNull(job, "job");
        if (!execution.equals(job.identity().execution()) || !runnerId.equals(job.runner().runnerId())) {
            throw new IllegalArgumentException("runner workspace scope or placement mismatch");
        }
        if (job.state() != RunnerJob.State.QUEUED || job.revision() != 1) {
            throw new IllegalArgumentException("workspace admission requires a new queued job");
        }
        if (currentJob != null && (currentJob.retainsWorkspace()
                || currentJob.identity().runnerJobId().equals(job.identity().runnerJobId())
                || currentJob.identity().attemptId().equals(job.identity().attemptId()))) {
            throw new IllegalStateException("process workspace has not passed the ownership barrier");
        }
        return replace(job);
    }

    /**
     * Claims the current job's execution lease.
     * @param runner authenticated runner
     * @param now store time
     * @param ttl requested lease
     * @return updated workspace and job state
     */
    public ProcessWorkspace claim(String runner, Instant now, Duration ttl) {
        return replace(requireJob().claim(runner, now, ttl));
    }

    /**
     * Renews the current job's liveness lease.
     * @param runner authenticated runner
     * @param fence current job fence
     * @param now store time
     * @param ttl requested lease
     * @return updated workspace and job state
     */
    public ProcessWorkspace heartbeat(String runner, long fence, Instant now, Duration ttl) {
        return replace(requireJob().heartbeat(runner, fence, now, ttl));
    }

    /**
     * Requests cancellation without releasing dispatched work.
     * @param now store time
     * @return updated workspace and job state
     */
    public ProcessWorkspace cancel(Instant now) { return replace(requireJob().cancel(now)); }

    /**
     * Applies lease/deadline recovery without releasing unknown work.
     * @param now store time
     * @return updated workspace and job state
     */
    public ProcessWorkspace reconcileLiveness(Instant now) {
        return replace(requireJob().reconcileLiveness(now));
    }

    /**
     * Issues the current job a report-only reconciliation fence.
     * @param runner authenticated designated runner
     * @param now store time
     * @param ttl reconciliation lease
     * @return updated workspace and job state
     */
    public ProcessWorkspace beginReconciliation(String runner, Instant now, Duration ttl) {
        return replace(requireJob().beginReconciliation(runner, now, ttl));
    }

    /**
     * Passes the ownership barrier only through a fenced terminal report.
     * @param runner authenticated runner
     * @param fence current fence
     * @param result terminal report after stopping descendants and flushing durable writes
     * @param now store time
     * @return workspace permitting its next sequential job
     */
    public ProcessWorkspace complete(String runner, long fence, RunnerResult result, Instant now) {
        return replace(requireJob().complete(runner, fence, result, now));
    }

    private RunnerJob requireJob() {
        if (currentJob == null) throw new IllegalStateException("workspace has no job");
        return currentJob;
    }

    private ProcessWorkspace replace(RunnerJob job) {
        return currentJob == job ? this : new ProcessWorkspace(execution, workspaceId, runnerId,
                Math.incrementExact(revision), job);
    }

    /**
     * Returns tenant/process identity owning this workspace.
     * @return tenant/process identity owning this workspace
     */
    public ExecutionKey execution() { return execution; }
    /**
     * Returns opaque workspace identity stable across traversals and specialist handoffs.
     * @return opaque workspace identity stable across traversals and specialist handoffs
     */
    public UUID workspaceId() { return workspaceId; }
    /**
     * Returns designated runner placement.
     * @return designated runner placement
     */
    public String runnerId() { return runnerId; }
    /**
     * Returns revision required by the adapter's atomic compare-and-set.
     * @return revision required by the adapter's atomic compare-and-set
     */
    public long revision() { return revision; }
    /**
     * Returns current or last terminal job, or null before first admission.
     * @return current or last terminal job, or null before first admission
     */
    public RunnerJob currentJob() { return currentJob; }
}
