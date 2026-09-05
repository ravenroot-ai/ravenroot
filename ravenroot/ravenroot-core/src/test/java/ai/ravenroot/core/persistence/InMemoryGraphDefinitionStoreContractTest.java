package ai.ravenroot.core.persistence;

import ai.ravenroot.api.persistence.GraphDefinitionReferences;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.testkit.persistence.GraphDefinitionStoreContract;

import java.time.Clock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Binds the graph-definition conformance suite against the reference in-memory adapter.
 *
 * <p>{@code storeId} is intentionally ignored: this adapter declares no
 * {@link ai.ravenroot.api.persistence.StoreCapability#DURABLE}, so the suite's reopen-based
 * assertions are skipped for it under the asymmetric enforcement rule and never observe that a
 * "reopen" here is really a fresh, empty store sharing the same clock.</p>
 */
class InMemoryGraphDefinitionStoreContractTest extends GraphDefinitionStoreContract {

    @Test
    void explicitBoundsCannotEscapeTheSharedSafetyCeiling() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryGraphDefinitionStore(
                Clock.systemUTC(), GraphDefinitionReferences.NONE, 0));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryGraphDefinitionStore(
                Clock.systemUTC(), GraphDefinitionReferences.NONE,
                GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES + 1));
    }

    @Override
    protected GraphDefinitionStore createStore(String storeId, Clock clock,
                                               GraphDefinitionReferences references) {
        return new InMemoryGraphDefinitionStore(clock, references);
    }
}
