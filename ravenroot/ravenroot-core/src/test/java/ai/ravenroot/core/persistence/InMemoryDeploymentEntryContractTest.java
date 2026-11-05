package ai.ravenroot.core.persistence;
class InMemoryDeploymentEntryContractTest extends ai.ravenroot.testkit.persistence.DeploymentEntryContract {
    @Override protected ai.ravenroot.api.persistence.ExecutionStore createStore(String id, java.time.Clock clock,
            ai.ravenroot.api.correlation.BusinessHistoryPolicy policy) {
        var d = InMemoryExecutionStorePolicy.DEFAULTS;
        return new InMemoryExecutionStore(clock, new InMemoryExecutionStorePolicy(d.maximumLeaseTtl(), d.maximumPayloadBytes(),
                d.maximumClockSkew(), d.journalRetention(), d.maximumInventoryPageSize(), d.terminalRetention(),
                d.executionResultRetention(), policy), ai.ravenroot.api.persistence.HumanTaskPolicy.DEFAULTS);
    }
}
