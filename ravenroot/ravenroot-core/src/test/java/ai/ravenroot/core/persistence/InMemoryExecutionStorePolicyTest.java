package ai.ravenroot.core.persistence;

import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionStorePolicy;
import ai.ravenroot.api.persistence.HumanTaskPolicy;
import ai.ravenroot.api.persistence.ProcessInventoryQuery;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryExecutionStorePolicyTest {
    private static final Clock CLOCK = Clock.systemUTC();

    @Test void defaultConstructorsShareTheNeutralPolicy() {
        for (var store : List.of(new InMemoryExecutionStore(), new InMemoryExecutionStore(CLOCK),
                new InMemoryExecutionStore(CLOCK, HumanTaskPolicy.DEFAULTS))) {
            try (store) {
                assertPolicy(ExecutionStorePolicy.DEFAULTS, store);
                assertEquals(HumanTaskPolicy.DEFAULTS.inboxMaxPageSize(), store.maxHumanTaskPageSize());
            }
        }
    }

    @Test void policyControlsReportedLimitsAndRealInventoryAdmission() {
        var policy = new ExecutionStorePolicy(Duration.ofSeconds(13), 257, Duration.ZERO,
                Duration.ofHours(2), 3, Duration.ofDays(2), Duration.ofHours(4));
        try (var store = new InMemoryExecutionStore(CLOCK, policy, HumanTaskPolicy.DEFAULTS)) {
            assertPolicy(policy, store);
            assertTrue(store.listProcessInstances("tenant", ProcessInventoryQuery.everything(3))
                    .toCompletableFuture().join().items().isEmpty());
            var failure = assertThrows(CompletionException.class, () -> store.listProcessInstances(
                    "tenant", ProcessInventoryQuery.everything(4)).toCompletableFuture().join());
            assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                    assertInstanceOf(ExecutionStoreException.class, failure.getCause()).failure());
            assertEquals(HumanTaskPolicy.DEFAULTS.inboxMaxPageSize(), store.maxHumanTaskPageSize());
        }
    }

    @Test void legacyConstructorsPreserveCallerTerminalFallbackAndExplicitResultWindow() {
        Duration lease = Duration.ofSeconds(13), skew = Duration.ofSeconds(17), journal = Duration.ofHours(2);
        Duration terminal = Duration.ofDays(2), result = Duration.ofHours(4);
        var defaults = ExecutionStorePolicy.DEFAULTS;
        try (var store = new InMemoryExecutionStore(CLOCK, lease, 257)) {
            assertPolicy(new ExecutionStorePolicy(lease, 257, defaults.maxClockSkew(),
                    defaults.journalRetention(), defaults.maxInventoryPageSize(), defaults.terminalRetention(),
                    defaults.terminalRetention()), store);
        }
        try (var store = new InMemoryExecutionStore(CLOCK, lease, 257, skew)) {
            assertPolicy(new ExecutionStorePolicy(lease, 257, skew, defaults.journalRetention(),
                    defaults.maxInventoryPageSize(), defaults.terminalRetention(), defaults.terminalRetention()), store);
        }
        try (var store = new InMemoryExecutionStore(CLOCK, lease, 257, skew, journal)) {
            assertPolicy(new ExecutionStorePolicy(lease, 257, skew, journal, defaults.maxInventoryPageSize(),
                    defaults.terminalRetention(), defaults.terminalRetention()), store);
        }
        try (var store = new InMemoryExecutionStore(CLOCK, lease, 257, skew, journal, terminal)) {
            assertPolicy(new ExecutionStorePolicy(lease, 257, skew, journal, defaults.maxInventoryPageSize(),
                    terminal, terminal), store);
        }
        try (var store = new InMemoryExecutionStore(CLOCK, lease, 257, skew, journal, terminal, result)) {
            assertPolicy(new ExecutionStorePolicy(lease, 257, skew, journal, defaults.maxInventoryPageSize(),
                    terminal, result), store);
        }
        try (var store = new InMemoryExecutionStore(CLOCK, lease, 257, skew, journal, terminal, result,
                HumanTaskPolicy.DEFAULTS)) {
            assertPolicy(new ExecutionStorePolicy(lease, 257, skew, journal, defaults.maxInventoryPageSize(),
                    terminal, result), store);
        }
    }

    @Test void legacyConstructorRejectsInvalidSharedPolicyAndNullCallRemainsUnambiguous() {
        assertThrows(NullPointerException.class, () -> new InMemoryExecutionStore(CLOCK, null));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryExecutionStore(CLOCK,
                Duration.ofSeconds(1), 1, Duration.ZERO, Duration.ofDays(2), Duration.ofDays(1)));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryExecutionStore(CLOCK,
                Duration.ofSeconds(1), 1, Duration.ZERO, Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(2)));
    }

    private static void assertPolicy(ExecutionStorePolicy expected, ExecutionStore store) {
        assertEquals(expected.maxLeaseTtl(), store.maxLeaseTtl());
        assertEquals(expected.maxPayloadBytes(), store.maxPayloadBytes());
        assertEquals(expected.maxPayloadBytes(), store.maxExecutionResultPayloadBytes());
        assertEquals(expected.maxClockSkew(), store.maxClockSkew());
        assertEquals(expected.journalRetention(), store.journalRetention());
        assertEquals(expected.maxInventoryPageSize(), store.maxInventoryPageSize());
        assertEquals(expected.terminalRetention(), store.terminalRetention());
        assertEquals(expected.executionResultRetention(), store.executionResultRetention());
    }
}
