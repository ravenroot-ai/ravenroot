package ai.ravenroot.server;

import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.runner.RunnerJson;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RunnerPlaneConfigurationTest {
    @Test void optInSampleSeedsDurableCatalogAndRejectsUnsafeDeploymentModes(@TempDir Path directory) throws Exception {
        assertNull(RunnerPlaneConfiguration.fromEnvironment(Map.of()));
        var sample = new LinkedHashMap<>(RunnerJson.read(Files.readAllBytes(Path.of("../../docs/examples/governed-runner/control-plane.json"))));
        sample.put("artifactDirectory", directory.toRealPath().resolve("artifacts").toString());
        Path file = directory.resolve("runner.json"); Files.write(file, RunnerJson.write(sample));
        var environment = Map.of("RAVENROOT_RUNNER_CONFIG", file.toString());
        var configuration = RunnerPlaneConfiguration.fromEnvironment(environment);
        assertEquals(5, configuration.definitions().size());
        assertEquals(1, configuration.runners().size());
        try (var memory = new InMemoryExecutionStore(Clock.systemUTC())) {
            assertThrows(IllegalArgumentException.class, () -> configuration.service(memory, Clock.systemUTC()));
        }
        try (var store = new SqliteExecutionStore(directory.resolve("store.db"), Clock.systemUTC())) {
            configuration.service(store, Clock.systemUTC());
            assertEquals(6, store.runnerResources("example-tenant").toCompletableFuture().join().size());
            configuration.service(store, Clock.systemUTC());
            assertTrue(store.runnerResources("other-tenant").toCompletableFuture().join().isEmpty());
        }
        sample.put("protocolVersion", 2); Files.write(file, RunnerJson.write(sample));
        assertThrows(IllegalArgumentException.class, () -> RunnerPlaneConfiguration.fromEnvironment(environment));
        sample.put("protocolVersion", 1); sample.put("credentials", "not-a-supported-setting"); Files.write(file, RunnerJson.write(sample));
        assertThrows(IllegalArgumentException.class, () -> RunnerPlaneConfiguration.fromEnvironment(environment));
    }
}
