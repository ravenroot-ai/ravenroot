package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual stored-row corruption, not a mock of the central decoder. */
class HistoryStoredUuidTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final BusinessScope SCOPE = new BusinessScope("tenant", "deployment", "version");
    private static final BusinessHistoryPolicy POLICY = BusinessHistoryPolicy.DEFAULTS;
    private static final String HOSTILE = "not-a-uuid secret=do-not-log";
    private Connection open() throws Exception { return PostgresTestDatabase.dataSourceFor("history-corruption-" + UUID.randomUUID()).getConnection(); }

    @ParameterizedTest
    @CsvSource({
        "correlated_traversal,process_id", "correlated_traversal,traversal_id",
        "correlated_traversal,derived_process_id", "correlated_traversal,derived_traversal_id",
        "correlated_traversal,derived_invocation_id", "correlated_traversal,derived_attempt_id",
        "business_history,closure_process_id", "business_history,closure_traversal_id",
        "business_history,closure_invocation_id", "business_history,closure_attempt_id",
        "correlated_join_member,process_id", "correlated_join_member,traversal_id",
        "correlated_join_member,invocation_id", "correlated_join_member,attempt_id",
        "deployment_entry_intent,process_id", "deployment_entry_intent,traversal_id"
    })
    void scalarHistoryReadersReportSanitizedTypedCorruption(String table, String column) throws Exception {
        try (var db = open()) {
            PostgresSchema.migrate(db, CLOCK);
            UUID process = UUID.randomUUID(), traversal = UUID.randomUUID();
            var context = new ApplicationCorrelationContext("business", Optional.empty(), Optional.empty(), "round", Optional.empty());
            BusinessHistoryStorage.admit(db, new CorrelatedTraversal(SCOPE, context, process, traversal, "source",
                    CorrelatedTraversal.Cause.SOURCE_EVENT, TraversalStatus.ACCEPTED, null, NOW, NOW, null),
                    CorrelationMapping.Mode.ACCEPT_OR_CREATE, POLICY, NOW);
            try (var sql = db.createStatement()) {
                String id = process.toString();
                if (table.equals("business_history")) sql.executeUpdate("UPDATE business_history SET requested_closure='COMPLETED', closure_reason='reviewed', "
                        + "closure_process_id='" + id + "', closure_traversal_id='" + id + "', closure_invocation_id='" + id
                        + "', closure_attempt_id='" + id + "', closure_requested_ms=0");
                if (column.startsWith("derived_")) sql.executeUpdate("UPDATE correlated_traversal SET cause='DERIVED_CONTEXT', derivation_reason='reviewed', "
                        + "derived_process_id='" + id + "', derived_traversal_id='" + id + "', derived_invocation_id='" + id + "', derived_attempt_id='" + id + "'");
                if (table.equals("correlated_join_member")) {
                    sql.executeUpdate("INSERT INTO correlated_business_join (tenant_id,deployment_id,graph_version,application_id,node_id,round_id,revision,phase,expected_count,received_count,sealed,retained_bytes,opened_ms,updated_ms,deadline_ms,continuation_process_id,continuation_traversal_id) "
                            + "VALUES ('tenant','deployment','version','business','join','round',1,'OPEN',2,1,0,0,0,0,1,'" + id + "','" + id + "')");
                    sql.executeUpdate("INSERT INTO correlated_join_member (tenant_id,deployment_id,graph_version,application_id,node_id,round_id,member_id,expected_member,received_member,kind,process_id,traversal_id,invocation_id,attempt_id,accepted_epoch_second,accepted_nano) "
                            + "VALUES ('tenant','deployment','version','business','join','round','member',1,1,'partition','" + id + "','" + id + "','" + id + "','" + id + "',0,0)");
                }
            }
            String corruptedTable = table.equals("deployment_entry_intent") ? "correlated_traversal" : table;
            try (var corrupt = db.prepareStatement("UPDATE " + corruptedTable + " SET " + column + " = ?")) {
                corrupt.setString(1, HOSTILE); assertEquals(1, corrupt.executeUpdate());
            }
            if (table.equals("deployment_entry_intent")) try (var insert = db.prepareStatement(
                    "INSERT INTO deployment_entry_intent (tenant_id,process_id,traversal_id,deployment_id,graph_version,entry_node_id,application_id,cause,input_bytes,retained_bytes,accepted_seconds,accepted_nano,expires_seconds,expires_nano,entry_started,entry_completed,snapshot,snapshot_digest) "
                    + "SELECT tenant_id,process_id,traversal_id,deployment_id,graph_version,entry_node_id,application_id,cause,1,1,0,0,1,0,0,0,?,'deliberately-not-loaded' FROM correlated_traversal")) {
                insert.setBytes(1, new byte[]{1}); assertEquals(1, insert.executeUpdate());
            }
            ExecutionStoreException failure = assertThrows(ExecutionStoreException.class, () -> {
                switch (table) {
                    case "business_history" -> BusinessHistoryStorage.history(db, SCOPE, "business");
                    case "correlated_traversal" -> BusinessHistoryStorage.traversals(db, SCOPE, "business", Optional.empty(), 10, POLICY);
                    case "correlated_join_member" -> CorrelatedJoinStorage.members(db, new CorrelatedJoinKey(SCOPE, "business", "join", "round"), JoinMemberFilter.ALL, Optional.empty(), 10, POLICY);
                    case "deployment_entry_intent" -> DeploymentEntryStorage.page(db, SCOPE, Optional.empty(), 10, POLICY);
                    default -> throw new AssertionError(table);
                }
            });
            var corrupted = assertInstanceOf(ExecutionStoreFailure.Corrupted.class, failure.failure());
            assertEquals(SCOPE.tenantId(), corrupted.key().tenantId());
            assertEquals("stored " + table + "." + column + " is not a canonical UUID", corrupted.reason());
            assertFalse(failure.toString().contains(HOSTILE)); assertNull(failure.getCause());
        }
    }

    @Test void nativeExecutionHistoryIdentifiersRefuseMalformedRowsAtTheDatabaseBoundary() throws Exception {
        var source = PostgresTestDatabase.dataSourceFor("native-history-" + UUID.randomUUID());
        var key = new ExecutionKey("tenant", UUID.randomUUID()); UUID traversal = UUID.randomUUID();
        try (var store = new PostgresExecutionStore(source, CLOCK)) {
            store.apply(PostgresExecutionStoreFixtures.creationBatch(key, traversal)).toCompletableFuture().join();
        }
        try (var db = source.getConnection(); var corrupt = db.prepareStatement("UPDATE traversal SET traversal_id = CAST(? AS uuid)")) {
            corrupt.setString(1, HOSTILE);
            assertEquals("22P02", assertThrows(SQLException.class, corrupt::executeUpdate).getSQLState());
            assertEquals(traversal, ExecutionHistoryStorage.traversals(db, key, Optional.empty(), 10, 100).items().getFirst().traversalId());
        }
    }

    @Test void invalidCursorIdentityRemainsARequestError() {
        for (String value : List.of(HOSTILE, "1-1-1-1-1", "")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> StoredUuid.cursor(value));
            assertFalse(failure.toString().contains(value.isEmpty() ? "secret=" : value));
            assertNull(failure.getCause());
        }
        UUID valid = UUID.randomUUID(); assertEquals(valid, StoredUuid.cursor(valid.toString()));
    }
}
