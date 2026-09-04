package ai.ravenroot.api.persistence;

import java.util.Set;
import java.util.UUID;

/**
 * Trusted immutable binding between a grant and its runtime invocation.
 *
 * @param grantId bound authority grant
 * @param nodeId bound graph node
 * @param invocationId bound invocation identity
 * @param causalParentInvocationIds causal parent invocations contributing authority
 */
public record AgentAuthorityBinding(UUID grantId, String nodeId, UUID invocationId,
                                    Set<UUID> causalParentInvocationIds) {
    /** Validates and snapshots the trusted binding. */
    public AgentAuthorityBinding {
        if (grantId == null || invocationId == null) throw new IllegalArgumentException("binding ids are required");
        nodeId = AgentAuthorityRootRegistration.token(nodeId, "nodeId", 256);
        causalParentInvocationIds = Set.copyOf(causalParentInvocationIds == null
                ? Set.of() : causalParentInvocationIds);
        if (causalParentInvocationIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("causal parents cannot contain null");
        }
    }
}
