package ai.ravenroot.api.runner;

import ai.ravenroot.api.persistence.ExecutionKey;
import java.time.Instant;
import java.util.*;

/**
 * Authenticated control-plane proof that terminal process retention permits workspace cleanup.
 * @param protocolVersion exact supported wire version
 * @param execution owning tenant and terminal process
 * @param workspaceId opaque process workspace identity
 * @param runnerId designated runner permitted to remove its local workspace
 * @param jobIds complete accepted job set for validating local ownership
 * @param notBefore earliest cleanup time after the retention barrier
 * @param workspaceScope resource ownership lifetime
 * @param generation named ownership generation protected against newer reuse
 * @param physicalCleanup false when only an old named ownership receipt may be released
 */
public record RunnerWorkspaceRelease(int protocolVersion, ExecutionKey execution, UUID workspaceId,
                                     String runnerId, Set<UUID> jobIds, Instant notBefore,
                                     WorkspaceProfile.Scope workspaceScope, long generation, boolean physicalCleanup) {
    /**
     * Creates the legacy process-owned physical cleanup proof.
     * @param protocolVersion supported runner protocol
     * @param execution owning tenant and terminal process
     * @param workspaceId owned filesystem identity
     * @param runnerId approved cleanup worker
     * @param jobIds complete retained job identities
     * @param notBefore earliest authorized cleanup time
     */
    public RunnerWorkspaceRelease(int protocolVersion, ExecutionKey execution, UUID workspaceId, String runnerId, Set<UUID> jobIds, Instant notBefore) {
        this(protocolVersion, execution, workspaceId, runnerId, jobIds, notBefore, WorkspaceProfile.Scope.PROCESS_INSTANCE, 1, true);
    }
    /** Validates the bounded cleanup proof; transport authentication remains the caller's duty. */
    public RunnerWorkspaceRelease {
        if (protocolVersion != 1) throw new IllegalArgumentException("unsupported runner release");
        Objects.requireNonNull(execution); Objects.requireNonNull(workspaceId); Objects.requireNonNull(notBefore);
        runnerId = RunnerPolicy.identifier(runnerId); jobIds = Set.copyOf(jobIds);
        Objects.requireNonNull(workspaceScope);
        if (generation < 1) throw new IllegalArgumentException("invalid cleanup generation");
        if (jobIds.isEmpty()) throw new IllegalArgumentException("invalid runner release jobs");
    }
}
