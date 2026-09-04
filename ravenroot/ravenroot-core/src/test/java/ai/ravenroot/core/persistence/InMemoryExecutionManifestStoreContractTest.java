package ai.ravenroot.core.persistence;

import ai.ravenroot.api.persistence.ExecutionManifestReferences;
import ai.ravenroot.api.persistence.ExecutionManifestStore;
import ai.ravenroot.testkit.persistence.ExecutionManifestStoreContract;

import java.time.Clock;

/**
 * The manifest conformance suite, run against the in-memory adapter.
 *
 * <p>{@code storeId} is ignored: this adapter is not durable, does not declare
 * {@link ai.ravenroot.api.persistence.StoreCapability#DURABLE}, and the assertions that would notice
 * are gated on that declaration.</p>
 */
class InMemoryExecutionManifestStoreContractTest extends ExecutionManifestStoreContract {

    @Override
    protected ExecutionManifestStore createStore(String storeId, Clock clock,
                                                 ExecutionManifestReferences references) {
        return new InMemoryExecutionManifestStore(clock, references);
    }
}
