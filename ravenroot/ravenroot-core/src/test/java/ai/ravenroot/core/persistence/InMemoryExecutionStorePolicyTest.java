package ai.ravenroot.core.persistence;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryExecutionStorePolicyTest {
    @Test
    void defaultsAreOneTypedProgrammaticDecision() {
        var defaults = InMemoryExecutionStorePolicy.DEFAULTS;
        assertEquals(Duration.ofMinutes(5), defaults.maximumLeaseTtl());
        assertEquals(1024 * 1024, defaults.maximumPayloadBytes());
        assertEquals(Duration.ofSeconds(5), defaults.maximumClockSkew());
        assertEquals(Duration.ofHours(24), defaults.journalRetention());
        assertEquals(100, defaults.maximumInventoryPageSize());
        assertEquals(Duration.ofDays(7), defaults.terminalRetention());
        assertEquals(Duration.ofDays(7), defaults.executionResultRetention());
    }

    @Test
    void explicitPolicyControlsTheReferenceStoreWithoutClaimingManagedPersistence() {
        var policy = new InMemoryExecutionStorePolicy(Duration.ofSeconds(40), 8192,
                Duration.ofSeconds(2), Duration.ofHours(2), 17, Duration.ofHours(4),
                Duration.ofHours(3));
        try (var store = new InMemoryExecutionStore(Clock.systemUTC(), policy)) {
            assertEquals(policy.maximumLeaseTtl(), store.maxLeaseTtl());
            assertEquals(policy.maximumPayloadBytes(), store.maxPayloadBytes());
            assertEquals(policy.maximumClockSkew(), store.maxClockSkew());
            assertEquals(policy.journalRetention(), store.journalRetention());
            assertEquals(policy.maximumInventoryPageSize(), store.maxInventoryPageSize());
            assertEquals(policy.terminalRetention(), store.terminalRetention());
            assertEquals(policy.executionResultRetention(), store.executionResultRetention());
            assertFalse(store.protectsManagedPersistence(),
                    "the reference store has no atomic manifest/process persistence seam");
        }
    }

    @Test
    void invalidCrossRetentionAndPageBoundsFailBeforeUse() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryExecutionStorePolicy(
                Duration.ofMinutes(5), 1024, Duration.ZERO, Duration.ofDays(2), 0,
                Duration.ofDays(3), Duration.ofDays(3)));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryExecutionStorePolicy(
                Duration.ofMinutes(5), 1024, Duration.ZERO, Duration.ofDays(4), 100,
                Duration.ofDays(3), Duration.ofDays(2)));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryExecutionStorePolicy(
                Duration.ofMinutes(5), 1024, Duration.ZERO, Duration.ofDays(2), 100,
                Duration.ofDays(3), Duration.ofDays(4)));
    }
}
