package ai.ravenroot.api.runner;

import ai.ravenroot.api.persistence.OpaquePayload;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Pure version-one job state machine. An adapter must atomically persist each returned state and its
 * audit record using a revision expectation. Times are supplied by the authoritative store, never
 * by a runner. No transition executes a job, retries an effect, or releases a workspace implicitly.
 *
 * <p>Lease expiry fences reports and parks the job as UNKNOWN. It does not demonstrate that the
 * runner or its children stopped. Reconciliation issues a new report-only fence to the same runner;
 * it never issues a second execution permit. Workspace ownership survives until a terminal state.
 */
public final class RunnerJob {
    /** Durable lifecycle states. */
    public enum State {
        /** Accepted but never dispatched; eligible for its sole execution claim. */
        QUEUED,
        /** A designated runner holds the current execution fence. */
        CLAIMED,
        /** Stop requested, with quiescence still unproven. */
        CANCELLING,
        /** Dispatch effects are unknown; execution must not be repeated. */
        UNKNOWN,
        /** A fresh report-only fence permits collecting existing quiescence evidence. */
        RECONCILING,
        /** Terminal result accepted after workspace quiescence. */
        COMPLETED,
        /** Unstarted cancellation or fenced quiescence after cancellation. */
        CANCELLED,
        /** Unstarted expiry or fenced quiescence after the deadline. */
        DEADLINE_EXCEEDED;

        /**
         * Returns whether the workspace ownership barrier has been passed.
         * @return whether the workspace ownership barrier has been passed
         */
        public boolean terminal() {
            return this == COMPLETED || this == CANCELLED || this == DEADLINE_EXCEEDED;
        }
    }

    /** Sticky control-plane stop request; deadlines take precedence over cancellation. */
    public enum StopReason {
        /** No control-plane stop request. */
        NONE,
        /** Authenticated cancellation request. */
        CANCEL,
        /** Effective deadline elapsed; takes precedence over cancellation. */
        DEADLINE
    }

    /** Maximum liveness lease; a shorter effective job deadline always wins. */
    public static final Duration MAX_LEASE = Duration.ofMinutes(5);

    private final RunnerJobIdentity identity;
    private final AgentDefinition definition;
    private final AgentCommand command;
    private final RunnerRegistration runner;
    private final RunnerPolicy authority;
    private final OpaquePayload input;
    private final Instant deadline;
    private final Instant updatedAt;
    private final long revision;
    private final long fence;
    private final State state;
    private final StopReason stopReason;
    private final Instant leaseUntil;
    private final RunnerResult result;

    private RunnerJob(RunnerJobIdentity identity, AgentDefinition definition, AgentCommand command,
                      RunnerRegistration runner, RunnerPolicy authority, OpaquePayload input,
                      Instant deadline, Instant updatedAt, long revision, long fence, State state,
                      StopReason stopReason, Instant leaseUntil, RunnerResult result) {
        this.identity = identity;
        this.definition = definition;
        this.command = command;
        this.runner = runner;
        this.authority = authority;
        this.input = input;
        this.deadline = deadline;
        this.updatedAt = updatedAt;
        this.revision = revision;
        this.fence = fence;
        this.state = state;
        this.stopReason = stopReason;
        this.leaseUntil = leaseUntil;
        this.result = result;
    }

    /** Versioned storage decoder; not an admission or runner-report entry point. */
    static RunnerJob restore(RunnerJobIdentity identity, AgentDefinition definition, String command,
                             RunnerRegistration runner, RunnerPolicy authority, OpaquePayload input,
                             Instant deadline, Instant updatedAt, long revision, long fence, State state,
                             StopReason stopReason, Instant leaseUntil, RunnerResult result) {
        if (revision < 1 || fence < 0 || !definition.commands().containsKey(command)
                || !identity.execution().tenantId().equals(definition.reference().tenantId())
                || !identity.execution().tenantId().equals(runner.tenantId())
                || input.size() > authority.limits().payloadBytes()
                || (state == State.QUEUED && (fence != 0 || leaseUntil != null || result != null))
                || (!state.terminal() && state != State.QUEUED && fence < 1)
                || ((state == State.CLAIMED || state == State.CANCELLING || state == State.RECONCILING)
                    != (leaseUntil != null))
                || (state == State.COMPLETED && result == null)
                || (!state.terminal() && result != null)) {
            throw new IllegalArgumentException("invalid stored runner job");
        }
        RunnerPolicy attenuated = RunnerPolicy.effective(authority, definition.policy(),
                definition.commands().get(command), runner.capabilities());
        if (!attenuated.equals(authority)) throw new IllegalArgumentException("stored authority exceeds policy");
        var job = new RunnerJob(identity, definition, definition.commands().get(command), runner,
                authority, input, deadline, updatedAt, revision, fence, state, stopReason, leaseUntil, result);
        if (result != null) job.validateResult(result);
        return job;
    }

