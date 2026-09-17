package ai.ravenroot.api.runner;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A graph-declared resource; physical lifecycle advances only on durable fenced evidence.
 * @param nodeId same-graph Workspace node identity
 * @param workspaceId opaque physical filesystem identity
 * @param profile pinned approved placement, lifecycle and authority policy
 * @param runnerId selected worker, stable after first dispatch
 * @param state durable logical lifecycle state
 * @param runtimeId observed container or VM identity, null before materialization
 * @param checkpoint immutable consolidated snapshot digest, or null
 * @param stopRequested sticky cancellation that prevents later Agent admission
 * @param updatedAt authoritative store timestamp of the last transition
 * @param generation positive named ownership generation, one for process resources
 */
public record WorkspaceResource(String nodeId, UUID workspaceId, WorkspaceProfile profile,
                                String runnerId, State state, String runtimeId, String checkpoint,
                                boolean stopRequested, Instant updatedAt, long generation) {
    /**
     * Creates the first ownership generation of a graph-declared resource.
     * @param nodeId same-graph Workspace node
     * @param workspaceId physical filesystem identity
     * @param profile exact approved profile
     * @param runnerId pinned worker
     * @param state durable lifecycle state
     * @param runtimeId observed runtime, or null
     * @param checkpoint immutable checkpoint, or null
     * @param stopRequested sticky stop flag
     * @param updatedAt authoritative transition time
     */
    public WorkspaceResource(String nodeId, UUID workspaceId, WorkspaceProfile profile, String runnerId, State state,
                             String runtimeId, String checkpoint, boolean stopRequested, Instant updatedAt) {
        this(nodeId, workspaceId, profile, runnerId, state, runtimeId, checkpoint, stopRequested, updatedAt, 1);
    }
    /** Resource states distinguish intent, physical acknowledgement and uncertain ownership. */
    public enum State {
        /** Declared but not opened. */ UNMATERIALIZED,
        /** Materialization requested; readiness remains unproven. */ OPENING,
        /** Materialized and eligible for permitted Agent invocations. */ READY,
        /** Successful close requested after quiescence. */ CLOSING,
        /** Successful close acknowledged with required evidence. */ CLOSED,
        /** Sticky abort requested, including active jobs. */ ABORTING,
        /** Aborted runtime quiescence acknowledged without claiming success. */ ABORTED,
        /** Resource failed without eligibility for implicit reuse. */ FAILED,
        /** Physical ownership or effects cannot safely be inferred. */ RECOVERY_REQUIRED,
        /** Exclusive physical deletion authority reserved. */ RELEASING,
        /** Cleanup acknowledged and retained capacity released. */ RELEASED
    }
    /** Validates the complete identity, pinned placement and immutable checkpoint syntax. */
    public WorkspaceResource {
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("workspace node identity required");
        Objects.requireNonNull(workspaceId); Objects.requireNonNull(profile); Objects.requireNonNull(state);
        runnerId = RunnerPolicy.identifier(runnerId); Objects.requireNonNull(updatedAt);
        if (generation < 1) throw new IllegalArgumentException("Workspace ownership generation must be positive");
        new RunnerResult.WorkspaceObservation(workspaceId, runtimeId, checkpoint);
        if (checkpoint != null && !checkpoint.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("workspace checkpoint must identify immutable evidence");
        }
    }
    /**
     * Closed and stopped resources cannot be implicitly reopened by an Agent.
     * @return true only for READY resources without a sticky stop
     */
    public boolean admitsAgent() { return state == State.READY && !stopRequested; }
    /**
     * EPHEMERAL creates a fresh filesystem per invocation, independent of runtime reuse policy.
     * @param jobId exact accepted invocation job
     * @param lifecycleCommand resource operation, or null for an Agent invocation
     * @return isolated child filesystem identity or the shared resource filesystem
     */
    public UUID invocationWorkspaceId(UUID jobId, String lifecycleCommand) {
        return profile.workspaceScope() == WorkspaceProfile.Scope.EPHEMERAL && lifecycleCommand == null
                ? UUID.nameUUIDFromBytes(("ravenroot.ephemeral.v1:" + workspaceId + ":" + jobId).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                : workspaceId;
    }
    /**
     * A named profile is the tenant-approved, explicit reuse name; versions cannot silently change ownership.
     * @param tenant owning tenant namespace
     * @param name approved durable resource name
     * @return deterministic tenant-separated physical identity
     */
    public static UUID namedWorkspaceId(String tenant, String name) {
        return UUID.nameUUIDFromBytes(("ravenroot.named-workspace.v1:" + tenant.length() + ":" + tenant + ":" + name)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    /**
     * Distinguishes resource termination from cleanup acknowledgement.
     * @return true for closed, aborted, failed, releasing or released resources
     */
    public boolean terminal() { return state == State.CLOSED || state == State.ABORTED || state == State.FAILED || state == State.RELEASING || state == State.RELEASED; }
    /**
     * Folds control intent without claiming that physical effects have completed.
     * @param command explicit lifecycle command
     * @param activeJobs whether any jobs still retain resource use
     * @param now authoritative store time
     * @return resource carrying the requested transition and sticky stop where applicable
     */
    public WorkspaceResource request(String command, boolean activeJobs, Instant now) {
        if (command.equals("abort") && stopRequested) return this;
        State next = switch (command) {
            case "open" -> {
                if (stopRequested || terminal()) throw new IllegalStateException("workspace is closed or stopped");
                if (state != State.UNMATERIALIZED && state != State.READY) throw new IllegalStateException("workspace requires reconciliation");
                yield state == State.READY ? State.READY : State.OPENING;
            }
            case "inspect" -> state;
            case "checkpoint" -> {
                if (!admitsAgent() || activeJobs) throw new IllegalStateException("checkpoint requires a ready quiescent workspace");
                yield state;
            }
            case "close" -> {
                if (activeJobs || !admitsAgent()) throw new IllegalStateException("close requires a ready quiescent workspace");
                yield State.CLOSING;
            }
            case "abort" -> terminal() ? state : State.ABORTING;
            default -> throw new IllegalArgumentException("unknown workspace command");
        };
        return new WorkspaceResource(nodeId, workspaceId, profile, runnerId, next, runtimeId, checkpoint,
                stopRequested || command.equals("abort"), now, generation);
    }
    /**
     * Accepts fenced physical observations without silently changing a retained runtime.
     * @param command completed resource command; inspect also records ordinary Agent observations
     * @param runtime observed physical runtime identity, or null
     * @param savedCheckpoint observed immutable checkpoint, or null
     * @param now authoritative store time
     * @return resource with acknowledged lifecycle/evidence and unchanged sticky stop
     */
    public WorkspaceResource observed(String command, String runtime, String savedCheckpoint, Instant now) {
        State next = switch (command) {
            case "open" -> stopRequested ? State.ABORTING : State.READY;
            case "close" -> State.CLOSED;
            case "abort" -> State.ABORTED;
            case "inspect", "checkpoint" -> state;
            default -> throw new IllegalArgumentException("unknown workspace command");
        };
        if (profile.workspaceScope() != WorkspaceProfile.Scope.EPHEMERAL && profile.runtimeLifecycle() == WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE
                && runtimeId != null && runtime != null && !runtimeId.equals(runtime)) {
            throw new IllegalStateException("workspace runtime identity changed without migration");
        }
        return new WorkspaceResource(nodeId, workspaceId, profile, runnerId, next,
                runtime == null ? runtimeId : runtime, savedCheckpoint == null ? checkpoint : savedCheckpoint,
                stopRequested, now, generation);
    }
}
