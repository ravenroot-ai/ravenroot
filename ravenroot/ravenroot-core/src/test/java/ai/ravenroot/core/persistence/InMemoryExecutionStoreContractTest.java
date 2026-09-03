package ai.ravenroot.core.persistence;

import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.testkit.persistence.ExecutionStoreContract;
import org.junit.jupiter.api.Disabled;
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
     * DEFECT (issue 154, wave 1): the commit message and {@code ExecutionStore#terminalRetention()}'s
     * javadoc both state that terminal retention "may not be shorter than journal retention" -- and
     * {@code SqliteStoreConfig}'s canonical constructor enforces exactly that, rejecting the
     * combination with {@code IllegalArgumentException}. {@code InMemoryExecutionStore}'s six-argument
     * constructor validates {@code journalRetention} and {@code terminalRetention} each independently
     * (positive, non-null) but never compares them to each other, so the in-memory adapter silently
     * accepts a configuration the SQLite adapter refuses. This is a genuine cross-adapter
     * inconsistency in {@code src/main}, which is out of this test's territory to fix; it is reported
     * here with a demonstrating, disabled test rather than fixed or worked around. Route the fix to
     * {@code InMemoryExecutionStore}'s canonical constructor
     * ({@code ravenroot-core/src/main/java/ai/ravenroot/core/persistence/InMemoryExecutionStore.java}),
     * mirroring {@code SqliteStoreConfig}'s guard.
     */
    @Test
    @Disabled("DEFECT: InMemoryExecutionStore does not reject terminalRetention shorter than "
            + "journalRetention, unlike SqliteStoreConfig -- see this test's javadoc")
    final void terminalRetentionShorterThanJournalRetentionIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryExecutionStore(Clock.systemUTC(),
                Duration.ofMinutes(5), 1024 * 1024, Duration.ofSeconds(5),
                /* journalRetention */ Duration.ofDays(1), /* terminalRetention */ Duration.ofHours(1)));
    }
}
