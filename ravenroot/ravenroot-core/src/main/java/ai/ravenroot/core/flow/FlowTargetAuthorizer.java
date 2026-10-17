package ai.ravenroot.core.flow;

import ai.ravenroot.api.deployment.registry.GraphVersion;
import ai.ravenroot.api.security.SecurityContext;

/** Trusted policy decision applied after tenant isolation and before a child is admitted. */
@FunctionalInterface
public interface FlowTargetAuthorizer {
    boolean authorized(SecurityContext caller, GraphVersion target);

    /**
     * Baseline target policy for the narrowed interior identity carried by a running graph.
     * Graph execution authorization has already consumed roles and scopes before this context is
     * created, so nested execution cannot safely recreate that ingress decision. The immutable
     * version author is the existing target-specific authority available here.
     */
    static FlowTargetAuthorizer creatorOwnedTargets() {
        return (caller, target) -> caller.tenantId().equals(target.tenantId())
                && caller.qualifiedIdentity().equals(target.createdBy());
    }
}
