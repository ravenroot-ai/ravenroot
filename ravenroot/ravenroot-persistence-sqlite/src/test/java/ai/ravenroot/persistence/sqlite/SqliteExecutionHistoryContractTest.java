package ai.ravenroot.persistence.sqlite;

class SqliteExecutionHistoryContractTest extends ai.ravenroot.testkit.persistence.ExecutionHistoryContract {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Override protected ai.ravenroot.api.persistence.ExecutionStore createStore(String id, java.time.Clock clock) {
        return new SqliteExecutionStore(directory.resolve(id + ".db"), clock);
    }
}
