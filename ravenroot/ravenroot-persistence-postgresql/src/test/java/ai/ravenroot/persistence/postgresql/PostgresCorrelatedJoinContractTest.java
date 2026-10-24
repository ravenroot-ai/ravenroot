package ai.ravenroot.persistence.postgresql;

class PostgresCorrelatedJoinContractTest extends ai.ravenroot.testkit.persistence.CorrelatedJoinContract {
    @Override protected ai.ravenroot.api.persistence.ExecutionStore createStore(String id, java.time.Clock clock,
            ai.ravenroot.api.correlation.BusinessHistoryPolicy policy) {
        return new PostgresExecutionStore(PostgresTestDatabase.dataSourceFor(id), clock, PostgresStoreConfig.defaults().withBusinessHistories(policy));
    }
}
