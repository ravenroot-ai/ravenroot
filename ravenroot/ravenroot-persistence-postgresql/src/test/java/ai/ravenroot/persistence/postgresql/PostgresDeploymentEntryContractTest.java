package ai.ravenroot.persistence.postgresql;
class PostgresDeploymentEntryContractTest extends ai.ravenroot.testkit.persistence.DeploymentEntryContract {
    @Override protected ai.ravenroot.api.persistence.ExecutionStore createStore(String id, java.time.Clock clock,
            ai.ravenroot.api.correlation.BusinessHistoryPolicy policy) {
        return new PostgresExecutionStore(PostgresTestDatabase.dataSourceFor(id), clock, PostgresStoreConfig.defaults().withBusinessHistories(policy));
    }
}
