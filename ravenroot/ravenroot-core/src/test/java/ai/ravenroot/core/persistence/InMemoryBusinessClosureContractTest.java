package ai.ravenroot.core.persistence;
class InMemoryBusinessClosureContractTest extends ai.ravenroot.testkit.persistence.BusinessClosureContract {
    @Override protected ai.ravenroot.api.persistence.ExecutionStore createStore(String id, java.time.Clock clock) {
        return new InMemoryExecutionStore(clock);
    }
}
