package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.persistence.DurableExecutionResult;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionResultNodes;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.GraphVersionPin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The unknown-name gate for {@code execution_result}'s own {@code status} and {@code payload_state}
 * columns, modelled on {@link SqliteTerminationReasonCorruptionGateTest}.
 *
 * <p>{@code readExecutionResult} shares {@code processStatusOf} and {@code terminationReasonOf} with
 * every other lifecycle table, and {@code readResultPayload} applies the identical rule to
 * {@code payload_state}: a stored name this build does not recognise must surface as
 * {@link ExecutionStoreFailure.Corrupted}, never be folded into an absent or default value -- reading
 * an unrecognised {@code payload_state} as {@code NONE} would report a run whose output was refused as
 * one that produced nothing at all, and reading an unrecognised {@code status} as anything would
 * misreport how the run in fact ended.</p>
 *
 * <p>Sharing the parsing helper with {@code process_instance} and {@code traversal} is not itself
 * proof the wiring on <em>this</em> table is correct: the SQL that reads {@code execution_result} is
 * its own path, added by schema 18 and never previously exercised against a corrupted row. This is
 * that exercise, for both columns migration 18 introduced with this failure mode.</p>
 */
class SqliteExecutionResultCorruptionGateTest {

    private static final String TENANT = "acme";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path databaseDirectory;

    private static SqliteExecutionStore openAt(Path file) {
        return new SqliteExecutionStore(file, CLOCK);
    }

    private static DurableExecutionResult completedResult(SqliteExecutionStore store, ExecutionKey key,
                                                           UUID traversalId) {
        return DurableExecutionResult.of(key, traversalId, new GraphVersionPin("graph-v1"),
                ProcessInstanceStatus.COMPLETED, null, NOW, NOW.plusSeconds(1),
                Map.of("answer", 42L), ExecutionResultNodes.empty(), null,
                store.maxExecutionResultPayloadBytes());
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static ExecutionStoreFailure failureOf(Runnable operation) {
        CompletionException thrown = assertThrows(CompletionException.class, operation::run);
        ExecutionStoreException failure = ExecutionStoreException.unwrap(thrown);
        assertNotNull(failure, "adapters must not leak non-store exceptions: " + thrown);
        return failure.failure();
    }

    @Test
    void anExecutionResultStatusNameThisBinaryDoesNotKnowIsCorruptedRatherThanMisread() throws Exception {
        Path file = databaseDirectory.resolve("result-status-gate.db");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();

        try (var store = openAt(file)) {
            await(store.apply(Fixtures.creationBatch(key, traversalId)));
            await(store.recordExecutionResult(completedResult(store, key, traversalId)));
        }

        // The row as this build wrote it: the status stored by name, exactly like every other
        // lifecycle table, which is why no data migration was needed for a future member.
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = raw.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "UPDATE execution_result SET status = 'FROM_THE_FUTURE' WHERE tenant_id = '"
                            + TENANT + "'"));
        }

        try (var store = openAt(file)) {
            ExecutionStoreFailure failure =
                    failureOf(() -> await(store.loadExecutionResult(TENANT, traversalId)));
            var corrupted = assertInstanceOf(ExecutionStoreFailure.Corrupted.class, failure,
                    "an unrecognised result status must stop the reader rather than be misread as one "
                            + "this build happens to know");
            assertEquals(key.processInstanceId(), corrupted.key().processInstanceId());
        }
    }

    @Test
    void anExecutionResultPayloadStateThisBinaryDoesNotKnowIsCorruptedRatherThanMisreadAsAbsent()
            throws Exception {
        Path file = databaseDirectory.resolve("result-payload-state-gate.db");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();

        try (var store = openAt(file)) {
            await(store.apply(Fixtures.creationBatch(key, traversalId)));
            await(store.recordExecutionResult(completedResult(store, key, traversalId)));
        }

        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = raw.createStatement()) {
            // The row as this build wrote it: RETAINED, with bytes actually stored.
            var rows = statement.executeQuery(
                    "SELECT payload_state FROM execution_result WHERE tenant_id = '" + TENANT + "'");
            assertEquals(true, rows.next());
            assertEquals("RETAINED", rows.getString("payload_state"));
            rows.close();

            assertEquals(1, statement.executeUpdate(
                    "UPDATE execution_result SET payload_state = 'FROM_THE_FUTURE' WHERE tenant_id = '"
                            + TENANT + "'"));
        }

        try (var store = openAt(file)) {
            ExecutionStoreFailure failure =
                    failureOf(() -> await(store.loadExecutionResult(TENANT, traversalId)));
            var corrupted = assertInstanceOf(ExecutionStoreFailure.Corrupted.class, failure,
                    "an unrecognised payload state must stop the reader rather than be misread as "
                            + "NONE -- that would report a run whose output was refused as one that "
                            + "produced nothing at all");
            assertEquals(key.processInstanceId(), corrupted.key().processInstanceId());
        }
    }

    @Test
    void anExecutionResultTerminationReasonNameThisBinaryDoesNotKnowIsCorruptedRatherThanMisread()
            throws Exception {
        Path file = databaseDirectory.resolve("result-termination-reason-gate.db");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();

        try (var store = openAt(file)) {
            await(store.apply(Fixtures.creationBatch(key, traversalId)));
            await(store.recordExecutionResult(DurableExecutionResult.of(key, traversalId,
                    new GraphVersionPin("graph-v1"), ProcessInstanceStatus.FAILED,
                    ai.ravenroot.api.application.ExecutionTerminationReason.CANCELLED, NOW,
                    NOW.plusSeconds(1), null, ExecutionResultNodes.empty(), null,
                    store.maxExecutionResultPayloadBytes())));
        }

        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = raw.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "UPDATE execution_result SET termination_reason = 'FROM_THE_FUTURE' "
                            + "WHERE tenant_id = '" + TENANT + "'"));
        }

        try (var store = openAt(file)) {
            ExecutionStoreFailure failure =
                    failureOf(() -> await(store.loadExecutionResult(TENANT, traversalId)));
            var corrupted = assertInstanceOf(ExecutionStoreFailure.Corrupted.class, failure,
                    "an unrecognised termination reason on a result row must stop the reader rather "
                            + "than be folded into an absent one -- that would report a cancellation "
                            + "as an ordinary failure, silently, exactly as it would on process_instance");
            assertEquals(key.processInstanceId(), corrupted.key().processInstanceId());
        }
    }
}
