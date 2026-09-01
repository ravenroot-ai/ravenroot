package ai.ravenroot.server.readiness;

import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves readiness is reading the same concrete execution-store connection production composes. */
class ExecutionStoreReadinessIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void closingTheComposedExecutionStoreMakesReadinessFailAndNoStoreDetailEscapes() {
        var store = new SqliteExecutionStore(temporaryDirectory.resolve("execution.db"), Clock.systemUTC());
        try (var gate = new ReadinessGate(() -> "RUNNING", StoreLivenessCheck.executionStore(store),
                List::of, ReadinessConfiguration.defaults())) {
            assertTrue(gate.evaluate().ready());

            store.close();
            ReadinessReport degraded = gate.evaluate();

            assertFalse(degraded.ready());
            assertEquals(ReadinessState.STORE_DEGRADED, degraded.state());
            assertTrue(degraded.dependencies().isEmpty(),
                    "a required-store failure must not be copied into the public dependency detail map");
        } finally {
            store.close();
        }
    }
}
