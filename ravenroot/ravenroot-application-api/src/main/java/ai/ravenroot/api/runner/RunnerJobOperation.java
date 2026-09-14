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
     */
    record Submit(RunnerJobIdentity identity, AgentDefinition definition, String command,
                  RunnerPolicy deployment, RunnerRegistration runner, OpaquePayload input,
                  Instant deadline, UUID workspaceId, OpaquePayload continuation) implements RunnerJobOperation {
        /** Validates complete bounded admission input. */
        public Submit {
            Objects.requireNonNull(identity); Objects.requireNonNull(definition);
            Objects.requireNonNull(command); Objects.requireNonNull(deployment);
            Objects.requireNonNull(runner); Objects.requireNonNull(input);
            Objects.requireNonNull(deadline); Objects.requireNonNull(workspaceId);
            Objects.requireNonNull(continuation);
            if (continuation.size() > 4_194_304) throw new IllegalArgumentException("runner continuation too large");
        }
        @Override public UUID jobId() { return identity.runnerJobId(); }
    }

    /**
     * The sole execution permit.
     * @param jobId accepted queued job
     * @param runnerId authenticated designated runner
     * @param ttl bounded requested lease duration
     */
    record Claim(UUID jobId, String runnerId, Duration ttl) implements RunnerJobOperation { }
    /**
     * A live fenced heartbeat, evaluated against store time.
     * @param jobId accepted job
     * @param runnerId authenticated designated runner
     * @param fence current live fence
     * @param ttl bounded requested lease extension
     */
    record Heartbeat(UUID jobId, String runnerId, long fence, Duration ttl) implements RunnerJobOperation { }
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
}
