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
import java.util.List;
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
 * <p><b>There is a rollback gate, and it is the schema version.</b> An earlier version of this
 * paragraph claimed the opposite -- that a nullable column beside an unchanged status creates no
 * downgrade hazard, because an older binary "simply does not {@code SELECT} the column" -- and used
 * that claim as the reason not to write a test. The claim is false against this store's own
 * {@link SqliteSchema#migrate} guard, which refuses a database whose recorded {@code user_version}
 * exceeds what the binary understands <em>before</em> it reads a row. Every migration in this schema
 * raises that version, so every migration is a one-way binary gate, this one included, exactly as
 * migration 6's own comment already states. The gate is asserted directly in
 * {@link #aBinaryPredatingTheTerminationReasonMigrationIsRefusedAtOpen()} rather than reasoned about
 * in prose.</p>
 *
 * <p>What that gate is <em>not</em> is the hazard {@link SqliteParkedAttemptRollbackGateTest}
 * describes, and the difference is worth keeping straight. A new status <em>name</em> needs no
 * migration at all, so it raises no version and stops no binary from opening the file; it fails
 * later, per aggregate, on the first row that carries the unrecognised name. The schema gate here is
 * earlier, total and data-independent. Both are one-way; they sit at different levels.</p>
 *
 * <p>The property this class asserts is the narrower one {@code SqliteExecutionStore}'s own
 * {@code terminationReasonOf} javadoc states, and it survives either way: a stored <em>name</em>
 * this build does not recognise -- whatever binary, migration path or hand edit put it there -- must
 * be refused as {@link ExecutionStoreFailure.Corrupted}, never silently folded into an absent
 * reason. Reading an unknown name as "unstated" would report a cancelled run as an ordinary failure,
 * which is the exact misreading this column exists to prevent, reintroduced by the very code meant
 * to carry it.</p>
 */
class SqliteTerminationReasonCorruptionGateTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
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

    /**
     * The gate the false claim denied: a binary that predates this migration cannot open the file.
     *
     * <p>A build that predates the termination reason is simulated the way
     * {@code SqliteSchemaMigrationTest.aDatabaseNewerThanTheCodeIsRefusedRatherThanOpened} simulates
     * one -- by handing {@link SqliteSchema#migrate} the migration list that build would have
     * carried. The refusal is the store's ordinary downgrade guard, and three properties of it are
     * what matter here.</p>
     *
     * <p>It is <b>total</b>: the failure is at open, not on a particular read, so nothing in the
     * file is reachable afterwards. It is <b>data-independent</b>: this database contains no
     * cancelled execution, and not a single {@code termination_reason} is set, yet the older build is
     * refused all the same -- which is precisely what distinguishes a schema-version gate from the
     * per-row name gate in {@link SqliteParkedAttemptRollbackGateTest}. And it is <b>clean</b>: a
     * refused open must not rewrite the version, or a failed downgrade would corrupt the very thing
     * an operator needs in order to roll forward again.</p>
     */
    @Test
    void aBinaryPredatingTheTerminationReasonMigrationIsRefusedAtOpen() throws Exception {
        Path file = databaseDirectory.resolve("downgrade-gate.db");
        int reasonVersion = terminationReasonMigration().version();
        List<SchemaMigration> beforeTheReason = SqliteSchema.migrations().stream()
                .filter(migration -> migration.version() < reasonVersion)
                .toList();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
            assertEquals(SqliteSchema.currentVersion(), SqliteSchema.migrate(connection, CLOCK));
            assertNoCancellationAnywhere(connection);
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> SqliteSchema.migrate(connection, beforeTheReason, CLOCK),
                    "a build that predates this migration must be refused at open; the column being "
                            + "nullable does not make it invisible, because the guard compares "
                            + "schema versions and never reaches a SELECT");
            assertTrue(refused.getMessage().contains("newer than this build understands"),
                    refused.getMessage());
            assertEquals(SqliteSchema.currentVersion(), SqliteSchema.versionOf(connection),
                    "a refused open must leave the version exactly as it found it");
        }

        // And the file is still fully usable by a current build afterwards, which is what makes the
        // refusal a gate rather than damage.
        try (var store = openAt(file)) {
            var key = new ExecutionKey(TENANT, UUID.randomUUID());
            UUID traversalId = UUID.randomUUID();
            cancelOneTraversal(store, key, traversalId);
            assertEquals(ExecutionTerminationReason.CANCELLED,
                    await(store.load(key)).state().terminationReason());
        }
    }

    /**
     * Proves the premise of the test above: the refusal owes nothing to the data. If any row already
     * carried a reason, the gate being total would say nothing about a database that had never
     * recorded a cancellation.
     */
    private static void assertNoCancellationAnywhere(Connection connection) throws Exception {
        for (String table : List.of("process_instance", "traversal")) {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT COUNT(*) AS carried FROM " + table
                                 + " WHERE termination_reason IS NOT NULL")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt("carried"),
                        table + " must carry no termination reason, so the refusal below cannot be "
                                + "attributed to the presence of a cancelled row");
            }
        }
    }

    private static SchemaMigration terminationReasonMigration() {
        return SqliteSchema.migrations().stream()
                .filter(candidate -> candidate.statements().stream()
                        .anyMatch(statement -> statement.contains("termination_reason")))
                .reduce((first, second) -> {
                    throw new AssertionError("more than one migration adds a termination reason");
                })
                .orElseThrow(() -> new AssertionError("no migration adds a termination reason"));
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
        return new SqliteExecutionStore(file, CLOCK);
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
