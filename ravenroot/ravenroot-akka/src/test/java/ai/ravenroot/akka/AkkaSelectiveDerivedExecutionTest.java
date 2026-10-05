package ai.ravenroot.akka;

import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.testkit.SelectiveDerivedExecutionContract;

final class AkkaSelectiveDerivedExecutionTest extends SelectiveDerivedExecutionContract {
    @Override
    protected ExecutionEngine createEngine(String systemName) {
        return new AkkaExecutionEngine(systemName);
    }
}
