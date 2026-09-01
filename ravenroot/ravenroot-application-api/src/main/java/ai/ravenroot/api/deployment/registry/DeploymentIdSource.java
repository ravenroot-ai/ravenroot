package ai.ravenroot.api.deployment.registry;

import ai.ravenroot.api.deployment.DeploymentId;

/** Server-side seam for minting opaque stable deployment identifiers. */
@FunctionalInterface
public interface DeploymentIdSource {
/**
 * Mints an opaque deployment identity in the tenant's registry scope.
 * @param tenantId tenant for which the identifier is allocated.
 * @return newly allocated stable deployment identifier.
 */
    DeploymentId mint(String tenantId);
}
