package ai.ravenroot.pekko;

import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.testkit.GraphDeploymentContract;

final class PekkoGraphDeploymentContractTest extends GraphDeploymentContract {
    @Override
    protected ExecutionEngine createEngine(String systemName) {
        return new PekkoExecutionEngine(systemName);
    }
}
