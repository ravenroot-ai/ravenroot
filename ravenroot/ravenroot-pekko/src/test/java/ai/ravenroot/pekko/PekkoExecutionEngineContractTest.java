package ai.ravenroot.pekko;

import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.testkit.ExecutionEngineContract;

final class PekkoExecutionEngineContractTest extends ExecutionEngineContract {
    @Override
    protected ExecutionEngine createEngine(String systemName) {
        return new PekkoExecutionEngine(systemName);
    }
}
