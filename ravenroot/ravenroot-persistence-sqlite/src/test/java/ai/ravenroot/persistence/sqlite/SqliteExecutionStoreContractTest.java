package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.testkit.persistence.ExecutionStoreContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR 0010 conformance suite, run against the SQLite adapter.
 *
 * <p>This is the first subclass in the repository for which
 * {@link StoreCapability#DURABLE} and {@link StoreCapability#CROSS_PROCESS_LEASE} are declared, so it
 * is the first run in which the two assertions the suite labels PROVISIONAL actually execute rather
 * than reporting as skips. {@link #theProvisionalAssertionsAreNotSkippedHere} guards that: a
 * capability quietly dropped from {@code capabilities()} would turn both back into skips, and the
 * suite's own Javadoc explains why that is worse than an acknowledged hole — in aggregate a skipped
 * assertion is indistinguishable from a passing one.</p>
 *
 * <p>{@code storeId} maps to a file under the per-test temporary directory, so a reopen genuinely
 * reconnects to the same bytes on disk. Nothing is shared in memory between the two instances.</p>
 */
class SqliteExecutionStoreContractTest extends ExecutionStoreContract {

    @TempDir
    Path databaseDirectory;

    @Override
    protected ExecutionStore createStore(String storeId, Clock clock) {
        return new SqliteExecutionStore(databaseDirectory.resolve(storeId + ".db"), clock);
    }

    @Test
    void theProvisionalAssertionsAreNotSkippedHere() {
        assertTrue(store().supports(StoreCapability.DURABLE),
                "The durability contract requires that the previously skipped DURABLE assertion runs "
                        + "and passes, not that this module's own tests are green");
        assertTrue(store().supports(StoreCapability.CROSS_PROCESS_LEASE),
                "likewise CROSS_PROCESS_LEASE: dropping the declaration would silently convert the "
                        + "assertion back into a skip that looks identical to a pass");
        assertTrue(store().supports(StoreCapability.TRANSACTIONAL_BATCH));
        assertTrue(store().supports(StoreCapability.IDEMPOTENCY_PURGE));
    }
}
