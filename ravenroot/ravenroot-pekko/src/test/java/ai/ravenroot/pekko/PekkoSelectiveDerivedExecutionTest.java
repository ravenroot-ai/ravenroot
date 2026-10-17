package ai.ravenroot.pekko;

import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.testkit.SelectiveDerivedExecutionContract;

final class PekkoSelectiveDerivedExecutionTest extends SelectiveDerivedExecutionContract {
    @Override
    protected ExecutionEngine createEngine(String systemName) {
        return new PekkoExecutionEngine(systemName);
    }
}
