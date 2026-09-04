package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.ExecutionManifestReferences;
import ai.ravenroot.api.persistence.ExecutionManifestStore;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.testkit.persistence.ExecutionManifestStoreContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The manifest conformance suite, run against the SQLite adapter.
 *
 * <p>This is the subclass for which {@link StoreCapability#DURABLE} is declared, so it is the run in
 * which the reopen assertion actually executes rather than reporting as a skip.
 * {@link #theDurabilityAssertionIsNotSkippedHere} guards that: a capability quietly dropped from
 * {@code capabilities()} would turn it back into a skip, and in aggregate a skipped assertion is
 * indistinguishable from a passing one.</p>
 */
class SqliteExecutionManifestStoreContractTest extends ExecutionManifestStoreContract {

    @TempDir
    Path databaseDirectory;

    @Override
    protected ExecutionManifestStore createStore(String storeId, Clock clock,
                                                 ExecutionManifestReferences references) {
        return new SqliteExecutionManifestStore(
                databaseDirectory.resolve(storeId + ".db"), clock, references);
    }

    @Test
    void theDurabilityAssertionIsNotSkippedHere() {
        assertTrue(store().supports(StoreCapability.DURABLE),
                "an execution rehydrates from its manifest after a complete process restart; "
                        + "dropping the declaration would silently convert that assertion into a skip "
                        + "that looks identical to a pass");
    }
}
