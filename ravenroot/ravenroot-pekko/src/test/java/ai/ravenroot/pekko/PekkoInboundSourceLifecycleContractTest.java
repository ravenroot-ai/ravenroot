package ai.ravenroot.pekko;

import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.testkit.InboundSourceLifecycleContract;

final class PekkoInboundSourceLifecycleContractTest extends InboundSourceLifecycleContract {
    @Override
    protected ExecutionEngine createEngine(String systemName) {
        return new PekkoExecutionEngine(systemName);
    }
}
