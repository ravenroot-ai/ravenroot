package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.GraphDefinitionReferences;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.testkit.persistence.GraphDefinitionStoreContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The graph-definition conformance suite, run against the SQLite adapter.
 *
 * <p>This is the subclass for which {@link StoreCapability#DURABLE} is declared, so it is the run in
 * which the reopen assertions actually execute rather than reporting as skips.
 * {@link #theDurabilityAssertionsAreNotSkippedHere} guards that: a capability quietly dropped from
 * {@code capabilities()} would turn them back into skips, and in aggregate a skipped assertion is
 * indistinguishable from a passing one.</p>
 *
 * <p>{@code storeId} maps to a file under the per-test temporary directory, so a reopen genuinely
 * reconnects to the same bytes on disk.</p>
 */
class SqliteGraphDefinitionStoreContractTest extends GraphDefinitionStoreContract {

    @TempDir
    Path databaseDirectory;

    @Override
    protected GraphDefinitionStore createStore(String storeId, Clock clock,
                                               GraphDefinitionReferences references) {
        return new SqliteGraphDefinitionStore(databaseDirectory.resolve(storeId + ".db"), clock, references);
    }

    @Test
    void theDurabilityAssertionsAreNotSkippedHere() {
        assertTrue(store().supports(StoreCapability.DURABLE),
                "the whole point of a definition store is that a definition survives the process "
                        + "that accepted the execution; dropping the declaration would silently "
                        + "convert that assertion into a skip that looks identical to a pass");
    }
}
