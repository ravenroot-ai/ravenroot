package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.DurableHandler;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.HandlerPayloadSchema;
import ai.ravenroot.api.persistence.HandlerRegistration;
import ai.ravenroot.api.persistence.HandlerStatus;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upgrade path this feature actually creates: a database already at the version another feature
 * shipped, taken forward by the handler migration that landed behind it.
 *
 * <p>The handler migration was authored as 5 and became 6 when it merged behind the durable graph
 * definition store, which had taken 5. Renumbering a migration is not a rename — a database created
 * by the earlier branch is already at 5 and has already run <em>a different</em> migration under that
 * number, so what has to be proven is that the runner takes such a file to 6 by applying only the
 * handler step, and that the handler DDL therefore executes exactly once. Getting this wrong does not
 * fail loudly: {@code CREATE TABLE} without {@code IF NOT EXISTS} would abort the upgrade, and a
 * partial index created twice would abort it too, so the symptom is a database that refuses to open
 * after an upgrade — which is discovered by an operator, in production, at start-up.</p>
 */
class SqliteHandlerMigrationUpgradeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void aDatabaseLeftAtTheDefinitionStoreVersionUpgradesByApplyingOnlyTheHandlerStep(
            @TempDir Path directory) throws Exception {
        Path databaseFile = directory.resolve("upgrade.db");
        List<SchemaMigration> beforeHandlers = SqliteSchema.migrations().stream()
                .filter(migration -> migration.version() < handlerMigrationVersion())
                .toList();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            assertEquals(handlerMigrationVersion() - 1, SqliteSchema.migrate(connection, beforeHandlers, CLOCK),
                    "the file starts where the other feature's branch left it");
            assertFalse(tableExists(connection, "execution_handler"),
                    "no handler table exists yet, which is what makes the next step a real upgrade");
            assertTrue(tableExists(connection, "graph_definition"),
                    "and the earlier feature's own table is present, so this is its file and not an "
                            + "empty one that would upgrade trivially");
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            assertEquals(SqliteSchema.currentVersion(), SqliteSchema.migrate(connection, CLOCK));
            assertTrue(tableExists(connection, "execution_handler"));
            assertEquals(1, indexCount(connection, "execution_handler_live_correlation"),
                    "the partial index must be created exactly once; a second CREATE would abort the "
                            + "upgrade and leave the database unopenable");
            assertEquals(1, indexCount(connection, "execution_handler_deduplication"));
            assertEquals(1, historyRowsFor(connection, handlerMigrationVersion()),
                    "the handler step is recorded once in the schema history an operator reads");
            assertTrue(tableExists(connection, "graph_definition"),
                    "the earlier feature's table survives the step that follows it");
        }

        // Re-running is a no-op, which is what an ordinary restart does.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            assertEquals(SqliteSchema.currentVersion(), SqliteSchema.migrate(connection, CLOCK));
            assertEquals(1, indexCount(connection, "execution_handler_live_correlation"));
        }
    }

    /** And the upgraded file is not merely structurally correct: it can hold and answer a real wait. */
    @Test
    void anUpgradedDatabaseRegistersAndResolvesAHandler(@TempDir Path directory) throws Exception {
        Path databaseFile = directory.resolve("upgraded-then-used.db");
        List<SchemaMigration> beforeHandlers = SqliteSchema.migrations().stream()
                .filter(migration -> migration.version() < handlerMigrationVersion())
                .toList();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            SqliteSchema.migrate(connection, beforeHandlers, CLOCK);
        }

        var key = new ExecutionKey("acme", UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID handlerId = UUID.randomUUID();
        try (var store = new SqliteExecutionStore(databaseFile, CLOCK)) {
            StoredProcessInstance created = await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.notPresent())
                    .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(
                            key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                            Map.of(traversalId, new Traversal(traversalId, "start",
                                    TraversalStatus.ACCEPTED, Map.of()))),
                            new GraphVersionPin("graph-v1")))
                    .build()));
            await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(created.revision()))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                    .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                    .apply(new ExecutionTransition.InvocationAdded(traversalId,
                            new NodeInvocation(invocationId, "await-approval", Set.of(),
                                    NodeInvocationStatus.SCHEDULED, List.of())))
                    .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.WAITING))
                    .registerHandler(new HandlerRegistration(handlerId, "approval", traversalId,
                            invocationId, "invoice-42", "dedup-1",
                            new HandlerPayloadSchema("text/plain", "approval/v1", 1024),
                            HandlerAuthorization.ofRoles("APPROVER")))
                    .build()));

            DurableHandler waiting = await(store.findHandler("acme", "approval", "invoice-42"))
                    .orElseThrow();
            assertEquals(handlerId, waiting.handlerId());
            assertEquals(HandlerStatus.WAITING, waiting.status());
        }
    }

    private static int handlerMigrationVersion() {
        return SqliteSchema.migrations().stream()
                .filter(migration -> migration.statements().stream()
                        .anyMatch(statement -> statement.contains("CREATE TABLE execution_handler")))
                .map(SchemaMigration::version)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no migration creates the handler table"));
    }

    private static boolean tableExists(Connection connection, String name) throws SQLException {
        return objectCount(connection, "table", name) == 1;
    }

    private static int indexCount(Connection connection, String name) throws SQLException {
        return objectCount(connection, "index", name);
    }

    private static int objectCount(Connection connection, String type, String name) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = ? AND name = ?")) {
            statement.setString(1, type);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private static int historyRowsFor(Connection connection, int version) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM store_schema_history WHERE version = " + version)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
