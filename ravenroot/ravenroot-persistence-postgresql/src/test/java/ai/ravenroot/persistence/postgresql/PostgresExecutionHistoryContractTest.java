package ai.ravenroot.persistence.postgresql;

class PostgresExecutionHistoryContractTest extends ai.ravenroot.testkit.persistence.ExecutionHistoryContract {
    @Override protected ai.ravenroot.api.persistence.ExecutionStore createStore(String id, java.time.Clock clock) {
        return new PostgresExecutionStore(PostgresTestDatabase.dataSourceFor(id), clock);
    }
}
