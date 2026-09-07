package ai.ravenroot.pekko;

import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.ExecutionEnginePolicy;
import ai.ravenroot.api.execution.ExecutionEngineProvider;

public final class PekkoExecutionEngineProvider implements ExecutionEngineProvider {
    @Override
    public String id() {
        return "pekko";
    }

    @Override
    public ExecutionEngine create(String systemName) {
        return new PekkoExecutionEngine(systemName);
    }

    @Override
    public ExecutionEngine create(String systemName, ExecutionEnginePolicy policy) {
        return new PekkoExecutionEngine(systemName, policy);
    }
}
