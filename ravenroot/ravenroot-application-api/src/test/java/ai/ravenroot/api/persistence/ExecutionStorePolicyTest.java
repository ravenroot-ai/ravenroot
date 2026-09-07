package ai.ravenroot.api.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionStorePolicyTest {
    private static final Duration ONE = Duration.ofNanos(1);

    @Test void defaultsPreserveAllSevenPublishedValues() {
        var policy = ExecutionStorePolicy.DEFAULTS;
        assertEquals(Duration.ofMinutes(5), policy.maxLeaseTtl());
        assertEquals(1_048_576, policy.maxPayloadBytes());
        assertEquals(Duration.ofSeconds(5), policy.maxClockSkew());
        assertEquals(Duration.ofHours(24), policy.journalRetention());
        assertEquals(100, policy.maxInventoryPageSize());
        assertEquals(Duration.ofDays(7), policy.terminalRetention());
        assertEquals(Duration.ofDays(7), policy.executionResultRetention());
    }

    @Test void preservesPositiveSubMillisecondValuesAndHasNoInventedUpperOrSkewLeaseBound() {
        var tiny = new ExecutionStorePolicy(ONE, 1, Duration.ZERO, ONE, 1, ONE, ONE);
        assertEquals(ONE, tiny.maxLeaseTtl());
        assertEquals(Duration.ZERO, tiny.maxClockSkew());
        Duration large = Duration.ofSeconds(Long.MAX_VALUE, 999_999_999);
        var broad = new ExecutionStorePolicy(ONE, Integer.MAX_VALUE, large, large,
                Integer.MAX_VALUE, large, large);
        assertEquals(large, broad.maxClockSkew());
        assertEquals(large, broad.terminalRetention());
        assertEquals(Integer.MAX_VALUE, broad.maxPayloadBytes());
        assertEquals(Integer.MAX_VALUE, broad.maxInventoryPageSize());
    }

    @Test void rejectsMissingDurations() {
        List<Executable> missing = List.of(
                () -> new ExecutionStorePolicy(null, 1, Duration.ZERO, ONE, 1, ONE, ONE),
                () -> new ExecutionStorePolicy(ONE, 1, null, ONE, 1, ONE, ONE),
                () -> new ExecutionStorePolicy(ONE, 1, Duration.ZERO, null, 1, ONE, ONE),
                () -> new ExecutionStorePolicy(ONE, 1, Duration.ZERO, ONE, 1, null, ONE),
                () -> new ExecutionStorePolicy(ONE, 1, Duration.ZERO, ONE, 1, ONE, null));
        missing.forEach(value -> assertThrows(NullPointerException.class, value));
    }

    @Test void rejectsEveryNonpositiveLimitAndNegativeSkew() {
        for (Duration invalid : List.of(Duration.ZERO, ONE.negated())) {
            List<Executable> durations = List.of(
                    () -> new ExecutionStorePolicy(invalid, 1, Duration.ZERO, ONE, 1, ONE, ONE),
                    () -> new ExecutionStorePolicy(ONE, 1, Duration.ZERO, invalid, 1, ONE, ONE),
                    () -> new ExecutionStorePolicy(ONE, 1, Duration.ZERO, ONE, 1, invalid, ONE),
                    () -> new ExecutionStorePolicy(ONE, 1, Duration.ZERO, ONE, 1, ONE, invalid));
            durations.forEach(value -> assertThrows(IllegalArgumentException.class, value));
        }
        for (int invalid : new int[]{0, -1}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExecutionStorePolicy(ONE, invalid, Duration.ZERO, ONE, 1, ONE, ONE));
            assertThrows(IllegalArgumentException.class,
                    () -> new ExecutionStorePolicy(ONE, 1, Duration.ZERO, ONE, invalid, ONE, ONE));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionStorePolicy(ONE, 1, ONE.negated(), ONE, 1, ONE, ONE));
    }

    @Test void terminalWindowCoversJournalAndResultsIncludingEquality() {
        Duration two = Duration.ofNanos(2);
        assertDoesNotThrow(() -> new ExecutionStorePolicy(ONE, 1, Duration.ZERO, two, 1, two, two));
        var journal = assertThrows(IllegalArgumentException.class,
                () -> new ExecutionStorePolicy(ONE, 1, Duration.ZERO, two, 1, ONE, ONE));
        assertEquals("terminalRetention cannot be shorter than journalRetention", journal.getMessage());
        assertNull(journal.getCause());
        var result = assertThrows(IllegalArgumentException.class,
                () -> new ExecutionStorePolicy(ONE, 1, Duration.ZERO, ONE, 1, ONE, two));
        assertEquals("terminalRetention cannot be shorter than executionResultRetention", result.getMessage());
        assertNull(result.getCause());
    }
}
