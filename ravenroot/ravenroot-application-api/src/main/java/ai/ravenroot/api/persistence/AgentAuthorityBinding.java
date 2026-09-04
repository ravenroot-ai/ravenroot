package ai.ravenroot.api.persistence;

import java.util.Set;
import java.util.UUID;

/** Trusted immutable binding between a grant and its runtime invocation. */
public record AgentAuthorityBinding(UUID grantId, String nodeId, UUID invocationId,
                                    Set<UUID> causalParentInvocationIds) {
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
