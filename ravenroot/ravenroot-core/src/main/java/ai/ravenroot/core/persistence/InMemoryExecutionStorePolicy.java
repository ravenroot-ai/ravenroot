package ai.ravenroot.core.persistence;

import java.time.Duration;
import java.util.Objects;

/**
 * Typed programmatic policy for the reference execution store.
 *
 * <p>The in-memory adapter has no deployment environment binding: callers select this policy when
 * embedding the reference store. Keeping its defaults in one public value makes those choices
 * inspectable and prevents its compatibility constructors from growing independent literals.</p>
 */
public record InMemoryExecutionStorePolicy(Duration maximumLeaseTtl, int maximumPayloadBytes,
                                           Duration maximumClockSkew, Duration journalRetention,
                                           int maximumInventoryPageSize, Duration terminalRetention,
                                           Duration executionResultRetention) {
    /** The reference adapter's shipped programmatic defaults. */
    public static final InMemoryExecutionStorePolicy DEFAULTS = new InMemoryExecutionStorePolicy(
            Duration.ofMinutes(5), 1024 * 1024, Duration.ofSeconds(5), Duration.ofHours(24), 100,
            Duration.ofDays(7), Duration.ofDays(7));

    public InMemoryExecutionStorePolicy {
        maximumLeaseTtl = positive(maximumLeaseTtl, "maximumLeaseTtl");
        if (maximumPayloadBytes < 1) {
            throw new IllegalArgumentException("maximumPayloadBytes must be positive");
        }
        maximumClockSkew = Objects.requireNonNull(maximumClockSkew, "maximumClockSkew");
        if (maximumClockSkew.isNegative()) {
            throw new IllegalArgumentException("maximumClockSkew cannot be negative");
        }
        journalRetention = positive(journalRetention, "journalRetention");
        if (maximumInventoryPageSize < 1) {
            throw new IllegalArgumentException("maximumInventoryPageSize must be positive");
        }
        terminalRetention = positive(terminalRetention, "terminalRetention");
        executionResultRetention = positive(executionResultRetention, "executionResultRetention");
        if (terminalRetention.compareTo(journalRetention) < 0) {
            throw new IllegalArgumentException("terminalRetention " + terminalRetention
                    + " cannot be shorter than journalRetention " + journalRetention
                    + ": events would outlive the instance they name");
        }
        if (terminalRetention.compareTo(executionResultRetention) < 0) {
            throw new IllegalArgumentException("terminalRetention " + terminalRetention
                    + " cannot be shorter than executionResultRetention " + executionResultRetention
                    + ": results would outlive the instance they name");
        }
    }

    private static Duration positive(Duration value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
