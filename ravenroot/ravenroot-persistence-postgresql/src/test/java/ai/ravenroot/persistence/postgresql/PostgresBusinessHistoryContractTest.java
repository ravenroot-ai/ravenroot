package ai.ravenroot.persistence.postgresql;

class PostgresBusinessHistoryContractTest extends ai.ravenroot.testkit.persistence.BusinessHistoryContract {
    @Override protected ai.ravenroot.api.persistence.ExecutionStore createStore(String id, java.time.Clock clock) {
        return new PostgresExecutionStore(PostgresTestDatabase.dataSourceFor(id), clock);
    }
}
