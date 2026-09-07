package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.persistence.ExecutionManifestReferences;
import ai.ravenroot.api.persistence.ExecutionManifestStore;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.testkit.persistence.ExecutionManifestStoreContract;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Clock;

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
}
