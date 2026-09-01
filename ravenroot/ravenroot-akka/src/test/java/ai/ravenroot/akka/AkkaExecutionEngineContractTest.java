package ai.ravenroot.akka;

import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.testkit.ExecutionEngineContract;

final class AkkaExecutionEngineContractTest extends ExecutionEngineContract {
    @Override
    protected ExecutionEngine createEngine(String systemName) {
        return new AkkaExecutionEngine(systemName);
    }
}
