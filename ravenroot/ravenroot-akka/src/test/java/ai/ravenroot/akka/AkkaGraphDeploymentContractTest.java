package ai.ravenroot.akka;

import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.testkit.GraphDeploymentContract;

/**
 * Mechanical mirror of {@code PekkoGraphDeploymentContractTest}. Unverifiable in this
 * environment: {@code ravenroot-akka} has never been compiled here, its BSL artifact has never
 * resolved, as declared in {@link AkkaExecutionEngine}'s own Javadoc. Mirrored rather than skipped
 * so the suite is complete the moment that artifact does resolve somewhere.
 */
final class AkkaGraphDeploymentContractTest extends GraphDeploymentContract {
    @Override
    protected ExecutionEngine createEngine(String systemName) {
        return new AkkaExecutionEngine(systemName);
    }
}
