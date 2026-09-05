package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.ExecutionTerminationReason;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cancelled execution stays distinguishable from a failed one across a restart, and the additive
 * column that makes it so behaves like an additive column.
 *
 * <h2>Why the status is the same on both sides of the distinction</h2>
 * <p>Every assertion here reads {@code FAILED} for a run that was cancelled, and that is the design
 * rather than the defect: the status vocabulary was deliberately not widened, because a new status
 * <em>name</em> is unreadable to any binary that predates it and would make the first cancelled row a
 * one-way gate on the two busiest tables in the schema. The distinction lives in a nullable column
 * beside the status, which an older binary simply does not select. So these tests always assert the
 * pair, never the status alone — asserting the status alone is exactly the misreading the column
 * exists to prevent.</p>
 *
 * <h2>The rollback gate that is deliberately absent, and the one that is not</h2>
 * <p>{@code SqliteParkedAttemptRollbackGateTest} documents the asymmetric hazard a new status name
 * creates: safe until the first row carries it, unsafe afterwards. This design has no such point,
 * which is the whole reason for it. What it does keep is the loud failure for a value from the
 * <em>future</em>: the reason is stored by name like every status, so a name this build does not know
 * must stop the reader rather than be read as "no reason" — silently turning a cancelled run back
 * into a failed one inside the very code that carries the distinction.</p>
 */
class SqliteTerminationReasonTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String TENANT = "acme";

    @TempDir
    Path databaseDirectory;

    @Test
    void aCancelledExecutionReadsBackAsCancelledAfterAReopenAndAFailedOneDoesNot() {
        Path file = databaseDirectory.resolve("distinguishable.db");
        var cancelledKey = new ExecutionKey(TENANT, UUID.randomUUID());
        var failedKey = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID cancelledTraversal = UUID.randomUUID();
        UUID failedTraversal = UUID.randomUUID();

        try (var store = openAt(file)) {
            terminate(store, cancelledKey, cancelledTraversal, ExecutionTerminationReason.CANCELLED);
            terminate(store, failedKey, failedTraversal, null);
        }

        // A different process, reading a file it did not write -- the only place this distinction has
        // ever had to survive, and the place it previously did not.
        try (var store = openAt(file)) {
            ProcessInstance cancelled = await(store.load(cancelledKey)).state();
            assertEquals(ProcessInstanceStatus.FAILED, cancelled.status(),
                    "the status is unchanged on purpose: widening the enum would make this row "
                            + "unreadable to every binary that predates the change");
            assertEquals(ExecutionTerminationReason.CANCELLED, cancelled.terminationReason(),
                    "and the reason beside it is the only thing that says this was not an incident");
            Traversal cancelledLeg = cancelled.traversals().get(cancelledTraversal);
            assertEquals(TraversalStatus.FAILED, cancelledLeg.status());
            assertEquals(ExecutionTerminationReason.CANCELLED, cancelledLeg.terminationReason(),
                    "the traversal carries it too: one instance can hold several traversals, so an "
                            + "instance-level answer could not say which of them was stopped");

            ProcessInstance failed = await(store.load(failedKey)).state();
            assertEquals(ProcessInstanceStatus.FAILED, failed.status());
            assertNull(failed.terminationReason(),
                    "an ordinary failure records no reason, which is what makes the cancelled row "
                            + "above a distinction rather than a value everything carries");
            assertNull(failed.traversals().get(failedTraversal).terminationReason());
        }
    }

    /**
     * A row written before the reason column existed, taken through the upgrade and read back.
     *
     * <p>The property is that it comes back <em>unstated</em>. The tempting alternative — backfilling
     * the column, or having the reader substitute a value for NULL — would write down a guess: a row
     * that ended before a reason could be recorded did not secretly carry one, and inferring "not
     * cancelled" from a {@code FAILED} status is precisely the inference that is wrong for a
     * cancellation. So the migration leaves it NULL and the reader reports absence.</p>
     *
     * <p>The legacy rows are inserted through raw SQL at the version immediately before the reason
     * migration, rather than simulated by nulling a column afterwards, because what is being tested is
     * the {@code ALTER} itself: that it adds the column without rewriting a single existing row.</p>
     */
    @Test
    void aRowWrittenBeforeTheReasonColumnExistedReadsBackWithNoReasonRatherThanAWrongOne()
            throws Exception {
        Path file = databaseDirectory.resolve("legacy-upgrade.db");
        int reasonVersion = terminationReasonMigration().version();
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();

        try (Connection connection = raw(file)) {
            assertEquals(reasonVersion - 1, SqliteSchema.migrate(connection,
                    SqliteSchema.migrations().stream()
                            .filter(migration -> migration.version() < reasonVersion).toList(), CLOCK),
                    "the file starts one step before the reason column exists");
            assertFalse(columnNames(connection, "process_instance").contains("termination_reason"));
            assertFalse(columnNames(connection, "traversal").contains("termination_reason"));
            insertLegacyTerminatedExecution(connection, key, traversalId);
        }

        try (Connection connection = raw(file)) {
            assertEquals(SqliteSchema.highestKnownVersion(), SqliteSchema.migrate(connection, CLOCK));
            assertTrue(columnNames(connection, "process_instance").contains("termination_reason"));
            assertTrue(columnNames(connection, "traversal").contains("termination_reason"));
            assertNull(storedReason(connection, "process_instance"),
                    "the ALTER adds the column and rewrites nothing; an old row is left unstated "
                            + "rather than backfilled with a value nobody recorded");
            assertNull(storedReason(connection, "traversal"));
        }

        // And through the port, which is where a wrong answer would actually be acted on.
        try (var store = openAt(file)) {
            ProcessInstance legacy = await(store.load(key)).state();
            assertEquals(ProcessInstanceStatus.FAILED, legacy.status());
            assertNull(legacy.terminationReason());
            assertNull(legacy.traversals().get(traversalId).terminationReason());
        }
    }

    /**
     * A reason name from a newer build stops the reader, on both tables that carry one.
     *
     * <p>The same shape {@code SqliteParkedAttemptRollbackGateTest} uses, and for the same reason: a
     * pre-change binary cannot be run here, so the equivalent is asserted directly by editing a value
     * out of band. The consequence being pinned is specific to this column — reading an unknown name
     * as an absent reason would not merely lose information, it would restore the exact defect the
     * column was added to remove, and would do it silently inside the code carrying the fix.</p>
     */
    @Test
    void anUnrecognisedTerminationReasonNameIsReportedAsCorruptedRatherThanMisread() throws Exception {
        for (String table : List.of("process_instance", "traversal")) {
            Path file = databaseDirectory.resolve("future-reason-" + table + ".db");
            var key = new ExecutionKey(TENANT, UUID.randomUUID());
            UUID traversalId = UUID.randomUUID();

            try (var store = openAt(file)) {
                terminate(store, key, traversalId, ExecutionTerminationReason.CANCELLED);
            }

            try (Connection connection = raw(file);
                 Statement statement = connection.createStatement()) {
                assertEquals("CANCELLED", storedReason(connection, table),
                        "the reason is stored as a name, which is what lets the vocabulary grow "
                                + "without a data migration -- and what creates this hazard");
                assertEquals(1, statement.executeUpdate("UPDATE " + table
                        + " SET termination_reason = 'FROM_THE_FUTURE' WHERE tenant_id = '" + TENANT + "'"));
            }

            try (var store = openAt(file)) {
                ExecutionStoreFailure failure = failureOf(() -> await(store.load(key)));
                var corrupted = assertInstanceOf(ExecutionStoreFailure.Corrupted.class, failure,
                        "an unreadable reason must stop the reader on " + table + "; folding it into "
                                + "an absent reason would report a cancelled run as a failed one");
                assertEquals(key.processInstanceId(), corrupted.key().processInstanceId());
            }
        }
    }

    /**
     * The migration is identified by what it does, never by the number it currently holds.
     *
     * <p>A literal version number in a test is a merge conflict waiting to become a false failure:
     * this schema has already had a migration renumbered when another feature landed first, and a
     * test pinned to the old number would fail on a change that is entirely correct. What must hold
     * after any renumber is structural — the step is additive, it touches both tables, it follows the
     * schema it extends, and the sequence stays dense.</p>
     */
    @Test
    void theTerminationReasonMigrationIsAdditiveOnBothTablesAndKeepsTheSequenceDense() {
        SchemaMigration migration = terminationReasonMigration();
        assertTrue(migration.version() > 1,
                "it extends the initial schema, so it cannot be the initial schema");
        assertEquals(List.of("ALTER TABLE process_instance ADD COLUMN termination_reason TEXT",
                        "ALTER TABLE traversal ADD COLUMN termination_reason TEXT"),
                migration.statements(),
                "purely additive and nullable: no row is rewritten, no status is touched, and no "
                        + "column is made NOT NULL -- which is what keeps a downgrade safe forever "
                        + "rather than only until the first cancelled row is written");
        assertEquals(IntStream.rangeClosed(1, SqliteSchema.highestKnownVersion()).boxed().toList(),
                SqliteSchema.migrations().stream().map(SchemaMigration::version).sorted().toList(),
                "versions must stay 1..n with no gaps and no duplicates; the downgrade guard "
                        + "compares integers and nothing else");
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

    /** Drives a freshly created instance straight to its terminal state, with or without a reason. */
    private void terminate(SqliteExecutionStore store, ExecutionKey key, UUID traversalId,
                           ExecutionTerminationReason reason) {
        StoredProcessInstance created = await(store.apply(Fixtures.creationBatch(key, traversalId)));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.FAILED,
                        reason))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.FAILED, reason))
                .build()));
    }

    /**
     * A terminated instance as a build without the reason column would have written it.
     *
     * <p>Only the columns the initial schema declared NOT NULL are named. Every later addition to
     * either table carries a default or is nullable, so this insert is valid at any version from the
     * first onward and does not have to be revisited when another additive migration lands.</p>
     */
    private static void insertLegacyTerminatedExecution(Connection connection, ExecutionKey key,
                                                        UUID traversalId) throws SQLException {
        try (var instance = connection.prepareStatement("INSERT INTO process_instance (tenant_id, "
                + "process_instance_id, status, graph_version_pin, revision, fencing_token, "
                + "updated_at_epoch_second, updated_at_nano) VALUES (?, ?, 'FAILED', 'graph-v1', 3, 0, ?, 0)")) {
            instance.setString(1, key.tenantId());
            instance.setString(2, key.processInstanceId().toString());
            instance.setLong(3, NOW.getEpochSecond());
            instance.executeUpdate();
        }
        try (var traversal = connection.prepareStatement("INSERT INTO traversal (tenant_id, "
                + "process_instance_id, traversal_id, position, ingress_node_id, status) "
                + "VALUES (?, ?, ?, 0, 'start', 'FAILED')")) {
            traversal.setString(1, key.tenantId());
            traversal.setString(2, key.processInstanceId().toString());
            traversal.setString(3, traversalId.toString());
            traversal.executeUpdate();
        }
    }

    private static String storedReason(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT termination_reason FROM " + table
                     + " WHERE tenant_id = '" + TENANT + "'")) {
            assertTrue(rows.next(), "no " + table + " row to read");
            return rows.getString("termination_reason");
        }
    }

    private static List<String> columnNames(Connection connection, String table) throws SQLException {
        var names = new java.util.ArrayList<String>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                names.add(rows.getString("name"));
            }
        }
        return names;
    }

    private static Connection raw(Path file) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
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
