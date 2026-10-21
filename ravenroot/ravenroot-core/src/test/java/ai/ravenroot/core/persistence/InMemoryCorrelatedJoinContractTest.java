package ai.ravenroot.core.persistence;

class InMemoryCorrelatedJoinContractTest extends ai.ravenroot.testkit.persistence.CorrelatedJoinContract {
    @Override protected ai.ravenroot.api.persistence.ExecutionStore createStore(String id, java.time.Clock clock) {
        return new InMemoryExecutionStore(clock);
    }
}
