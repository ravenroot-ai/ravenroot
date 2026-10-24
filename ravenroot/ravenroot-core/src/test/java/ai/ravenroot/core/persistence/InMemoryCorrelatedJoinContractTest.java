package ai.ravenroot.core.persistence;

class InMemoryCorrelatedJoinContractTest extends ai.ravenroot.testkit.persistence.CorrelatedJoinContract {
    @Override protected ai.ravenroot.api.persistence.ExecutionStore createStore(String id, java.time.Clock clock,
            ai.ravenroot.api.correlation.BusinessHistoryPolicy policy) {
        var defaults = InMemoryExecutionStorePolicy.DEFAULTS;
        return new InMemoryExecutionStore(clock, new InMemoryExecutionStorePolicy(defaults.maximumLeaseTtl(),
                defaults.maximumPayloadBytes(), defaults.maximumClockSkew(), defaults.journalRetention(),
                defaults.maximumInventoryPageSize(), defaults.terminalRetention(), defaults.executionResultRetention(), policy));
    }
}
