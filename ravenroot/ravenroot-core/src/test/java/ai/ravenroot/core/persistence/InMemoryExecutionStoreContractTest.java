package ai.ravenroot.core.persistence;

import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.testkit.persistence.ExecutionStoreContract;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;

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

    /**
     * The retention invariant is a property of the contract, so both adapters enforce it.
     *
     * <p>{@code ExecutionStore#terminalRetention()} states that a terminal instance must outlive its
     * own events, and {@code SqliteStoreConfig}'s canonical constructor rejects the combination that
     * would break it. The reason has nothing to do with the storage medium — a terminal instance
     * pruned while its events are still readable leaves the journal naming an instance the inventory
     * can no longer describe — so the reference adapter has to refuse it too. Enforcing it in only one
     * adapter would let a deployment reach a configuration through this store that the durable store
     * rejects, and find out on the day it swapped them.</p>
     */
    @Test
    final void terminalRetentionShorterThanJournalRetentionIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryExecutionStore(Clock.systemUTC(),
                Duration.ofMinutes(5), 1024 * 1024, Duration.ofSeconds(5),
                /* journalRetention */ Duration.ofDays(1), /* terminalRetention */ Duration.ofHours(1)));
    }
}
