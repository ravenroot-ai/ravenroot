package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.ExecutionStorePolicy;

import java.time.Duration;
import java.util.Objects;

/**
 * Contention and limit settings for the PostgreSQL adapters.
 *
 * <p>Deliberately not here: the JDBC URL, credentials, TLS material, pool size, retention
 * scheduling, backup and restore. Those are the operational surface and reach this package only
 * through the {@link javax.sql.DataSource} it is handed. This record carries only what changes the
 * adapter's <em>declared semantics</em>, so that everything an operator can turn is visibly
 * connected to a capability or to a bound the port publishes.</p>
 *
 * <p>Terminal retention must cover both journal and execution-result retention, as in the other
 * adapters. A longer result window is rejected: purging its process instance cascades to the result
 * row and could not honor that window.</p>
 *
 * @param lockTimeout          how long a statement waits for a row lock held by another transaction
 *                             before failing. This is what turns contention between two hosts into a
 *                             wait rather than an immediate error, and it is bounded rather than
 *                             infinite so that a lost holder cannot stall a worker indefinitely
 * @param statementTimeout     the ceiling on any single statement, applied per connection so that a
 *                             pathological query cannot hold a row lock for longer than this
 * @param serializationRetries how many times a transaction that PostgreSQL aborted with a
 *                             serialization failure or a deadlock is retried before the failure is
 *                             reported as {@link ai.ravenroot.api.persistence.ExecutionStoreFailure.Unavailable}.
 *                             Retrying is safe because every retried transaction re-reads the state
 *                             it decides on: the fencing, replay and expectation checks run again
 *                             inside the new attempt, so a retry either reaches the same decision or
 *                             correctly reaches a different one
 * @param maxLeaseTtl          the bound published by
 *                             {@link ai.ravenroot.api.persistence.ExecutionStore#maxLeaseTtl()}
 * @param maxPayloadBytes      the bound published by
 *                             {@link ai.ravenroot.api.persistence.ExecutionStore#maxPayloadBytes()}
 * @param maxClockSkew         the budget published by
 *                             {@link ai.ravenroot.api.persistence.ExecutionStore#maxClockSkew()};
 *                             with several hosts this is also the skew between their clocks that
 *                             lease evaluation tolerates
 * @param journalRetention     the window published by
 *                             {@link ai.ravenroot.api.persistence.ExecutionStore#journalRetention()}
 * @param maxInventoryPageSize the bound published by
 *                             {@link ai.ravenroot.api.persistence.ExecutionStore#maxInventoryPageSize()}
 * @param terminalRetention    the window published by
 *                             {@link ai.ravenroot.api.persistence.ExecutionStore#terminalRetention()}
 * @param executionResultRetention the window published by
 *                             {@link ai.ravenroot.api.persistence.ExecutionStore#executionResultRetention()}
 */
public record PostgresStoreConfig(Duration lockTimeout, Duration statementTimeout,
                                  int serializationRetries, Duration maxLeaseTtl, int maxPayloadBytes,
                                  Duration maxClockSkew, Duration journalRetention,
                                  int maxInventoryPageSize, Duration terminalRetention,
                                  Duration executionResultRetention) {

    /**
     * Five seconds of lock timeout, thirty of statement timeout, three serialization retries, and the
     * same published bounds the other adapters declare.
     *
     * <p>The bounds match {@code SqliteStoreConfig.defaults()} and {@code InMemoryExecutionStore}
     * exactly, so a deployment can move between adapters without a caller discovering a different
     * limit. Two of them are additionally constrained by the conformance suite rather than by taste:
     * the payload limit must stay under 64 MiB or the payload-rejection assertion cannot allocate a
     * payload large enough to exceed it and skips, and the skew budget must exceed two seconds or the
     * ambiguity-window assertion has no room to probe and skips. Both are thresholds that quietly
     * turn a passing assertion into an unrun one, so they are stated here rather than discovered.</p>
     *
     * <p>{@code serializationRetries} is three because a retry only helps against a conflict that has
     * already resolved; a transaction still losing after three attempts is contending with something
     * structural rather than with a transient overlap, and reporting
     * {@link StoreCapability#TRANSACTIONAL_BATCH}-preserving unavailability is a better answer to the
     * caller than an unbounded retry that hides it.</p>
     */
    public static PostgresStoreConfig defaults() {
        return new PostgresStoreConfig(Duration.ofSeconds(5), Duration.ofSeconds(30), 3,
                ExecutionStorePolicy.DEFAULTS);
    }

    /** Composes PostgreSQL contention settings with the shared execution-store policy. */
    public PostgresStoreConfig(Duration lockTimeout, Duration statementTimeout, int serializationRetries,
                               ExecutionStorePolicy policy) {
        this(lockTimeout, statementTimeout, serializationRetries, policy.maxLeaseTtl(), policy.maxPayloadBytes(),
                policy.maxClockSkew(), policy.journalRetention(), policy.maxInventoryPageSize(),
                policy.terminalRetention(), policy.executionResultRetention());
    }

    public PostgresStoreConfig {
        Objects.requireNonNull(lockTimeout, "lockTimeout");
        Objects.requireNonNull(statementTimeout, "statementTimeout");
        if (lockTimeout.isZero() || lockTimeout.isNegative()) {
            // Zero means "wait forever" to PostgreSQL, which is the one value this adapter cannot
            // accept: a worker blocked on a row a lost holder still locks would never reach the
            // lease expiry that is supposed to free it.
            throw new IllegalArgumentException("lockTimeout must be positive");
        }
        if (statementTimeout.isZero() || statementTimeout.isNegative()) {
            throw new IllegalArgumentException("statementTimeout must be positive");
        }
        if (statementTimeout.compareTo(lockTimeout) < 0) {
            // A statement timeout below the lock timeout makes the lock timeout unreachable, so
            // contention would surface as a generic statement abort rather than as the lock-wait it
            // actually is, and the two are not the same diagnosis for an operator.
            throw new IllegalArgumentException("statementTimeout " + statementTimeout
                    + " cannot be shorter than lockTimeout " + lockTimeout);
        }
        if (serializationRetries < 0) {
            throw new IllegalArgumentException("serializationRetries cannot be negative");
        }
        new ExecutionStorePolicy(maxLeaseTtl, maxPayloadBytes, maxClockSkew, journalRetention,
                maxInventoryPageSize, terminalRetention, executionResultRetention);
    }

    /** Returns the shared semantic policy without PostgreSQL contention settings. */
    public ExecutionStorePolicy executionStorePolicy() {
        return new ExecutionStorePolicy(maxLeaseTtl, maxPayloadBytes, maxClockSkew, journalRetention,
                maxInventoryPageSize, terminalRetention, executionResultRetention);
    }
}
