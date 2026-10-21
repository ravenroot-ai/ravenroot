package ai.ravenroot.persistence.postgresql;
class PostgresBusinessClosureContractTest extends ai.ravenroot.testkit.persistence.BusinessClosureContract {
    @Override protected ai.ravenroot.api.persistence.ExecutionStore createStore(String id, java.time.Clock clock) {
        return new PostgresExecutionStore(PostgresTestDatabase.dataSourceFor(id), clock, PostgresStoreConfig.defaults());
    }
}
