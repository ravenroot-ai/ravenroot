package ai.ravenroot.api.node.service;

import ai.ravenroot.api.execution.NodeMessage;

import java.util.Set;

/** Explicit attenuated authority for a child invocation that already exists in the durable graph run. */
public record AgentChildResourceRequest(NodeMessage child, Set<String> dataScopes,
                                        Set<String> authorityScopes, AgentResourceRequest resources) {
    public AgentChildResourceRequest {
        if (child == null || resources == null) throw new IllegalArgumentException("child resource scope is required");
        dataScopes = Set.copyOf(dataScopes == null ? Set.of() : dataScopes);
        authorityScopes = Set.copyOf(authorityScopes == null ? Set.of() : authorityScopes);
    }
}
