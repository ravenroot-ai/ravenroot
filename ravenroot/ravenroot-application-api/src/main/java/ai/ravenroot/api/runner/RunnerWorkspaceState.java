package ai.ravenroot.api.runner;

import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.OpaquePayload;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded process workspace state committed atomically with its graph aggregate. Job history remains
 * independent of journal compaction. One active job is a sequencing barrier, never a second lease.
 * @param execution owning tenant and process
 * @param workspaceId stable opaque workspace identity
 * @param runnerId immutable designated runner placement
 * @param jobs bounded insertion-ordered history keyed by runner job identity
 */
public record RunnerWorkspaceState(ExecutionKey execution, UUID workspaceId, String runnerId,
                                   Map<UUID, Entry> jobs) {
    /** Maximum retained job count per process workspace. */
    public static final int MAX_JOBS = 256;

    /**
     * Pinned continuation travels only between trusted control-plane components.
     * @param job accepted immutable job state
     * @param continuation bounded graph continuation, never sent to the runner
     * @param continuationUncertain whether partial successor delivery prevents automatic replay
     */
    public record Entry(RunnerJob job, OpaquePayload continuation, boolean continuationUncertain) {
        /**
         * Creates a newly parked continuation without an uncertain-delivery marker.
         * @param job accepted job state
         * @param continuation trusted graph checkpoint
         */
        public Entry(RunnerJob job, OpaquePayload continuation) { this(job, continuation, false); }
        /** Validates the bounded trusted checkpoint. */
        public Entry {
            Objects.requireNonNull(job); Objects.requireNonNull(continuation);
            if (continuation.size() > 4_194_304) throw new IllegalArgumentException("runner continuation too large");
        }
    }

    /** Validates unique attempt identities, placement and exclusive workspace ownership. */
    public RunnerWorkspaceState {
        Objects.requireNonNull(execution); Objects.requireNonNull(workspaceId);
        runnerId = RunnerPolicy.identifier(runnerId);
        jobs = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(jobs));
        if (jobs.size() > MAX_JOBS) throw new IllegalArgumentException("process runner job quota exceeded");
        var attempts = new java.util.HashSet<UUID>();
        int active = 0;
        for (var item : jobs.entrySet()) {
            RunnerJob job = item.getValue().job();
            if (!item.getKey().equals(job.identity().runnerJobId())
                    || !execution.equals(job.identity().execution()) || !runnerId.equals(job.runner().runnerId())
                    || !attempts.add(job.identity().attemptId())) {
                throw new IllegalArgumentException("invalid workspace identity chain");
            }
            if (job.retainsWorkspace()) active++;
        }
        if (active > 1) throw new IllegalArgumentException("overlapping workspace jobs");
    }

    /**
     * Folds only validated operations, using the adapter's authoritative clock and post-fold graph.
     * @param key exact owning process scope
     * @param current prior workspace state, or null before first admission
     * @param operation accepted control-plane operation
     * @param graph post-transition graph aggregate committed in the same transaction
     * @param now authoritative store time
     * @return validated next workspace state to commit atomically
     */
    public static RunnerWorkspaceState apply(ExecutionKey key, RunnerWorkspaceState current,
                                             RunnerJobOperation operation, ProcessInstance graph, Instant now) {
        Objects.requireNonNull(operation); Objects.requireNonNull(operation.jobId());
        if (!key.processInstanceId().equals(graph.processInstanceId())
                || (current != null && !key.equals(current.execution()))) {
            throw new IllegalArgumentException("runner workspace scope mismatch");
        }
        if (operation instanceof RunnerJobOperation.Submit submit) {
            if (!key.equals(submit.identity().execution())) throw new IllegalArgumentException("runner job scope mismatch");
            var traversal = graph.traversals().get(submit.identity().traversalId());
            var invocation = traversal == null ? null : traversal.invocations().get(submit.identity().invocationId());
            var attempt = invocation == null ? null : invocation.attempts().stream()
                    .filter(value -> value.attemptId().equals(submit.identity().attemptId())).findFirst().orElse(null);
            if (attempt == null || attempt.status() != NodeAttemptStatus.WAITING
                    || !invocation.command().name().equals(submit.command())) {
                throw new IllegalArgumentException("runner admission requires the exact parked graph attempt");
            }
            if (current == null) current = new RunnerWorkspaceState(key, submit.workspaceId(),
                    submit.runner().runnerId(), Map.of());
            if (!current.workspaceId().equals(submit.workspaceId())
                    || !current.runnerId().equals(submit.runner().runnerId())) {
                throw new IllegalArgumentException("workspace placement is immutable");
            }
            if (current.jobs().containsKey(submit.jobId())) {
                throw new IllegalStateException("runner attempt already admitted; load its accepted job");
            }
            if (current.jobs().values().stream().anyMatch(value -> value.job().retainsWorkspace() || value.continuationUncertain())) {
                throw new IllegalStateException("workspace quiescence barrier has not passed");
            }
            RunnerJob job = RunnerJob.accept(submit.identity(), submit.definition(), submit.command(),
                    submit.deployment(), submit.runner(), submit.input(), now, submit.deadline());
            return current.replace(submit.jobId(), new Entry(job, submit.continuation()));
        }
        if (current == null || !current.jobs().containsKey(operation.jobId())) {
            throw new IllegalArgumentException("runner job not found");
        }
        Entry entry = current.jobs().get(operation.jobId());
        RunnerJob job = entry.job();
        if (operation instanceof RunnerJobOperation.ContinuationUncertain) {
            if (!job.state().terminal()) throw new IllegalStateException("only a terminal runner result can have uncertain graph delivery");
            return entry.continuationUncertain() ? current : current.replace(operation.jobId(), new Entry(job, entry.continuation(), true));
        }
        RunnerJob next = switch (operation) {
            case RunnerJobOperation.Claim value -> job.claim(value.runnerId(), now, value.ttl());
            case RunnerJobOperation.Heartbeat value -> job.heartbeat(value.runnerId(), value.fence(), now, value.ttl());
            case RunnerJobOperation.Cancel ignored -> job.cancel(now);
            case RunnerJobOperation.Reconcile ignored -> job.reconcileLiveness(now);
            case RunnerJobOperation.ReconcileReport value -> job.beginReconciliation(value.runnerId(), now, value.ttl());
            case RunnerJobOperation.Complete value -> job.complete(value.runnerId(), value.fence(), value.result(), now);
            case RunnerJobOperation.Submit ignored -> throw new IllegalStateException("admission already handled");
            case RunnerJobOperation.ContinuationUncertain ignored -> throw new IllegalStateException("uncertainty already handled");
        };
        return next == job ? current : current.replace(operation.jobId(), new Entry(next, entry.continuation(), entry.continuationUncertain()));
    }

    private RunnerWorkspaceState replace(UUID id, Entry entry) {
        var next = new LinkedHashMap<>(jobs);
        next.put(id, entry);
        return new RunnerWorkspaceState(execution, workspaceId, runnerId, next);
    }
}
