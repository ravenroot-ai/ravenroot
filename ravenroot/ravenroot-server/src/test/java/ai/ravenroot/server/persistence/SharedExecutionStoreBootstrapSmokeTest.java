package ai.ravenroot.server.persistence;

import ai.ravenroot.api.persistence.ProcessInventoryQuery;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.core.graph.GraphMlLimits;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composition seam, end to end, against a real PostgreSQL.
 *
 * <h2>Why a real server and not a substitute</h2>
 * <p>Everything this test is for happens outside Java: the driver resolves the URL, the pool opens
 * connections against it, the adapter takes an advisory lock and migrates a schema, and a query comes
 * back through all of that. A substitute would run these methods and prove none of it. The container
 * is started by the test rather than by the workflow, so a developer and CI provision it the same way
 * and a missing daemon fails the run instead of quietly skipping the assertions — the same posture
 * {@code ravenroot-persistence-postgresql}'s own suite states for itself.</p>
 *
 * <h2>Why the fixture is not reused</h2>
 * <p>{@code PostgresTestDatabase}, which that module's suite shares, is package-private in that
 * module's <em>test</em> sources and the module publishes no test-jar, so it is not on this module's
 * test classpath and cannot be. What is duplicated here is a container and a URL, not that class's
 * schema-isolation logic: this test needs one database it is the only user of, which is what a fresh
 * container already is.</p>
 */
class SharedExecutionStoreBootstrapSmokeTest {

    /**
     * Pinned for the reason the adapter's own suite pins it: a test whose subject is what a real
     * server does should not silently change servers when a tag moves.
     */
    private static final DockerImageName IMAGE = DockerImageName.parse("postgres:17-alpine");

    private static PostgreSQLContainer<?> database;

    @BeforeAll
    static void startDatabase() {
        database = new PostgreSQLContainer<>(IMAGE);
        database.start();
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) {
            database.stop();
        }
    }

    @Test
    void theSharedSelectorComposesThreeStoresOverOneRealDatabase() throws Exception {
        var configuration = ExecutionStoreConfiguration.fromEnvironment(environment(Map.of()));
        assertInstanceOf(ExecutionStoreConfiguration.Shared.class, configuration);

        try (var opened = ExecutionStoreBootstrap.openOwned(configuration, Clock.systemUTC(),
                GraphMlLimits.DEFAULTS)) {
            assertNotNull(opened.store(), "the shared branch must compose an execution store");
            assertNotNull(opened.graphDefinitionStore());
            assertNotNull(opened.executionManifestStore());
            assertTrue(opened.store().supports(StoreCapability.DURABLE));
            assertTrue(opened.store().supports(StoreCapability.TRANSACTIONAL_BATCH),
                    "the application refuses a store that cannot batch, so the seam must produce one "
                            + "that can");

            // A real query through the pool, the driver and the migrated schema. An empty page is the
            // correct answer for a fresh database and is only reachable if every one of those worked.
            var page = opened.store()
                    .listProcessInstances("local", ProcessInventoryQuery.outstanding(10))
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(0, page.items().size());
        }
    }

    @Test
    void closingTheOwnerReleasesThePoolSoTheNextStartFindsTheSchemaAlreadyMigrated() throws Exception {
        var configuration = ExecutionStoreConfiguration.fromEnvironment(environment(Map.of(
                // One connection, so a leaked pool would exhaust this replica's own budget and the
                // second open below would time out rather than succeed.
                ExecutionStoreConfiguration.POOL_SIZE_VARIABLE, "1",
                ExecutionStoreConfiguration.POOL_TIMEOUT_VARIABLE, "2000")));

        try (var first = ExecutionStoreBootstrap.openOwned(configuration, Clock.systemUTC())) {
            assertNotNull(first.store());
        }
        try (var second = ExecutionStoreBootstrap.openOwned(configuration, Clock.systemUTC())) {
            var page = second.store()
                    .listProcessInstances("local", ProcessInventoryQuery.outstanding(10))
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(0, page.items().size());
        }
    }

    @Test
    void theSharedBranchTakesNoSingleWriterLock() throws Exception {
        var configuration = ExecutionStoreConfiguration.fromEnvironment(environment(Map.of()));

        // Two owners over one database at once. The single-host branch refuses this with
        // MAINTENANCE_BUSY, because a SQLite database has one writer; the shared branch must not,
        // because several processes addressing one database is what it exists for. Asserting it here
        // is what stops a later reader "restoring symmetry" by giving the shared branch a lock and
        // turning a horizontally scalable store back into a single-writer one.
        try (var first = ExecutionStoreBootstrap.openOwned(configuration, Clock.systemUTC());
             var second = ExecutionStoreBootstrap.openOwned(configuration, Clock.systemUTC())) {
            assertNotNull(first.store());
            assertNotNull(second.store());
            assertEquals(0, second.store()
                    .listProcessInstances("local", ProcessInventoryQuery.outstanding(10))
                    .toCompletableFuture().get(30, TimeUnit.SECONDS).items().size());
        }
    }

    @Test
    void anUnreachableDatabaseAbortsStartupWithoutPrintingTheUrl() {
        var configuration = ExecutionStoreConfiguration.fromEnvironment(Map.of(
                ExecutionStoreConfiguration.SELECTOR_VARIABLE, "postgresql",
                ExecutionStoreConfiguration.URL_VARIABLE,
                "jdbc:postgresql://127.0.0.1:1/ravenroot?user=admin&password=hunter2",
                ExecutionStoreConfiguration.POOL_TIMEOUT_VARIABLE, "1000"));

        var failure = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionStoreBootstrap.StartupException.class,
                () -> ExecutionStoreBootstrap.openOwned(configuration, Clock.systemUTC()));

        assertEquals(ExecutionStoreBootstrap.FailureReason.UNAVAILABLE, failure.reason());
        assertEquals("Execution store startup failed: UNAVAILABLE", failure.getMessage());
        org.junit.jupiter.api.Assertions.assertNull(failure.getCause(),
                "a cause would let the JVM print the driver's own message, which quotes the URL");
    }

    private static Map<String, String> environment(Map<String, String> extra) {
        var environment = new HashMap<String, String>();
        environment.put(ExecutionStoreConfiguration.SELECTOR_VARIABLE, "postgresql");
        environment.put(ExecutionStoreConfiguration.URL_VARIABLE, database.getJdbcUrl());
        environment.put(ExecutionStoreConfiguration.USER_VARIABLE, database.getUsername());
        environment.put(ExecutionStoreConfiguration.PASSWORD_VARIABLE, database.getPassword());
        environment.putAll(extra);
        return Map.copyOf(environment);
    }
}
