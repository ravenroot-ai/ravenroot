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
 */
public record RunnerWorkspaceRelease(int protocolVersion, ExecutionKey execution, UUID workspaceId,
                                     String runnerId, Set<UUID> jobIds, Instant notBefore) {
    /** Validates the bounded cleanup proof; transport authentication remains the caller's duty. */
    public RunnerWorkspaceRelease {
        if (protocolVersion != 1) throw new IllegalArgumentException("unsupported runner release");
        Objects.requireNonNull(execution); Objects.requireNonNull(workspaceId); Objects.requireNonNull(notBefore);
        runnerId = RunnerPolicy.identifier(runnerId); jobIds = Set.copyOf(jobIds);
        if (jobIds.isEmpty() || jobIds.size() > RunnerWorkspaceState.MAX_JOBS) throw new IllegalArgumentException("invalid runner release jobs");
    }
}
