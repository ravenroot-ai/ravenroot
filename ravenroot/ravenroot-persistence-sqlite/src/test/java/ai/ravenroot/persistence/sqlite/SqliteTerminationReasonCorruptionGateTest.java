package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.ExecutionTerminationReason;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unknown-name gate that {@code termination_reason} (migration 17) creates, and the one it
 * deliberately does <em>not</em> create.
 *
 * <p>Contrast this with {@link SqliteParkedAttemptRollbackGateTest}: a new status <em>name</em>
 * (like {@code PARKED}) is a one-way, forward-only enlargement, because the first row carrying it
 * makes a downgrade to an older binary unsafe. Migration 17's own comment is explicit that a
 * nullable column beside an <em>unchanged</em> status does not create that hazard at all -- an
 * older binary simply does not {@code SELECT} the column, reads the {@code FAILED} it always read,
 * and a downgrade past the first cancelled row stays safe permanently. This test does not exercise
 * a rollback hazard, because there is not one to exercise.</p>
 *
 * <p>What survives is the adjacent, narrower property {@code SqliteExecutionStore}'s own
 * {@code terminationReasonOf} javadoc states: a stored <em>name</em> this build does not recognise
 * -- whatever binary or migration path put it there -- must still be refused as
 * {@link ExecutionStoreFailure.Corrupted}, never silently folded into an absent reason. Reading an
 * unknown name as "unstated" would report a cancelled run as an ordinary failure, which is the
 * exact misreading this column exists to prevent, reintroduced by the very code meant to carry it.
 * That failure mode is real regardless of the direction (older reader, newer reader, or a hand-edited
 * row) that produced the unrecognised name, which is why it is asserted directly here rather than
 * inferred from the migration's own safety argument.</p>
 */
class SqliteTerminationReasonCorruptionGateTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String TENANT = "acme";

    @TempDir
    Path databaseDirectory;

    @Test
    void aTerminationReasonNameThisBinaryDoesNotKnowIsReportedAsCorruptedRatherThanMisread() throws Exception {
        Path file = databaseDirectory.resolve("termination-reason-gate.db");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();

        try (var store = openAt(file)) {
            cancelOneTraversal(store, key, traversalId);
            StoredProcessInstance loaded = await(store.load(key));
            assertEquals(ProcessInstanceStatus.FAILED, loaded.state().status());
            assertEquals(ExecutionTerminationReason.CANCELLED, loaded.state().terminationReason());
            assertEquals(ExecutionTerminationReason.CANCELLED,
                    loaded.state().traversals().get(traversalId).terminationReason());
        }

        // The row as this build wrote it: the reason stored by name, exactly like every status,
        // which is why no data migration was needed to introduce the column.
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = raw.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT status, termination_reason FROM process_instance WHERE tenant_id = '"
                            + TENANT + "'")) {
                assertTrue(rows.next());
                assertEquals("FAILED", rows.getString("status"));
                assertEquals("CANCELLED", rows.getString("termination_reason"),
                        "the reason is stored as a name, the same way status is");
            }
            assertEquals(1, statement.executeUpdate(
                    "UPDATE process_instance SET termination_reason = 'FROM_THE_FUTURE' "
                            + "WHERE tenant_id = '" + TENANT + "'"));
            assertEquals(1, statement.executeUpdate(
                    "UPDATE traversal SET termination_reason = 'FROM_THE_FUTURE' "
                            + "WHERE tenant_id = '" + TENANT + "'"));
        }

        try (var store = openAt(file)) {
            ExecutionStoreFailure failure = failureOf(() -> await(store.load(key)));
            var corrupted = assertInstanceOf(ExecutionStoreFailure.Corrupted.class, failure,
                    "an unrecognised termination reason must stop the reader rather than be folded "
                            + "into an absent one -- that would report a cancellation as an ordinary "
                            + "failure, silently");
            assertEquals(key.processInstanceId(), corrupted.key().processInstanceId());
        }
    }

    /**
     * The companion positive case: {@code NULL} is not the rollback hazard, and must keep reading
     * as an ordinary, unstated termination -- exactly what every row written before this column
     * existed, and every ordinary failure written after it, both look like.
     */
    @Test
    void anAbsentTerminationReasonIsNeverConfusedWithAnUnreadableOne() throws Exception {
        Path file = databaseDirectory.resolve("termination-reason-absent.db");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();

        try (var store = openAt(file)) {
            StoredProcessInstance created = await(store.apply(Fixtures.creationBatch(key, traversalId)));
            await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(created.revision()))
                    .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.FAILED))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.FAILED))
                    .build()));
        }

        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = raw.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT termination_reason FROM process_instance WHERE tenant_id = '" + TENANT + "'")) {
            assertTrue(rows.next());
            assertNull(rows.getString("termination_reason"),
                    "an ordinary failure must leave the column NULL, not some sentinel value");
        }

        try (var store = openAt(file)) {
            StoredProcessInstance reloaded = await(store.load(key));
            assertEquals(ProcessInstanceStatus.FAILED, reloaded.state().status());
            assertNull(reloaded.state().terminationReason());
        }
    }

    private void cancelOneTraversal(SqliteExecutionStore store, ExecutionKey key, UUID traversalId) {
        StoredProcessInstance created = await(store.apply(Fixtures.creationBatch(key, traversalId)));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.FAILED,
                        ExecutionTerminationReason.CANCELLED))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.FAILED,
                        ExecutionTerminationReason.CANCELLED))
                .build()));
    }

    private SqliteExecutionStore openAt(Path file) {
        return new SqliteExecutionStore(file, Clock.fixed(NOW, ZoneOffset.UTC));
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
}
