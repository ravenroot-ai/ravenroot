package ai.ravenroot.core.persistence;

class InMemoryExecutionHistoryContractTest extends ai.ravenroot.testkit.persistence.ExecutionHistoryContract {
    @Override protected ai.ravenroot.api.persistence.ExecutionStore createStore(String id, java.time.Clock clock) {
        return new InMemoryExecutionStore(clock);
    }
}
