package ai.ravenroot.api.runner;

import java.util.Objects;
import java.util.UUID;

/**
 * Versioned dispatch view. Contains no graph checkpoint, bearer credential or operator context.
 * @param protocolVersion exact supported wire version
 * @param workspaceId opaque process workspace identity
 * @param job accepted job with pinned definition, authority and execution fence
 * @param workspace graph resource and pinned profile, or null only for legacy durable work
 * @param lifecycleCommand explicit resource operation, or null for an Agent invocation
 */
public record RunnerAssignment(int protocolVersion, UUID workspaceId, RunnerJob job,
                               WorkspaceResource workspace, String lifecycleCommand) {
    /**
     * Reconstructs a legacy durable assignment without inventing a graph resource.
     * @param protocolVersion supported runner protocol
     * @param workspaceId legacy physical workspace identity
     * @param job accepted fenced job
     */
    public RunnerAssignment(int protocolVersion, UUID workspaceId, RunnerJob job) {
        this(protocolVersion, workspaceId, job, null, null);
    }
    /**
     * Conversation namespace, deliberately independent of filesystem and runtime identities.
     * @return stable tenant/process/exact-definition session identity across loops and traversals
     */
    public UUID agentSessionId() {
        return job.definition().reference().sessionId(job.identity().execution().processInstanceId());
    }
    /** Validates the wire version and complete dispatch identity. */
    public RunnerAssignment {
        if (protocolVersion != RunnerRegistration.PROTOCOL_VERSION) {
            throw new IllegalArgumentException("unsupported runner protocol");
        }
        Objects.requireNonNull(workspaceId); Objects.requireNonNull(job);
        if (workspace != null && (!workspaceId.equals(workspace.invocationWorkspaceId(job.identity().runnerJobId(), lifecycleCommand))
                || !job.runner().runnerId().equals(workspace.runnerId())
                || !job.identity().execution().tenantId().equals(workspace.profile().reference().tenantId()))) {
            throw new IllegalArgumentException("assignment workspace identity mismatch");
        }
        if (lifecycleCommand != null && (workspace == null || !lifecycleCommand.equals(job.command().name()))) {
            throw new IllegalArgumentException("invalid lifecycle assignment");
        }
    }
}
