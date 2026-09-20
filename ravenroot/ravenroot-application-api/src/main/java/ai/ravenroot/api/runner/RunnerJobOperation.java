package ai.ravenroot.api.runner;

import ai.ravenroot.api.persistence.OpaquePayload;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Store-clock operations committed with graph transitions and audit in an execution batch. */
public sealed interface RunnerJobOperation {
    /**
     * Identifies the affected accepted job.
     * @return exact runner job identity
     */
    UUID jobId();

    /**
     * Operator stop of one graph resource, independent of traversal position.
     * @param jobId accepted job anchoring the resource's process/audit identity
     * @param workspaceNodeId exact graph-declared resource to stop
     */
    record WorkspaceStop(UUID jobId, String workspaceNodeId) implements RunnerJobOperation { }
    /**
     * Authenticated pinned worker's acknowledgement that the resource runtime is stopped.
     * @param jobId accepted resource ownership anchor
     * @param workspaceNodeId exact graph resource
     * @param workspaceId expected physical filesystem identity
     * @param runnerId authenticated pinned worker
     */
    record WorkspaceStopped(UUID jobId, String workspaceNodeId, UUID workspaceId, String runnerId) implements RunnerJobOperation { }
    /**
     * Reserve exclusive cleanup before issuing physical deletion authority.
     * @param jobId accepted resource ownership anchor
     * @param workspaceNodeId exact graph resource
     * @param workspaceId expected physical filesystem identity
     * @param runnerId authenticated pinned cleanup worker
     */
    record WorkspaceRelease(UUID jobId, String workspaceNodeId, UUID workspaceId, String runnerId) implements RunnerJobOperation { }
    /**
     * Pinned worker attests physical cleanup; only this acknowledgement frees retained capacity.
     * @param jobId accepted resource ownership anchor
     * @param workspaceNodeId exact graph resource
     * @param workspaceId physically released filesystem identity
     * @param runnerId authenticated pinned cleanup worker
     */
    record WorkspaceReleased(UUID jobId, String workspaceNodeId, UUID workspaceId, String runnerId) implements RunnerJobOperation { }
    /**
     * Scheduler-only placement change, legal only before the first physical dispatch.
     * @param jobId undispatched opening job
     * @param workspaceNodeId unmaterialized graph resource
     * @param runner compatible approved replacement worker
     * @param workerSession exact live incarnation owning the advertised placement
     */
    record WorkspacePlace(UUID jobId, String workspaceNodeId, RunnerRegistration runner, UUID workerSession) implements RunnerJobOperation { }

    /**
     * Admission input resolved by the trusted control plane, never directly from a runner.
     * @param identity complete durable graph-to-job identity chain
     * @param definition approved immutable definition version
     * @param command declared application command
     * @param deployment deployment authority ceiling
     * @param runner approved designated runner advertisement
     * @param input bounded invocation payload
     * @param deadline enclosing execution deadline
     * @param workspaceId process-scoped workspace identity
     * @param continuation trusted pinned graph continuation, never sent to the runner
     * @param workspace pinned graph resource, null only for legacy stored admissions
     * @param lifecycleCommand resource operation, null for an Agent invocation
     */
    record Submit(RunnerJobIdentity identity, AgentDefinition definition, String command,
                  RunnerPolicy deployment, RunnerRegistration runner, OpaquePayload input,
                  Instant deadline, UUID workspaceId, OpaquePayload continuation,
                  WorkspaceResource workspace, String lifecycleCommand) implements RunnerJobOperation {
        /**
         * Reconstructs legacy admission without introducing a generic graph-node alias.
         * @param identity exact process/traversal/invocation/attempt/job chain
         * @param definition approved immutable Agent
         * @param command declared requested operation
         * @param deployment enclosing authority ceiling
         * @param runner approved designated worker
         * @param input bounded invocation context
         * @param deadline enclosing execution deadline
         * @param workspaceId legacy physical ownership identity
         * @param continuation trusted pinned graph checkpoint
         */
        public Submit(RunnerJobIdentity identity, AgentDefinition definition, String command,
                      RunnerPolicy deployment, RunnerRegistration runner, OpaquePayload input,
                      Instant deadline, UUID workspaceId, OpaquePayload continuation) {
            this(identity, definition, command, deployment, runner, input, deadline, workspaceId, continuation, null, null);
        }
        /** Validates complete bounded admission input. */
        public Submit {
            Objects.requireNonNull(identity); Objects.requireNonNull(definition);
            Objects.requireNonNull(command); Objects.requireNonNull(deployment);
            Objects.requireNonNull(runner); Objects.requireNonNull(input);
            Objects.requireNonNull(deadline); Objects.requireNonNull(workspaceId);
            Objects.requireNonNull(continuation);
            if (workspace != null && (!workspaceId.equals(workspace.workspaceId())
                    || !runner.runnerId().equals(workspace.runnerId())
                    || !identity.execution().tenantId().equals(workspace.profile().reference().tenantId()))) {
                throw new IllegalArgumentException("workspace admission identity mismatch");
            }
            if (lifecycleCommand != null && (workspace == null || !lifecycleCommand.equals(command)
                    || !java.util.Set.of("open", "inspect", "checkpoint", "close", "abort").contains(command))) {
                throw new IllegalArgumentException("invalid workspace lifecycle operation");
            }
            if (continuation.size() > 4_194_304) throw new IllegalArgumentException("runner continuation too large");
        }
        @Override public UUID jobId() { return identity.runnerJobId(); }
    }

