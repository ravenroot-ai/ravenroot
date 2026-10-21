package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.history.HistoryPage;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.security.*;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Populated previous schema, shipped migration, retained pending work, and actual backup rollback. */
class SqliteBusinessHistoryMigrationUpgradeTest {

    @Test void populated40UpgradeReopenAndPreUpgradeBackupRestore(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        Path database = directory.resolve("old40.db"), backup = directory.resolve("old40.backup.db"), restored = directory.resolve("restored40.db");
        var old = SqliteSchema.migrations().stream().filter(step -> step.version() <= 40).toList();
        Map<String, List<String>> before;
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            assertEquals(40, SqliteSchema.migrate(connection, old, CLOCK)); populateOld(connection); before = snapshot(connection);
            try (var sql = connection.createStatement()) { sql.execute("VACUUM INTO '" + backup.toString().replace("'", "''") + "'"); }
        }
        var custody = newCustody();
        try (var store = new SqliteExecutionStore(database, CLOCK)) {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) { assertOldRows(before, connection); }
            verifyUpgraded(store); store.acceptDeploymentEntry(custody, CorrelationMapping.Mode.ACCEPT_OR_CREATE).toCompletableFuture().get();
        }
        try (var store = new SqliteExecutionStore(database, CLOCK)) { verifyCustody(store, custody); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            assertEquals(41, SqliteSchema.migrate(connection, CLOCK));
            assertThrows(IllegalStateException.class, () -> SqliteSchema.migrate(connection, old, CLOCK));
            try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT COUNT(*) FROM store_schema_history WHERE version=41")) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
        }
        Files.copy(backup, restored);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + restored)) {
            assertEquals(40, SqliteSchema.migrate(connection, old, CLOCK)); assertEquals(before, snapshot(connection));
            assertEquals(legacyState(), AggregateStorage.read(connection, KEY, ProcessInstanceStatus.RUNNING, null, ProcessControlState.RUNNING));
        }
    }

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID PROCESS = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TRAVERSAL = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VISIT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ATTEMPT = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final ExecutionKey KEY = new ExecutionKey("tenant", PROCESS);
    private static final BusinessScope SCOPE = new BusinessScope("tenant", "deployment", "version");
    private static final byte[] ORIGINAL_INPUT = "{\"operation\":\"old-input\",\"part\":2}".getBytes(StandardCharsets.UTF_8);

    private static ProcessInstance legacyState() {
        var attempt = new NodeAttempt(ATTEMPT, 1, NodeAttemptStatus.SCHEDULED);
        var visit = new NodeInvocation(VISIT, "worker", Set.of(), NodeInvocationStatus.SCHEDULED,
                List.of(attempt), ai.ravenroot.api.execution.NodeCommand.application("process-result"));
        return new ProcessInstance(PROCESS, ProcessInstanceStatus.RUNNING,
                Map.of(TRAVERSAL, new Traversal(TRAVERSAL, "source", TraversalStatus.RUNNING, Map.of(VISIT, visit))));
    }
    private static void populateOld(Connection connection) throws Exception {
        try (var sql = connection.prepareStatement("INSERT INTO process_instance (tenant_id, process_instance_id, status, graph_version_pin, revision, fencing_token, updated_at_epoch_second, updated_at_nano, deployment_id, control_state) VALUES (?, ?, 'RUNNING', 'version', 7, 4, ?, 0, 'deployment', 'RUNNING')")) {
            sql.setString(1, "tenant"); sql.setString(2, PROCESS.toString()); sql.setLong(3, CLOCK.instant().getEpochSecond()); sql.executeUpdate();
        }
        AggregateStorage.write(connection, KEY, legacyState());
        try (var sql = connection.prepareStatement("INSERT INTO timer (tenant_id, process_instance_id, timer_id, traversal_id, invocation_id, payload_content_type, payload_bytes, due_at_epoch_second, due_at_nano) VALUES (?, ?, ?, ?, ?, 'application/json', ?, ?, 0)")) {
            sql.setString(1, "tenant"); sql.setString(2, PROCESS.toString()); sql.setString(3, UUID.randomUUID().toString());
            sql.setString(4, TRAVERSAL.toString()); sql.setString(5, VISIT.toString()); sql.setBytes(6, ORIGINAL_INPUT);
            sql.setLong(7, CLOCK.instant().minusSeconds(1).getEpochSecond()); sql.executeUpdate();
        }
        try (var sql = connection.prepareStatement("INSERT INTO idempotency_record (tenant_id, idempotency_key, request_fingerprint_content_type, request_fingerprint_bytes, outcome_ref_content_type, outcome_ref_bytes, recorded_at_revision, expires_at_epoch_second, expires_at_nano) VALUES ('tenant', 'source-receipt', 'application/octet-stream', ?, 'application/json', ?, 7, ?, 0)")) {
            sql.setBytes(1, new byte[]{1, 2, 3}); sql.setBytes(2, ORIGINAL_INPUT); sql.setLong(3, CLOCK.instant().plusSeconds(86400).getEpochSecond()); sql.executeUpdate();
        }
        try (var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO outbox_cursor (tenant_id, destination, delivered_through) VALUES ('tenant', 'deployment/source', 19)");
        }
    }
    private static DeploymentEntryIntent newCustody() {
        var identity = new SecurityContext("request", "tenant", "source", PrincipalType.WORKLOAD, "test");
        var context = new ApplicationCorrelationContext("new-ingestion", Optional.of("part-2"), Optional.of("silver"), "round-1", Optional.of("sales.csv"));
        return new DeploymentEntryIntent(SCOPE, UUID.randomUUID(), UUID.randomUUID(), "source", context, identity,
                OpaquePayload.of(ORIGINAL_INPUT, "application/json"), CLOCK.instant(), CLOCK.instant().plusSeconds(3600),
                CorrelatedTraversal.Cause.SOURCE_EVENT, null, "", null);
    }
    private static void verifyUpgraded(ExecutionStore store) throws Exception {
        assertEquals(legacyState(), store.load(KEY).toCompletableFuture().get().state());
        assertTrue(store.correlatedTraversal("tenant", PROCESS, TRAVERSAL).toCompletableFuture().get().isEmpty());
        assertTrue(store.deploymentEntry(KEY).toCompletableFuture().get().isEmpty(), "old absent input custody must not be fabricated from a receipt");
        assertEquals(List.of(TRAVERSAL), store.uncorrelatedDeploymentTraversals(SCOPE, Optional.empty(), 10).toCompletableFuture().get().items().stream().map(value -> value.traversalId()).toList());
        assertEquals(HistoryPage.Availability.NOT_RECORDED, store.executionEdgeSummaries(KEY, TRAVERSAL, Optional.empty(), 10).toCompletableFuture().get().availability());
        assertEquals(19, store.outboxCursor("tenant", "deployment/source").toCompletableFuture().get().deliveredThrough());
        var work = store.claimPendingWork("tenant", "recovery", 10, Duration.ofMinutes(1)).toCompletableFuture().get();
        var attempt = work.stream().filter(PendingWork.AttemptDispatch.class::isInstance).map(PendingWork.AttemptDispatch.class::cast).findFirst().orElseThrow();
        assertEquals(TRAVERSAL, attempt.traversalId()); assertEquals(VISIT, attempt.invocationId()); assertEquals(ATTEMPT, attempt.attemptId());
        assertEquals(ai.ravenroot.api.execution.NodeCommand.application("process-result"), attempt.command());
        var timers = work.stream().filter(PendingWork.TimerDue.class::isInstance).map(PendingWork.TimerDue.class::cast).toList();
        assertEquals(1, timers.size()); assertArrayEquals(ORIGINAL_INPUT, timers.getFirst().payload().bytes());
    }
    private static void verifyCustody(ExecutionStore store, DeploymentEntryIntent original) throws Exception {
        var restored = store.deploymentEntry(original.key()).toCompletableFuture().get().orElseThrow();
        assertEquals(original.context(), restored.context()); assertEquals(original.entryNodeId(), restored.entryNodeId());
        assertEquals(original.identity(), restored.identity()); assertArrayEquals(original.input().bytes(), restored.input().bytes());
        assertEquals(original.acceptedAt(), restored.acceptedAt()); assertEquals(original.expiresAt(), restored.expiresAt());
        assertEquals(original, store.acceptDeploymentEntry(original, CorrelationMapping.Mode.ACCEPT_OR_CREATE).toCompletableFuture().get().intent().orElseThrow());
        assertEquals(List.of(original.processInstanceId()), store.pendingDeploymentEntries(SCOPE, Optional.empty(), 10).toCompletableFuture().get().items().stream().map(DeploymentEntrySummary::processInstanceId).toList());
    }
    private static Map<String, List<String>> snapshot(Connection connection) throws Exception {
        var names = new ArrayList<String>();
        try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) { while (rows.next()) names.add(rows.getString(1)); }
        var result = new TreeMap<String, List<String>>();
        for (String name : names) {
            if (name.equals("store_schema_history") || name.equals("store_schema_version")) continue;
            assertTrue(name.matches("[a-z0-9_]+")); var values = new ArrayList<String>();
            try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT * FROM " + name)) {
                while (rows.next()) {
                    var row = new ArrayList<String>();
                    for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                        Object value = rows.getObject(i); row.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : String.valueOf(value));
                    }
                    values.add(row.toString());
                }
            }
            Collections.sort(values); result.put(name, List.copyOf(values));
        }
        assertTrue(result.size() > 40); return result;
    }
    private static void assertOldRows(Map<String, List<String>> before, Connection connection) throws Exception {
        var after = snapshot(connection);
        for (var table : before.entrySet()) assertEquals(table.getValue(), after.get(table.getKey()), table.getKey());
    }
}
