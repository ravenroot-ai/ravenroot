package ai.ravenroot.core.deployment;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.lifecycle.DeploymentLifecycleTarget;

import java.util.Optional;

/**
 * Resolves the runtime this process can actually reach for one deployment.
 *
 * <h2>Why the answer is optional, and why that is not a failure</h2>
 * <p>The durable registry is the authority for every deployment of every tenant it holds, while a
 * given process hosts only some of them. A coordinator or reconciler that assumed a target existed
 * for every record it read would treat "this deployment lives on another host" as a fault, and the
 * loudest available response to a fault is to retry — against a deployment this process was never
 * going to be able to move. Returning empty says the true thing instead: the decision is recorded and
 * durable, and the effect is somebody else's to perform.</p>
 *
 * <p>An empty answer is therefore reported as a deferral rather than as a failure, and the durable
 * intent is left standing so the owner that can reach the runtime finishes it.</p>
 */
@FunctionalInterface
public interface DeploymentTargets {

    /** Resolves nothing, for a process that hosts no deployment runtime at all. */
    DeploymentTargets NONE = (tenantId, deploymentId) -> Optional.empty();

    /**
     * Returns the runtime port for one deployment hosted in this process.
     *
     * @param tenantId tenant owning the deployment.
     * @param deploymentId deployment whose runtime is being resolved.
     * @return the lifecycle port, or empty when this process does not host that deployment.
     */
    Optional<DeploymentLifecycleTarget> resolve(String tenantId, DeploymentId deploymentId);
}