    /**
     * The sole execution permit.
     * @param jobId accepted queued job
     * @param runnerId authenticated designated runner
     * @param ttl bounded requested lease duration
     * @param workerSession exact live supervisor incarnation, null only for legacy jobs
     */
    record Claim(UUID jobId, String runnerId, Duration ttl, UUID workerSession) implements RunnerJobOperation {
        /**
         * Claims a legacy job which predates live incarnation advertisements.
         * @param jobId accepted legacy queued job
         * @param runnerId authenticated designated worker
         * @param ttl requested execution lease duration
         */
        public Claim(UUID jobId, String runnerId, Duration ttl) { this(jobId, runnerId, ttl, null); }
    }
    /**
     * A live fenced heartbeat, evaluated against store time.
     * @param jobId accepted job
     * @param runnerId authenticated designated runner
     * @param fence current live fence
     * @param ttl bounded requested lease extension
     * @param workerSession exact live supervisor incarnation, null only for legacy jobs
     * @param kubernetes bounded physical observation, or null when unavailable
     */
    record Heartbeat(UUID jobId, String runnerId, long fence, Duration ttl, UUID workerSession, KubernetesWorkload kubernetes) implements RunnerJobOperation {
        /**
         * Renews a claim without adding a native physical observation.
         * @param jobId accepted job
         * @param runnerId pinned worker
         * @param fence live fence
         * @param ttl lease extension
         * @param workerSession live worker incarnation
         */
        public Heartbeat(UUID jobId, String runnerId, long fence, Duration ttl, UUID workerSession) {
            this(jobId, runnerId, fence, ttl, workerSession, null);
        }
        /**
         * Renews a legacy claim without inventing an incarnation identity.
         * @param jobId accepted legacy job
         * @param runnerId authenticated designated worker
         * @param fence current execution fence
         * @param ttl requested lease extension
         */
        public Heartbeat(UUID jobId, String runnerId, long fence, Duration ttl) { this(jobId, runnerId, fence, ttl, null); }
    }
    /**
     * Cancellation does not imply that dispatched work has stopped.
     * @param jobId accepted job to stop
     */
    record Cancel(UUID jobId) implements RunnerJobOperation { }
    /**
     * Store-side lease/deadline reconciliation; never a retry.
     * @param jobId accepted job to reconcile using store time
     */
    record Reconcile(UUID jobId) implements RunnerJobOperation { }
    /**
     * Grants report permission, never execution permission, after an unknown result.
     * @param jobId accepted unknown job
     * @param runnerId authenticated designated runner
     * @param ttl bounded report-only lease duration
     */
    record ReconcileReport(UUID jobId, String runnerId, Duration ttl) implements RunnerJobOperation { }
    /**
     * Fenced, idempotent quiescence report.
     * @param jobId accepted job
     * @param runnerId authenticated designated runner
     * @param fence current report fence
     * @param result sealed quiescent terminal evidence
     */
    record Complete(UUID jobId, String runnerId, long fence, RunnerResult result) implements RunnerJobOperation { }
    /**
     * Trusted control-plane fence: uncertain successor delivery must never be replayed automatically.
     * @param jobId terminal job whose graph continuation was partially dispatched
     */
    record ContinuationUncertain(UUID jobId) implements RunnerJobOperation { }

    /** Explicit operator disposition; none of these modes grants runner execution authority. */
    enum ContinuationResolution {
        /** Deliver the accepted result only when no successor invocation was recorded. */
        RESUME,
        /** Acknowledge the complete successor multiset observed in the pinned graph. */
        ACKNOWLEDGE,
        /** Fail the unfinished traversal, preserving already observed effects and never dispatching missing work. */
        ABANDON
    }

    /** Trusted graph-derived resolution, committed with the operator audit and graph transitions.
     * @param jobId terminal runner job
     * @param resolution explicit operator disposition
     * @param expectedSuccessors exact target multiplicities derived from the immutable graph pin
     */
    record ResolveContinuation(UUID jobId, ContinuationResolution resolution,
                               java.util.Map<String, Long> expectedSuccessors) implements RunnerJobOperation {
        /** Freezes the bounded graph-derived target multiset. */
        public ResolveContinuation {
            Objects.requireNonNull(jobId); Objects.requireNonNull(resolution);
            expectedSuccessors = java.util.Map.copyOf(expectedSuccessors);
            if (expectedSuccessors.size() > 1024 || expectedSuccessors.values().stream().anyMatch(count -> count < 1)) {
                throw new IllegalArgumentException("invalid runner successor multiset");
            }
        }
    }
}
