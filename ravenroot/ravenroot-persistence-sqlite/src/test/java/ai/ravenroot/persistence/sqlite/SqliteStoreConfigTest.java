package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.ExecutionStorePolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class SqliteStoreConfigTest {
    @Test void defaultsCombineSharedPolicyWithIndependentConnectionSettings() {
        var config = SqliteStoreConfig.defaults();
        assertEquals(ExecutionStorePolicy.DEFAULTS, config.executionStorePolicy());
        assertEquals(SqliteStoreConfig.SynchronousMode.FULL, config.synchronousMode());
        assertEquals(Duration.ofSeconds(5), config.busyTimeout());
    }

    @Test void policyOverloadPreservesAllComponentsAndLegacyCanonicalConstructor() {
        var policy = new ExecutionStorePolicy(Duration.ofNanos(13), 257, Duration.ZERO,
                Duration.ofHours(2), 3, Duration.ofDays(2), Duration.ofHours(4));
        var config = new SqliteStoreConfig(SqliteStoreConfig.SynchronousMode.NORMAL, Duration.ZERO, policy);
        assertEquals(Duration.ofNanos(13), config.maxLeaseTtl());
        assertEquals(257, config.maxPayloadBytes());
        assertEquals(Duration.ZERO, config.maxClockSkew());
        assertEquals(Duration.ofHours(2), config.journalRetention());
        assertEquals(3, config.maxInventoryPageSize());
        assertEquals(Duration.ofDays(2), config.terminalRetention());
        assertEquals(Duration.ofHours(4), config.executionResultRetention());
        assertEquals(policy, config.executionStorePolicy());
        assertEquals(config, new SqliteStoreConfig(SqliteStoreConfig.SynchronousMode.NORMAL, Duration.ZERO,
                Duration.ofNanos(13), 257, Duration.ZERO, Duration.ofHours(2), 3,
                Duration.ofDays(2), Duration.ofHours(4)));
        assertEquals(policy, config.withBusyTimeout(Duration.ofSeconds(17))
                .withSynchronousMode(SqliteStoreConfig.SynchronousMode.FULL).executionStorePolicy());
        assertEquals(7, config.withMaxInventoryPageSize(7).maxInventoryPageSize());
        assertEquals(Duration.ofDays(3), config.withTerminalRetention(Duration.ofDays(3)).terminalRetention());
        assertEquals(Duration.ofHours(1), config.withJournalRetention(Duration.ofHours(1)).journalRetention());
        assertEquals(Duration.ofHours(3), config.withExecutionResultRetention(Duration.ofHours(3))
                .executionResultRetention());
    }

    @Test void legacyConstructorAndCopyMethodsEnforceSharedRetentionRelations() {
        assertThrows(IllegalArgumentException.class, () -> new SqliteStoreConfig(
                SqliteStoreConfig.SynchronousMode.FULL, Duration.ZERO, Duration.ofSeconds(1), 1,
                Duration.ZERO, Duration.ofDays(2), 1, Duration.ofDays(1), Duration.ofDays(1)));
        assertThrows(IllegalArgumentException.class, () -> new SqliteStoreConfig(
                SqliteStoreConfig.SynchronousMode.FULL, Duration.ZERO, Duration.ofSeconds(1), 1,
                Duration.ZERO, Duration.ofDays(1), 1, Duration.ofDays(1), Duration.ofDays(2)));
        assertThrows(IllegalArgumentException.class,
                () -> SqliteStoreConfig.defaults().withExecutionResultRetention(Duration.ofDays(8)));
        assertThrows(IllegalArgumentException.class,
                () -> SqliteStoreConfig.defaults().withMaxInventoryPageSize(0));
    }

    @Test void sqliteBusyTimeoutRetainsItsOwnNonnegativeRange() {
        assertDoesNotThrow(() -> SqliteStoreConfig.defaults().withBusyTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> SqliteStoreConfig.defaults().withBusyTimeout(Duration.ofNanos(-1)));
        assertThrows(NullPointerException.class, () -> SqliteStoreConfig.defaults().withSynchronousMode(null));
    }
}
