package ai.ravenroot.core.persistence;

class InMemoryBusinessHistoryContractTest extends ai.ravenroot.testkit.persistence.BusinessHistoryContract {
    @Override protected ai.ravenroot.api.persistence.ExecutionStore createStore(String id, java.time.Clock clock) {
        return new InMemoryExecutionStore(clock);
    }
}
