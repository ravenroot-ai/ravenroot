package ai.ravenroot.akka;

import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.ExecutionEngineProvider;

public final class AkkaExecutionEngineProvider implements ExecutionEngineProvider {
    @Override
    public String id() {
        return "akka";
    }

    @Override
    public ExecutionEngine create(String systemName) {
        return new AkkaExecutionEngine(systemName);
    }
}
