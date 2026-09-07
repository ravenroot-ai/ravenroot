package ai.ravenroot.api.persistence;

import java.time.Duration;
import java.util.Objects;

/**
 * Shared execution-store limits and retention windows, independent of the storage adapter.
 *
 * <p>Terminal instances must outlive both their journal and their results. Otherwise an instance
 * purge would leave dangling events or silently shorten the configured result window. Existing
 * stored terminal and result deadlines remain fixed; choosing a policy does not rewrite them.
 * Connection settings and deployment-registry limits belong to their own policies.</p>
 *
 * @param maxLeaseTtl longest accepted lease duration
 * @param maxPayloadBytes largest accepted encoded payload, also used for execution-result payloads
 * @param maxClockSkew nonnegative clock-skew budget
 * @param journalRetention positive journal retention window
 * @param maxInventoryPageSize largest accepted process-inventory page
 * @param terminalRetention positive terminal-instance retention, at least the journal and result windows
 * @param executionResultRetention positive terminal-result retention window
 */
public record ExecutionStorePolicy(Duration maxLeaseTtl, int maxPayloadBytes, Duration maxClockSkew,
                                   Duration journalRetention, int maxInventoryPageSize,
                                   Duration terminalRetention, Duration executionResultRetention) {

    /** Authoritative defaults shared by the in-memory, SQLite and PostgreSQL adapters. */
    public static final ExecutionStorePolicy DEFAULTS = new ExecutionStorePolicy(
            Duration.ofMinutes(5), 1024 * 1024, Duration.ofSeconds(5), Duration.ofHours(24), 100,
            Duration.ofDays(7), Duration.ofDays(7));

    public ExecutionStorePolicy {
        positive(maxLeaseTtl, "maxLeaseTtl");
        if (maxPayloadBytes < 1) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
        Objects.requireNonNull(maxClockSkew, "maxClockSkew");
        if (maxClockSkew.isNegative()) {
            throw new IllegalArgumentException("maxClockSkew cannot be negative");
        }
        positive(journalRetention, "journalRetention");
        if (maxInventoryPageSize < 1) {
            throw new IllegalArgumentException("maxInventoryPageSize must be positive");
        }
        positive(terminalRetention, "terminalRetention");
        positive(executionResultRetention, "executionResultRetention");
        if (terminalRetention.compareTo(journalRetention) < 0) {
            throw new IllegalArgumentException("terminalRetention cannot be shorter than journalRetention");
        }
        if (terminalRetention.compareTo(executionResultRetention) < 0) {
            throw new IllegalArgumentException("terminalRetention cannot be shorter than executionResultRetention");
        }
    }

    private static void positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
