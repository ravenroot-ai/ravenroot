package ai.ravenroot.core.persistence;

import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.testkit.persistence.ExecutionStoreContract;

import java.time.Clock;

/**
 * Binds the ADR 0010 / PERS-02 conformance suite ({@code ravenroot-persistence-testkit}) against the
 * reference in-memory adapter, at test scope, per ADR 0010 section 1.
 *
 * <p>{@code storeId} is intentionally ignored: this adapter declares neither
 * {@link ai.ravenroot.api.persistence.StoreCapability#DURABLE} nor
 * {@link ai.ravenroot.api.persistence.StoreCapability#CROSS_PROCESS_LEASE} (see
 * {@link InMemoryExecutionStore}'s own Javadoc), so the suite's reopen-based assertions for those
 * capabilities are skipped for it by the asymmetric enforcement rule and never observe that a
 * "reopen" here is really just a fresh, empty store sharing the same clock.</p>
 */
class InMemoryExecutionStoreContractTest extends ExecutionStoreContract {

    @Override
    protected ExecutionStore createStore(String storeId, Clock clock) {
        return new InMemoryExecutionStore(clock);
    }
}