    /**
     * Resolves an already-approved definition and designated runner into immutable execution input.
     * The caller must authenticate the scope, resolve approval and reserve the process workspace in
     * the same acceptance transaction that records the graph continuation and this job.
     * @param identity complete job identity
     * @param definition exact approved definition version
     * @param commandName graph-selected command
     * @param deployment deployment authority ceiling
     * @param runner approved designated runner advertisement
     * @param input bounded invocation context
     * @param now authoritative acceptance time
     * @param executionDeadline enclosing traversal deadline
     * @return queued job whose policies and definition no longer depend on mutable catalogs
     */
    public static RunnerJob accept(RunnerJobIdentity identity, AgentDefinition definition, String commandName,
                                   RunnerPolicy deployment, RunnerRegistration runner, OpaquePayload input,
                                   Instant now, Instant executionDeadline) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(runner, "runner");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(executionDeadline, "executionDeadline");
        if (!identity.execution().tenantId().equals(definition.reference().tenantId())
                || !identity.execution().tenantId().equals(runner.tenantId())) {
            throw new IllegalArgumentException("runner job scope mismatch");
        }
        AgentCommand command = definition.commands().get(commandName);
        if (command == null || !runner.labels().containsAll(definition.runnerRequirements())) {
            throw new IllegalArgumentException("runner or command does not match the definition");
        }
        RunnerPolicy authority = RunnerPolicy.effective(deployment, definition.policy(), command,
                runner.capabilities());
        if (input.size() > authority.limits().payloadBytes() || input.contentType().length() > 128) {
            throw new IllegalArgumentException("runner input exceeds effective payload limit");
        }
        Instant deadline = now.plus(authority.limits().wallTime());
        if (executionDeadline.isBefore(deadline)) deadline = executionDeadline;
        if (!deadline.isAfter(now)) throw new IllegalArgumentException("runner deadline has elapsed");
        return new RunnerJob(identity, definition, command, runner, authority, input, deadline, now,
                1, 0, State.QUEUED, StopReason.NONE, null, null);
    }

    /**
     * Issues the only execution permit for this job. UNKNOWN jobs cannot be claimed again.
     * @param runnerId authenticated designated runner
     * @param now store time
     * @param ttl requested liveness lease
     * @return claimed job or an unstarted deadline terminal
     */
    public RunnerJob claim(String runnerId, Instant now, Duration ttl) {
        checkRunner(runnerId);
        checkTime(now);
        checkTtl(ttl);
        if (state != State.QUEUED) throw new IllegalStateException("job is not queued");
        if (!now.isBefore(deadline)) {
            return next(now, State.DEADLINE_EXCEEDED, StopReason.DEADLINE, fence, null, null);
        }
        return next(now, State.CLAIMED, StopReason.NONE, Math.incrementExact(fence),
                leaseEnd(now, ttl, true), null);
    }

    /**
     * Renews a live execution or reconciliation lease. An expired lease can never be revived.
     * @param runnerId authenticated runner
     * @param token current fencing token
     * @param now store time
     * @param ttl requested lease extension
     * @return renewed job, including a deadline stop request when necessary
     */
    public RunnerJob heartbeat(String runnerId, long token, Instant now, Duration ttl) {
        checkReport(runnerId, token, now);
        checkTtl(ttl);
        if (state != State.CLAIMED && state != State.CANCELLING && state != State.RECONCILING) {
            throw new IllegalStateException("job has no live runner lease");
        }
        StopReason reason = reasonAt(now);
        State nextState = reason != StopReason.NONE && state != State.RECONCILING ? State.CANCELLING : state;
        return next(now, nextState, reason, fence, leaseEnd(now, ttl, reason == StopReason.NONE), null);
    }

    /**
     * Requests stop. An unstarted job can terminate immediately; every dispatched job retains its
     * workspace until quiescence is acknowledged, including when disconnected.
     * @param now store time
     * @return cancellation-requested or already-terminal job
     */
    public RunnerJob cancel(Instant now) {
        checkTime(now);
        if (state.terminal()) return this;
        StopReason reason = now.isBefore(deadline) ? StopReason.CANCEL : StopReason.DEADLINE;
        if (state == State.QUEUED) {
            return next(now, terminalFor(reason), reason, fence, null, null);
        }
        if (leaseUntil != null && !now.isBefore(leaseUntil)) {
            return next(now, State.UNKNOWN, reason, fence, null, null);
        }
        State nextState = state == State.UNKNOWN || state == State.RECONCILING ? state : State.CANCELLING;
        if (nextState == state && reason == stopReason) return this;
        return next(now, nextState, reason, fence, leaseUntil, null);
    }

    /**
     * Evaluates liveness and deadline without presuming the result of any dispatched effect.
     * @param now store time
     * @return expired unknown state, deadline stop request, or unchanged job
     */
    public RunnerJob reconcileLiveness(Instant now) {
        checkTime(now);
        if (state.terminal()) return this;
        StopReason reason = reasonAt(now);
        if (state == State.QUEUED) {
            return reason == StopReason.DEADLINE
                    ? next(now, State.DEADLINE_EXCEEDED, reason, fence, null, null) : this;
        }
        if (leaseUntil != null && !now.isBefore(leaseUntil)) {
            return next(now, State.UNKNOWN, reason, fence, null, null);
        }
        if (reason != stopReason) {
            State nextState = state == State.CLAIMED ? State.CANCELLING : state;
            return next(now, nextState, reason, fence, leaseUntil, null);
        }
        return this;
    }

    /**
     * Issues a fresh report-only fence after the control plane authenticates the designated runner
     * and requests reconciliation. This permission must never launch or relaunch the job.
     * @param runnerId authenticated designated runner
     * @param now store time
     * @param ttl reconciliation lease
     * @return reconciliation state with a new fencing token
     */
    public RunnerJob beginReconciliation(String runnerId, Instant now, Duration ttl) {
        checkRunner(runnerId);
        checkTime(now);
        checkTtl(ttl);
        if (state != State.UNKNOWN) throw new IllegalStateException("job is not unknown");
        return next(now, State.RECONCILING, reasonAt(now), Math.incrementExact(fence),
                leaseEnd(now, ttl, false), null);
    }

    /**
     * Accepts a terminal quiescence report under the current live fence. Identical terminal reports
     * replay idempotently, but mismatched fences are rejected before replay. A cancellation or
     * elapsed deadline wins over a late success report; its payload remains available for audit.
     * @param runnerId authenticated runner
     * @param token current fencing token
     * @param report immutable terminal report
     * @param now store time
     * @return terminal job safe for workspace handoff
     */
    public RunnerJob complete(String runnerId, long token, RunnerResult report, Instant now) {
        checkRunner(runnerId);
        checkTime(now);
        checkFence(token);
        Objects.requireNonNull(report, "report");
        if (state.terminal()) {
            if (report.equals(result)) return this;
            throw new IllegalStateException("terminal report conflicts with accepted result");
        }
        checkReport(runnerId, token, now);
        if (state != State.CLAIMED && state != State.CANCELLING && state != State.RECONCILING) {
            throw new IllegalStateException("job cannot accept a terminal report");
        }
        validateResult(report);
        StopReason reason = reasonAt(now);
        return next(now, reason == StopReason.NONE ? State.COMPLETED : terminalFor(reason), reason,
                fence, null, report);
    }

    private void validateResult(RunnerResult report) {
        if (!command.outcomes().contains(report.outcome()) || report.payload().size() > authority.limits().payloadBytes()) {
            throw new IllegalArgumentException("runner result violates the pinned output contract");
        }
        // The output contract always includes a structured graph-safe JSON object. Validate before
        // terminal acceptance, not after the durable workspace barrier has already been released.
        if (!report.payload().contentType().equals("application/json")
                || !(ai.ravenroot.api.payload.PayloadJson.read(report.payload().bytes(),
                    ai.ravenroot.api.payload.PayloadLimits.DEFAULTS)
                    instanceof ai.ravenroot.api.payload.PayloadValue.MapValue)) {
            throw new IllegalArgumentException("runner output contract requires a bounded JSON object");
        }
        long artifactBytes = 0;
        long logBytes = 0;
        for (RunnerArtifact artifact : report.artifacts()) {
            if (!artifact.job().equals(identity)) throw new IllegalArgumentException("runner artifact scope mismatch");
            // Subtraction prevents overflow even with an adversarial Long.MAX_VALUE size.
            if (artifact.sizeBytes() > authority.limits().artifactBytes() - artifactBytes) {
                throw new IllegalArgumentException("runner artifacts exceed effective quota");
            }
            artifactBytes += artifact.sizeBytes();
            if (artifact.kind() == RunnerArtifact.Kind.LOG || artifact.kind() == RunnerArtifact.Kind.STDOUT
                    || artifact.kind() == RunnerArtifact.Kind.STDERR) {
                if (artifact.sizeBytes() > authority.limits().logBytes() - logBytes) {
                    throw new IllegalArgumentException("runner logs exceed effective quota");
                }
                logBytes += artifact.sizeBytes();
            }
        }
    }

    private RunnerJob next(Instant now, State state, StopReason reason, long fence, Instant lease,
                           RunnerResult result) {
        return new RunnerJob(identity, definition, command, runner, authority, input, deadline, now,
                Math.incrementExact(revision), fence, state, reason, lease, result);
    }

    private void checkRunner(String runnerId) {
        if (!runner.runnerId().equals(runnerId)) throw new IllegalArgumentException("runner identity mismatch");
    }

    private void checkTime(Instant now) {
        Objects.requireNonNull(now, "now");
        if (now.isBefore(updatedAt)) throw new IllegalArgumentException("runner store clock moved backwards");
    }

    private void checkFence(long token) {
        if (token < 1 || token != fence) throw new IllegalStateException("runner report is fenced out");
    }

    private void checkReport(String runnerId, long token, Instant now) {
        checkRunner(runnerId);
        checkTime(now);
        checkFence(token);
        if (leaseUntil == null || !now.isBefore(leaseUntil)) {
            throw new IllegalStateException("runner lease expired; reconciliation required");
        }
    }

    private static void checkTtl(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("invalid runner lease duration");
        }
    }

    private Instant leaseEnd(Instant now, Duration ttl, boolean capAtDeadline) {
        Instant end = now.plus(ttl);
        return capAtDeadline && deadline.isBefore(end) ? deadline : end;
    }

    private StopReason reasonAt(Instant now) {
        return now.isBefore(deadline) ? stopReason : StopReason.DEADLINE;
    }

    private static State terminalFor(StopReason reason) {
        return reason == StopReason.DEADLINE ? State.DEADLINE_EXCEEDED : State.CANCELLED;
    }

    /**
     * Returns complete durable identity.
     * @return complete durable identity
     */
    public RunnerJobIdentity identity() { return identity; }
    /**
     * Returns exact immutable resolved definition, including its version.
     * @return exact immutable resolved definition, including its version
     */
    public AgentDefinition definition() { return definition; }
    /**
     * Returns pinned command policy.
     * @return pinned command policy
     */
    public AgentCommand command() { return command; }
    /**
     * Returns pinned designated runner advertisement.
     * @return pinned designated runner advertisement
     */
    public RunnerRegistration runner() { return runner; }
    /**
     * Returns immutable effective capability and resource ceilings.
     * @return immutable effective capability and resource ceilings
     */
    public RunnerPolicy authority() { return authority; }
    /**
     * Returns immutable bounded input.
     * @return immutable bounded input
     */
    public OpaquePayload input() { return input; }
    /**
     * Returns absolute job deadline.
     * @return absolute job deadline
     */
    public Instant deadline() { return deadline; }
    /**
     * Returns authoritative time of the latest state mutation.
     * @return authoritative time of the latest state mutation
     */
    public Instant updatedAt() { return updatedAt; }
    /**
     * Returns strictly increasing mutation revision.
     * @return strictly increasing mutation revision
     */
    public long revision() { return revision; }
    /**
     * Returns current execution or report-only fencing token; zero before claim.
     * @return current execution or report-only fencing token; zero before claim
     */
    public long fence() { return fence; }
    /**
     * Returns current lifecycle state.
     * @return current lifecycle state
     */
    public State state() { return state; }
    /**
     * Returns sticky stop request.
     * @return sticky stop request
     */
    public StopReason stopReason() { return stopReason; }
    /**
     * Returns current lease expiry or null when no lease exists.
     * @return current lease expiry or null when no lease exists
     */
    public Instant leaseUntil() { return leaseUntil; }
    /**
     * Returns accepted terminal report or null before a quiescence report.
     * @return accepted terminal report or null before a quiescence report
     */
    public RunnerResult result() { return result; }
    /**
     * Returns true until quiescence is acknowledged or an unstarted job is cancelled/expired.
     * @return true until quiescence is acknowledged or an unstarted job is cancelled/expired
     */
    public boolean retainsWorkspace() { return !state.terminal(); }

    /** Diagnostics exclude job inputs, definition instructions, and runner grant identifiers. */
    @Override public String toString() {
        return "RunnerJob[id=" + identity.runnerJobId() + ", state=" + state + ", revision=" + revision + "]";
    }
}
