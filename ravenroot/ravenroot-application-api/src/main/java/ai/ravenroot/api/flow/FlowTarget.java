package ai.ravenroot.api.flow;

import ai.ravenroot.api.deployment.DeploymentId;

import java.util.Objects;

/**
 * Exact registered deployment version selected before a child starts.
 * @param deploymentId registered deployment identity
 * @param version positive immutable version
 */
public record FlowTarget(DeploymentId deploymentId, long version) {
    /** Validates an exact target. */
    public FlowTarget {
        Objects.requireNonNull(deploymentId, "deploymentId");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
    }
}
