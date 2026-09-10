package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.persistence.ExecutionManifestReferences;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifest;
import ai.ravenroot.api.persistence.ExecutionManifestStoreException;
import ai.ravenroot.api.persistence.ExecutionManifestStoreFailure;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.ResolvedRuntimeProfile;
import ai.ravenroot.api.persistence.ExecutionManifestStore;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.testkit.persistence.ExecutionManifestStoreContract;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The manifest conformance suite, run against the PostgreSQL adapter.
 *
 * <p>This is the subclass for which {@link StoreCapability#DURABLE} is declared, so it is the run in
 * which the reopen assertion actually executes rather than reporting as a skip.
 * {@link #theDurabilityAssertionIsNotSkippedHere} guards that, exactly as the SQLite adapter's
 * identically named test does: a capability quietly dropped from {@code capabilities()} would turn it
 * back into a skip, and in aggregate a skipped assertion is indistinguishable from a passing one.</p>
 *
 * <p>{@code storeId} maps to an isolated schema through {@link PostgresTestDatabase}; see
 * {@link PostgresGraphDefinitionStoreContractTest}'s class documentation for why isolation by schema,
 * rather than by tenant id, is what this suite actually needs against a shared-database adapter.</p>
 */
class PostgresExecutionManifestStoreContractTest extends ExecutionManifestStoreContract {

    @Override
    protected ExecutionManifestStore createStore(String storeId, Clock clock,
                                                  ExecutionManifestReferences references) {
        DataSource dataSource = PostgresTestDatabase.dataSourceFor(storeId);
        return new PostgresExecutionManifestStore(dataSource, clock, references);
    }

    @Test
    void theDurabilityAssertionIsNotSkippedHere() {
        assertTrue(store().supports(StoreCapability.DURABLE),
                "an execution rehydrates from its manifest after a complete process restart; "
                        + "dropping the declaration would silently convert that assertion into a skip "
                        + "that looks identical to a pass");
    }

    @Test
    void aStoredFutureFormatIsAClassifiedCorruptRead() throws Exception {
        DataSource dataSource = PostgresTestDatabase.dataSourceFor("future-manifest-" + UUID.randomUUID());
        var key = new ExecutionKey("acme", UUID.randomUUID());
        var content = new GraphContentId("a".repeat(64));
        var profile = new ResolvedRuntimeProfile(1, 1, "STANDARD", "pass-through",
                "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64));
        var manifest = new ExecutionManifest(ExecutionManifest.CURRENT_FORMAT_VERSION, key, content,
                GraphDefinitionIdentity.forSubmission(content), profile, List.of(), Instant.EPOCH);
        try (var manifestStore = new PostgresExecutionManifestStore(
                dataSource, Clock.systemUTC(), ExecutionManifestReferences.NONE)) {
            manifestStore.pin(manifest).toCompletableFuture().join();
        }
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE execution_manifest SET format_version = ? WHERE tenant_id = ? "
                             + "AND process_instance_id = ?")) {
            statement.setInt(1, ExecutionManifest.CURRENT_FORMAT_VERSION + 1);
            statement.setString(2, key.tenantId());
            statement.setObject(3, key.processInstanceId());
            assertEquals(1, statement.executeUpdate());
        }

        try (var manifestStore = new PostgresExecutionManifestStore(
                dataSource, Clock.systemUTC(), ExecutionManifestReferences.NONE)) {
            CompletionException wrapped = assertThrows(CompletionException.class,
                    () -> manifestStore.load(key).toCompletableFuture().join());
            ExecutionManifestStoreException failure = ExecutionManifestStoreException.unwrap(wrapped);
            var corrupted = assertInstanceOf(ExecutionManifestStoreFailure.Corrupted.class,
                    failure.failure());
            assertEquals("unsupported execution manifest format version", corrupted.reason());
        }
    }
}
