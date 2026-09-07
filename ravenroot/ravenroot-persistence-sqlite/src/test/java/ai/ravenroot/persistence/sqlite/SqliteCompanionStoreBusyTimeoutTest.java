package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.ExecutionManifestReferences;
import ai.ravenroot.api.persistence.GraphDefinitionReferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class SqliteCompanionStoreBusyTimeoutTest {
    @TempDir Path directory;
    private final Clock clock = Clock.systemUTC();

    @Test void explicitTimeoutReachesBothConnectionsWithoutChangingDurability() throws Exception {
        for (int timeout : new int[] {0, 19, Integer.MAX_VALUE}) {
            var location = SqliteStoreLocation.underDirectory(directory.resolve("timeout-" + timeout));
            try (var definitions = new SqliteGraphDefinitionStore(location, clock,
                    GraphDefinitionReferences.NONE, 4096, timeout);
                 var manifests = new SqliteExecutionManifestStore(location, clock,
                         ExecutionManifestReferences.NONE, timeout)) {
                assertPragmas(definitions, timeout);
                assertPragmas(manifests, timeout);
                assertEquals(4096, definitions.maxDefinitionBytes());
            }
        }
    }

    @Test void everyPreviousConstructorKeepsTheSharedDefault() throws Exception {
        int expected = Math.toIntExact(SqliteStoreConfig.defaults().busyTimeout().toMillis());
        Path file = directory.resolve("old.db");
        var location = SqliteStoreLocation.ofFile(file);
        try (var pathDefaults = new SqliteGraphDefinitionStore(file, clock, GraphDefinitionReferences.NONE);
             var pathBounded = new SqliteGraphDefinitionStore(file, clock, GraphDefinitionReferences.NONE, 4096);
             var locationDefaults = new SqliteGraphDefinitionStore(location, clock, GraphDefinitionReferences.NONE);
             var locationBounded = new SqliteGraphDefinitionStore(location, clock, GraphDefinitionReferences.NONE, 8192);
             var pathManifests = new SqliteExecutionManifestStore(file, clock, ExecutionManifestReferences.NONE);
             var locationManifests = new SqliteExecutionManifestStore(location, clock, ExecutionManifestReferences.NONE)) {
            for (Object store : new Object[] {pathDefaults, pathBounded, locationDefaults, locationBounded,
                    pathManifests, locationManifests}) assertPragmas(store, expected);
            assertEquals(4096, pathBounded.maxDefinitionBytes());
            assertEquals(8192, locationBounded.maxDefinitionBytes());
        }
    }

    @Test void explicitFileOverloadsCarryTheTimeout() throws Exception {
        Path file = directory.resolve("explicit.db");
        try (var definitions = new SqliteGraphDefinitionStore(file, clock, GraphDefinitionReferences.NONE, 4096, 23);
             var manifests = new SqliteExecutionManifestStore(file, clock, ExecutionManifestReferences.NONE, 29)) {
            assertPragmas(definitions, 23);
            assertPragmas(manifests, 29);
        }
    }

    @Test void negativeTimeoutIsRefusedBeforePreparingTheLocation() {
        var location = SqliteStoreLocation.underDirectory(directory.resolve("unopened"));
        var definitionsFailure = assertThrows(IllegalArgumentException.class,
                () -> new SqliteGraphDefinitionStore(location, clock, GraphDefinitionReferences.NONE, 4096, -1));
        var manifestsFailure = assertThrows(IllegalArgumentException.class,
                () -> new SqliteExecutionManifestStore(location, clock, ExecutionManifestReferences.NONE, -1));
        for (var failure : new IllegalArgumentException[] {definitionsFailure, manifestsFailure}) {
            assertEquals("busyTimeoutMillis must be non-negative", failure.getMessage());
            assertNull(failure.getCause());
        }
        assertFalse(Files.exists(location.databaseFile().getParent()));
    }

    private static void assertPragmas(Object store, int timeout) throws Exception {
        // Inspect the actual JDBC connection on its owning worker; no production test-only API.
        var connectionField = store.getClass().getDeclaredField("connection");
        var workerField = store.getClass().getDeclaredField("worker");
        connectionField.setAccessible(true);
        workerField.setAccessible(true);
        var connection = (Connection) connectionField.get(store);
        var worker = (ExecutorService) workerField.get(store);
        worker.submit(() -> {
            try (var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("PRAGMA busy_timeout")) {
                    assertTrue(rows.next());
                    assertEquals(timeout, rows.getInt(1));
                }
                try (var rows = statement.executeQuery("PRAGMA synchronous")) {
                    assertTrue(rows.next());
                    assertEquals(2, rows.getInt(1));
                }
            }
            return null;
        }).get();
    }
}
