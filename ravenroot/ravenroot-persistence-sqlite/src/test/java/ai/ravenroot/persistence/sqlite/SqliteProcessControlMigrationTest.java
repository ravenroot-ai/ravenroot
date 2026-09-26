package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SqliteProcessControlMigrationTest {
    @TempDir Path directory;
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void legacyControlIsRecoveredOrExplicitlyHeldAndNewWritesNeverDependOnTheJournal() throws Exception {
        Path database = directory.resolve("control.db");
        java.util.function.Supplier<SqliteExecutionStore> open = () -> new SqliteExecutionStore(database, CLOCK);
        var expected = new LinkedHashMap<ExecutionKey, ProcessControlState>();
        var before = new LinkedHashMap<ExecutionKey, ProcessInventoryEntry>();
        try (var store = open.get()) {
            for (String tenant : List.of("pause", "stop", "latest", "missing", "clean", "cancel")) {
                var key = new ExecutionKey(tenant, UUID.randomUUID());
                var batch = ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                        .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                                ProcessInstanceStatus.RUNNING, Map.of()), new GraphVersionPin("graph")));
                if (!tenant.equals("clean")) batch.publish(event(key, "PROCESS_PAUSE"));
                if (tenant.equals("stop") || tenant.equals("latest")) batch.publish(event(key, "PROCESS_STOP"));
                if (tenant.equals("cancel")) batch.apply(new ExecutionTransition.ProcessTransitioned(
                        ProcessInstanceStatus.FAILED, ExecutionTerminationReason.CANCELLED));
                store.apply(batch.build()).toCompletableFuture().join();
                before.put(key, store.findProcessInstance(key).toCompletableFuture().join().orElseThrow());
                expected.put(key, switch (tenant) {
                    case "pause" -> ProcessControlState.PAUSED;
                    case "stop", "latest" -> ProcessControlState.STOPPED;
                    case "missing" -> ProcessControlState.RECOVERY_REQUIRED;
                    case "cancel" -> ProcessControlState.CANCELLED;
                    default -> ProcessControlState.RUNNING;
                });
            }
        }
        // Restore the exact pre-control row shape. Journal bytes and all other tables are unchanged.
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
            statement.execute("DROP INDEX idx_process_instance_deployment_incarnation");
            statement.execute("ALTER TABLE process_instance DROP COLUMN deployment_incarnation_id");
            statement.execute("ALTER TABLE process_instance DROP COLUMN control_state");
            statement.execute("DROP TABLE runner_fleet_guard");
            statement.execute("DROP TABLE runner_availability");
            statement.execute("DROP TABLE runner_retention_guard");
            statement.execute("DROP TABLE human_task_interaction_revocation");
            statement.execute("DROP TABLE deployment_identity_binding");
            statement.execute("ALTER TABLE human_task DROP COLUMN presentation_schema_digest");
            statement.execute("ALTER TABLE human_task DROP COLUMN presentation_form_schema");
            statement.execute("ALTER TABLE human_task DROP COLUMN presentation_profile_version");
            statement.execute("ALTER TABLE human_task DROP COLUMN presentation_profile_id");
            statement.execute("ALTER TABLE human_task DROP COLUMN presentation_version");
            statement.execute("ALTER TABLE human_task DROP COLUMN presentation_kind");
            statement.execute("DELETE FROM store_schema_history WHERE version >= 29");
            statement.execute("PRAGMA user_version = 28");
            statement.execute("DELETE FROM event_journal WHERE tenant_id = 'missing' OR (tenant_id = 'latest' AND journal_offset = 1)");
            statement.execute("UPDATE journal_watermark SET retained_from = next_offset WHERE tenant_id = 'missing'");
            statement.execute("UPDATE journal_watermark SET retained_from = 2 WHERE tenant_id = 'latest'");
        }
        for (int restart = 0; restart < 2; restart++) try (var store = open.get()) {
            for (var item : expected.entrySet()) {
                var stored = store.load(item.getKey()).toCompletableFuture().join();
                assertEquals(item.getValue(), stored.state().controlState());
                assertEquals(before.get(item.getKey()),
                        store.findProcessInstance(item.getKey()).toCompletableFuture().join().orElseThrow(),
                        "backfill preserves every pre-existing inventory field, including revision and retention");
                if (item.getKey().tenantId().equals("cancel")) {
                    assertEquals(ProcessInstanceStatus.FAILED, stored.state().status());
                    assertEquals(ExecutionTerminationReason.CANCELLED, stored.state().terminationReason());
                }
            }
        }
        try (var store = open.get()) {
            var uncertain = expected.keySet().stream().filter(key -> key.tenantId().equals("missing")).findFirst().orElseThrow();
            var old = store.load(uncertain).toCompletableFuture().join();
            var resolved = store.apply(ExecutionBatch.to(uncertain).expecting(RevisionExpectation.exactly(old.revision()))
                    .apply(new ExecutionTransition.ProcessControlChanged(ProcessControlState.RUNNING)).build()).toCompletableFuture().join();
            assertEquals(ProcessControlState.RUNNING, resolved.state().controlState());
            var fresh = new ExecutionKey("missing", UUID.randomUUID());
            var created = store.apply(ExecutionBatch.to(fresh).expecting(RevisionExpectation.notPresent())
                    .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(fresh.processInstanceId(),
                            ProcessInstanceStatus.RUNNING, Map.of()), new GraphVersionPin("graph"))).build()).toCompletableFuture().join();
            assertEquals(ProcessControlState.RUNNING, created.state().controlState());
        }
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
            statement.execute("UPDATE process_instance SET control_state = 'UNRECOGNIZED' WHERE tenant_id = 'pause'");
        }
        try (var store = open.get()) {
            var key = expected.keySet().stream().filter(value -> value.tenantId().equals("pause")).findFirst().orElseThrow();
            var failure = assertThrows(java.util.concurrent.CompletionException.class,
                    () -> store.load(key).toCompletableFuture().join());
            assertInstanceOf(ExecutionStoreFailure.Corrupted.class,
                    ((ExecutionStoreException) failure.getCause()).failure());
        }
    }

    static EventEnvelope event(ExecutionKey key, String type) {
        return EventEnvelope.of(UUID.randomUUID(), key.tenantId(), type, key.processInstanceId(),
                key.processInstanceId(), null, null, null, "migration", "graph", CLOCK.instant(),
                OpaquePayload.empty("application/json"));
    }
}
