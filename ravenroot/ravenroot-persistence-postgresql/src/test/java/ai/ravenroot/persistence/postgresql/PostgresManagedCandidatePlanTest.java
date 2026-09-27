package ai.ravenroot.persistence.postgresql;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresManagedCandidatePlanTest {

    @Test
    void generalRecoveryDiscoveryUsesWorkIndexInsteadOfTerminalProcessHistory() throws Exception {
        var dataSource = PostgresTestDatabase.dataSourceFor("managed-candidate-plan-" + UUID.randomUUID());
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        try (var ignored = new PostgresExecutionStore(dataSource, Clock.fixed(now, ZoneOffset.UTC))) {
            // Opening applies the production schema, including candidate indexes.
        }

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO process_instance
                      (tenant_id, process_instance_id, status, graph_version_pin, revision,
                       fencing_token, lifecycle_generation, created_at_epoch_second, created_at_nano,
                       updated_at_epoch_second, updated_at_nano)
                    SELECT 'tenant', md5('process-' || n)::uuid,
                           CASE WHEN n = 10001 THEN 'RUNNING' ELSE 'COMPLETED' END,
                           'graph-v1', 0, 0, 0, 0, 0, 0, 0
                    FROM generate_series(1, 10001) AS n
                    """);
            statement.executeUpdate("""
                    INSERT INTO traversal
                      (tenant_id, process_instance_id, traversal_id, position, ingress_node_id, status)
                    SELECT 'tenant', md5('process-' || n)::uuid, md5('traversal-' || n)::uuid,
                           0, 'node', CASE WHEN n = 10001 THEN 'RUNNING' ELSE 'COMPLETED' END
                    FROM generate_series(1, 10001) AS n
                    """);
            statement.executeUpdate("""
                    INSERT INTO invocation
                      (tenant_id, process_instance_id, traversal_id, invocation_id, position,
                       node_id, status, node_command)
                    SELECT 'tenant', md5('process-' || n)::uuid, md5('traversal-' || n)::uuid,
                           md5('invocation-' || n)::uuid, 0, 'node',
                           CASE WHEN n = 10001 THEN 'RUNNING' ELSE 'COMPLETED' END, '{}'
                    FROM generate_series(1, 10001) AS n
                    """);
            statement.executeUpdate("""
                    INSERT INTO attempt
                      (tenant_id, process_instance_id, invocation_id, attempt_id, ordinal, status,
                       withheld_through_delivery)
                    SELECT 'tenant', md5('process-' || n)::uuid, md5('invocation-' || n)::uuid,
                           md5('attempt-' || n)::uuid, 1,
                           CASE WHEN n = 10001 THEN 'SCHEDULED' ELSE 'COMPLETED' END, 0
                    FROM generate_series(1, 10001) AS n
                    """);
            statement.execute("ANALYZE");
        }

        var details = new ArrayList<String>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("EXPLAIN (COSTS OFF) "
                     + PostgresExecutionStore.managedCandidateSql(false, false))) {
            PostgresExecutionStore.bindManagedCandidateParameters(statement, "tenant", "worker", 32,
                    false, Optional.empty(), now);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) details.add(rows.getString(1));
            }
        }

        String plan = String.join("\n", details);
        assertTrue(plan.contains("managed_recovery_attempt_candidate"), plan);
        assertFalse(plan.contains("Scan on process_instance"), plan);
    }
}
