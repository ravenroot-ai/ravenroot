package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.persistence.ExecutionStorePolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class PostgresStoreConfigTest {
    @Test void defaultsCombineSharedPolicyWithIndependentContentionSettings() {
        var config = PostgresStoreConfig.defaults();
        assertEquals(ExecutionStorePolicy.DEFAULTS, config.executionStorePolicy());
        assertEquals(Duration.ofSeconds(5), config.lockTimeout());
        assertEquals(Duration.ofSeconds(30), config.statementTimeout());
        assertEquals(3, config.serializationRetries());
    }

    @Test void policyOverloadPreservesAllComponentsAndLegacyCanonicalConstructor() {
        var policy = new ExecutionStorePolicy(Duration.ofNanos(13), 257, Duration.ZERO,
                Duration.ofHours(2), 3, Duration.ofDays(2), Duration.ofHours(4));
        var config = new PostgresStoreConfig(Duration.ofSeconds(2), Duration.ofSeconds(3), 0, policy);
        assertEquals(Duration.ofNanos(13), config.maxLeaseTtl());
        assertEquals(257, config.maxPayloadBytes());
        assertEquals(Duration.ZERO, config.maxClockSkew());
        assertEquals(Duration.ofHours(2), config.journalRetention());
        assertEquals(3, config.maxInventoryPageSize());
        assertEquals(Duration.ofDays(2), config.terminalRetention());
        assertEquals(Duration.ofHours(4), config.executionResultRetention());
        assertEquals(policy, config.executionStorePolicy());
        assertEquals(config, new PostgresStoreConfig(Duration.ofSeconds(2), Duration.ofSeconds(3), 0,
                Duration.ofNanos(13), 257, Duration.ZERO, Duration.ofHours(2), 3,
                Duration.ofDays(2), Duration.ofHours(4)));
    }

    @Test void legacyConstructorNowRejectsResultWindowThatInstancePurgeWouldTruncate() {
        var failure = assertThrows(IllegalArgumentException.class, () -> new PostgresStoreConfig(
                Duration.ofSeconds(1), Duration.ofSeconds(2), 0, Duration.ofSeconds(1), 1,
                Duration.ZERO, Duration.ofDays(1), 1, Duration.ofDays(1), Duration.ofDays(2)));
        assertEquals("terminalRetention cannot be shorter than executionResultRetention", failure.getMessage());
        assertNull(failure.getCause());
        assertDoesNotThrow(() -> new PostgresStoreConfig(Duration.ofSeconds(1), Duration.ofSeconds(2), 0,
                Duration.ofSeconds(1), 1, Duration.ZERO, Duration.ofDays(1), 1,
                Duration.ofDays(1), Duration.ofDays(1)));
        assertThrows(IllegalArgumentException.class, () -> new PostgresStoreConfig(
                Duration.ofSeconds(1), Duration.ofSeconds(2), 0, Duration.ofSeconds(1), 1,
                Duration.ZERO, Duration.ofDays(2), 1, Duration.ofDays(1), Duration.ofDays(1)));
    }

    @Test void connectionTimeoutsAndRetriesKeepTheirAdapterSpecificValidation() {
        var policy = ExecutionStorePolicy.DEFAULTS;
        assertDoesNotThrow(() -> new PostgresStoreConfig(Duration.ofNanos(1), Duration.ofNanos(1), 0, policy));
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresStoreConfig(Duration.ZERO, Duration.ofSeconds(1), 0, policy));
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresStoreConfig(Duration.ofSeconds(1), Duration.ZERO, 0, policy));
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresStoreConfig(Duration.ofSeconds(2), Duration.ofSeconds(1), 0, policy));
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresStoreConfig(Duration.ofSeconds(1), Duration.ofSeconds(2), -1, policy));
    }
}
