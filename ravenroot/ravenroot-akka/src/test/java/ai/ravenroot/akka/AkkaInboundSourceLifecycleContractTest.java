package ai.ravenroot.akka;

import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.testkit.InboundSourceLifecycleContract;

/**
 * Mechanical mirror of {@code PekkoInboundSourceLifecycleContractTest}. Unverifiable in this
 * environment for the same reason {@code AkkaGraphDeploymentContractTest} already documents: the BSL
 * artifact has never resolved here. Mirrored rather than skipped so the suite is complete the moment
 * that artifact does resolve somewhere.
 */
final class AkkaInboundSourceLifecycleContractTest extends InboundSourceLifecycleContract {
    @Override
    protected ExecutionEngine createEngine(String systemName) {
        return new AkkaExecutionEngine(systemName);
    }
}
