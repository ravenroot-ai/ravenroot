package ai.ravenroot.core.deployment.registry;

import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.testkit.persistence.DeploymentRegistryContract;
import java.time.Clock;

class InMemoryDeploymentRegistryContractTest extends DeploymentRegistryContract {
    @Override protected DeploymentRegistry createRegistry(Clock clock) { return new InMemoryDeploymentRegistry(clock); }
}
