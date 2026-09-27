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
    @Test void anEmptyBootstrapCatalogIsValidAndDoesNotRebuildTheWorker(@TempDir Path directory) throws Exception {
        var sample = RunnerJson.read(Files.readAllBytes(Path.of("../../docs/examples/governed-runner/control-plane.json")));
        sample.put("artifactDirectory", directory.resolve("artifacts").toString());
        var tenant = RunnerJson.map(RunnerJson.map(sample.get("tenants")).get("example-tenant"));
        tenant.put("definitions", List.of()); tenant.put("workspaceProfiles", List.of()); tenant.put("runners", List.of());
        var file = directory.resolve("empty.json"); Files.write(file, RunnerJson.write(sample));
        var configuration = RunnerPlaneConfiguration.fromEnvironment(Map.of("RAVENROOT_RUNNER_CONFIG", file.toString()));
        assertTrue(configuration.definitions().isEmpty());
        try (var store = new SqliteExecutionStore(directory.resolve("empty.db"), Clock.systemUTC())) {
            configuration.service(store, Clock.systemUTC());
            assertTrue(store.runnerResources("example-tenant").toCompletableFuture().join().isEmpty());
        }
    }
    @Test void workerAndDriverCapacitiesAndTimingsAreOperatorOwned() {
        for (int capacity : new int[]{2, 37}) {
            var configured = RunnerWorkerMain.configuration(Map.of("maxConcurrentJobs", capacity,
                    "driverCommandTimeout", "PT47S", "driverOutputTimeout", "PT13S", "maxSupervisorOutputBytes", 131_072));
            assertEquals(capacity, configured.maxConcurrentJobs());
            assertEquals(java.time.Duration.ofSeconds(47), configured.driverCommandTimeout());
            assertEquals(java.time.Duration.ofSeconds(13), configured.driverOutputTimeout());
            assertEquals(131_072, configured.maxSupervisorOutputBytes());
        }
        assertThrows(IllegalArgumentException.class, () -> RunnerWorkerMain.configuration(Map.of("driverCommandTimeout", "PT0S")));
        assertThrows(IllegalArgumentException.class, () -> RunnerWorkerMain.configuration(Map.of("maxSupervisorOutputBytes", 0)));
    }
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
            assertEquals(7, store.runnerResources("example-tenant").toCompletableFuture().join().size());
            assertEquals(8, configuration.control().continuationThreads());
            assertEquals(1, configuration.workspaceProfiles().size());
            configuration.service(store, Clock.systemUTC());
            assertTrue(store.runnerResources("other-tenant").toCompletableFuture().join().isEmpty());
        }
        sample.put("protocolVersion", 2); Files.write(file, RunnerJson.write(sample));
        assertThrows(IllegalArgumentException.class, () -> RunnerPlaneConfiguration.fromEnvironment(environment));
        sample.put("protocolVersion", 1); sample.put("credentials", "not-a-supported-setting"); Files.write(file, RunnerJson.write(sample));
        assertThrows(IllegalArgumentException.class, () -> RunnerPlaneConfiguration.fromEnvironment(environment));
    }
}
