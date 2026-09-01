package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
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
 * The one-way rollback gate that {@code NodeAttemptStatus.PARKED} creates (PERS-04, ADR 0022).
 *
 * <p>Statuses are persisted by <em>name</em>, so the enlargement needs no data migration: every
 * pre-PERS-04 row folds exactly as it did before and the new name only ever appears in rows written
 * after the change. What it does create is a downgrade hazard, and it is asymmetric — safe until the
 * first parked row exists, unsafe after it.</p>
 *
 * <p>This test exists because "a pre-change binary would fail to read it" is a claim, and an
 * unverified claim about a failure mode is exactly the kind of thing that gets discovered during a
 * rollback at three in the morning. A pre-PERS-04 binary cannot be run here, so the equivalent is
 * asserted directly: an attempt status name this binary does not know surfaces as
 * {@link ExecutionStoreFailure.Corrupted}, which is what {@code PARKED} looks like to an older
 * {@code NodeAttemptStatus.valueOf}. The failure is loud and classified, never a silent misread.</p>
 *
 * <p>It lives in the adapter's own suite rather than in the shared conformance suite because it needs
 * a row edited out of band, which only a durable adapter has — the same treatment ADR 0010
 * section 12.4 blesses for {@code Corrupted}, and the same one {@code SqliteJournalIntegrityTest}
 * already uses.</p>
 */
class SqliteParkedAttemptRollbackGateTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String TENANT = "acme";

    @TempDir
    Path databaseDirectory;

    @Test
    void anAttemptStatusNameThisBinaryDoesNotKnowIsReportedAsCorruptedRatherThanMisread() throws Exception {
        Path file = databaseDirectory.resolve("rollback-gate.db");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        try (var store = openAt(file)) {
            parkOneAttempt(store, key, traversalId, invocationId, attemptId);
            NodeAttempt parked = onlyAttempt(await(store.load(key)));
            assertEquals(NodeAttemptStatus.PARKED, parked.status());
            assertEquals("dispatched with unknown outcome", parked.parkCause());
        }

        // The row as a *newer* binary wrote it, seen through the eyes of an *older* one: a status name
        // that is not a member of its enum. 'PARKED' is precisely this to any pre-PERS-04 build.
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = raw.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT status, park_cause FROM attempt WHERE tenant_id = '" + TENANT + "'")) {
                assertTrue(rows.next());
                assertEquals("PARKED", rows.getString("status"),
                        "the status is stored as a name, which is why no data migration is needed");
                assertNotNull(rows.getString("park_cause"));
            }
            assertEquals(1, statement.executeUpdate("UPDATE attempt SET status = 'FROM_THE_FUTURE' "
                    + "WHERE tenant_id = '" + TENANT + "'"));
        }

        try (var store = openAt(file)) {
            ExecutionStoreFailure failure = failureOf(() -> await(store.load(key)));
            var corrupted = assertInstanceOf(ExecutionStoreFailure.Corrupted.class, failure,
                    "an unreadable status must stop the reader, not be folded into a legal-looking "
                            + "aggregate; a downgrade past the first parked row fails loudly by design");
            assertEquals(key.processInstanceId(), corrupted.key().processInstanceId());
        }
    }

    @Test
    void aParkedRowSurvivesAReopenAndResolvesToAnOperatorVerifiedCompletion() {
        Path file = databaseDirectory.resolve("resolution.db");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        try (var store = openAt(file)) {
            parkOneAttempt(store, key, traversalId, invocationId, attemptId);
        }

        try (var store = openAt(file)) {
            StoredProcessInstance reloaded = await(store.load(key));
            assertEquals(NodeAttemptStatus.PARKED, onlyAttempt(reloaded).status());
            assertEquals("dispatched with unknown outcome", onlyAttempt(reloaded).parkCause());

            await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(reloaded.revision()))
                    .apply(new ExecutionTransition.ParkResolvedCompleted(traversalId, invocationId, attemptId))
                    .build()));
        }

        try (var store = openAt(file)) {
            NodeAttempt resolved = onlyAttempt(await(store.load(key)));
            assertEquals(NodeAttemptStatus.COMPLETED, resolved.status());
            assertEquals(ai.ravenroot.api.application.NodeAttemptCompletion.OPERATOR_VERIFIED,
                    resolved.completion());
            assertNull(resolved.parkCause(), "the cause is cleared with the park, not left to mislead");
        }
    }

    private void parkOneAttempt(SqliteExecutionStore store, ExecutionKey key, UUID traversalId,
                                UUID invocationId, UUID attemptId) {
        StoredProcessInstance created = await(store.apply(Fixtures.creationBatch(key, traversalId)));
        StoredProcessInstance scheduled = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(traversalId,
                        new NodeInvocation(invocationId, "mail.send", null, NodeInvocationStatus.SCHEDULED)))
                .apply(new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                        NodeInvocationStatus.RUNNING))
                .apply(new ExecutionTransition.AttemptAdded(traversalId, invocationId,
                        new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED)))
                .build()));
        StoredProcessInstance running = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(scheduled.revision()))
                .apply(new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, attemptId,
                        NodeAttemptStatus.RUNNING))
                .build()));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(running.revision()))
                .apply(new ExecutionTransition.AttemptParked(traversalId, invocationId, attemptId,
                        "dispatched with unknown outcome"))
                .build()));
    }

    private static NodeAttempt onlyAttempt(StoredProcessInstance stored) {
        return stored.state().traversals().values().iterator().next()
                .invocations().values().iterator().next().attempts().getLast();
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
