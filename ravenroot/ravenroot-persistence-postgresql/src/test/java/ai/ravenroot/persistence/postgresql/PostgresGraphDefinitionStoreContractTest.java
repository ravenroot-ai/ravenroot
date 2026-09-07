package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.persistence.GraphDefinitionReferences;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.testkit.persistence.GraphDefinitionStoreContract;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The graph-definition conformance suite, run against the PostgreSQL adapter.
 *
 * <p>This is the subclass for which {@link StoreCapability#DURABLE} is declared, so it is the run in
 * which the reopen assertions actually execute rather than reporting as skips.
 * {@link #theDurabilityAssertionsAreNotSkippedHere} guards that, exactly as the SQLite adapter's
 * identically named test does: a capability quietly dropped from {@code capabilities()} would turn
 * those assertions back into skips, and in aggregate a skipped assertion is indistinguishable from a
 * passing one.</p>
 *
 * <p>{@code storeId} maps to an isolated schema inside the one PostgreSQL container this module's
 * conformance run shares, through {@link PostgresTestDatabase}. Isolation between two {@code storeId}s
 * matters here in a way it does not for the file-backed SQLite adapter: this adapter's tables carry no
 * {@code storeId} column, only {@code tenant_id}, and the suite reuses the same tenant id
 * ({@code "acme"}) across independent test methods, so two concurrently open stores that shared one
 * schema would corrupt each other's fixtures. A reopen reuses the same {@code storeId} and therefore
 * the same schema, which is what lets the durability assertions reconnect to the same stored rows.</p>
 */
class PostgresGraphDefinitionStoreContractTest extends GraphDefinitionStoreContract {

    @Override
    protected GraphDefinitionStore createStore(String storeId, Clock clock, GraphDefinitionReferences references) {
        DataSource dataSource = PostgresTestDatabase.dataSourceFor(storeId);
        return new PostgresGraphDefinitionStore(dataSource, clock, references);
    }

    @Test
    void theDurabilityAssertionsAreNotSkippedHere() {
        assertTrue(store().supports(StoreCapability.DURABLE),
                "the whole point of a definition store is that a definition survives the process "
                        + "that accepted the execution; dropping the declaration would silently "
                        + "convert that assertion into a skip that looks identical to a pass");
    }
}
