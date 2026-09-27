package ai.ravenroot.persistence.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteManagedCandidatePlanTest {

    @Test
    void generalRecoveryDiscoveryUsesWorkIndexesInsteadOfProcessHistory(@TempDir Path directory)
            throws Exception {
        Path database = directory.resolve("candidate-plan.db");
        try (var ignored = new SqliteExecutionStore(database, Clock.systemUTC())) {
            // Opening applies the production schema, including candidate indexes.
        }

        var details = new ArrayList<String>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement("EXPLAIN QUERY PLAN "
                     + SqliteExecutionStore.managedCandidateSql(false, true))) {
            SqliteExecutionStore.bindManagedCandidateParameters(statement, "tenant", "worker", 32,
                    false, Optional.of(new UUID(0, 1)), Instant.parse("2026-01-01T00:00:00Z"));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) details.add(rows.getString("detail"));
            }
        }

        String plan = String.join("\n", details);
        assertTrue(plan.contains("managed_recovery_attempt_candidate"), plan);
        assertTrue(plan.contains("managed_recovery_timer_candidate"), plan);
        assertTrue(plan.contains("managed_recovery_handler_candidate"), plan);
        assertFalse(plan.contains("SCAN process_instance"), plan);
    }
}
