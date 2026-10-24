package ai.ravenroot.persistence.sqlite;
@org.junit.jupiter.api.Timeout(120)
class SqliteBusinessClosureContractTest extends ai.ravenroot.testkit.persistence.BusinessClosureContract {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Override protected ai.ravenroot.api.persistence.ExecutionStore createStore(String id, java.time.Clock clock) {
        return new SqliteExecutionStore(directory.resolve(id + ".db"), clock);
    }
}
