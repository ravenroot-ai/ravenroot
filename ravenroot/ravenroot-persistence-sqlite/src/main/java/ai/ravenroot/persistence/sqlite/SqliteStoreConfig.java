package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.StoreCapability;

import java.time.Duration;
import java.util.Objects;

/**
 * Durability and limit settings for {@link SqliteExecutionStore}.
 *
 * <p>Deliberately not here: database path, retention policy, backup and restore. Those are the
 * operational surface; this record carries only what changes the adapter's
 * <em>declared semantics</em>, so that everything an operator can turn is visibly connected to a
 * capability or a bound the port publishes.</p>
 *
 * @param synchronousMode how far a commit is flushed before it is reported as durable
 * @param busyTimeout     how long a statement waits for a writer lock held by another connection
 *                        before failing; see {@link SqliteExecutionStore} for why this is what makes
 *                        cross-process contention a wait rather than an error
 * @param maxLeaseTtl     the bound published by {@link SqliteExecutionStore#maxLeaseTtl()}
 * @param maxPayloadBytes the bound published by {@link SqliteExecutionStore#maxPayloadBytes()}
 * @param maxClockSkew    the budget published by {@link SqliteExecutionStore#maxClockSkew()}
 */
public record SqliteStoreConfig(SynchronousMode synchronousMode, Duration busyTimeout,
                                Duration maxLeaseTtl, int maxPayloadBytes, Duration maxClockSkew,
                                Duration journalRetention) {

    /**
     * How far SQLite flushes on commit.
     *
     * <p>{@link #FULL} is the default and is not a conservative preference, it is what makes
     * {@link StoreCapability#DURABLE} true. Under {@link #NORMAL} in WAL mode a commit is <em>not</em>
     * fsynced; it becomes durable only at the next checkpoint, so a power loss or kernel panic between
     * the two loses transactions that the store already reported as applied. An adapter declaring
     * {@code DURABLE} while defaulting to {@code NORMAL} would be declaring something it does not do,
     * and — the reason this matters more than it first appears — a kill test would not catch it: a
     * {@code SIGKILL} destroys the process but leaves the page cache intact, so unsynced commits still
     * reach the file and the test passes. {@code NORMAL} is honest only where the whole host failing
     * is out of scope.</p>
     */
    public enum SynchronousMode {
        /** Commit is fsynced. Required for {@link StoreCapability#DURABLE} against host failure. */
        FULL("FULL"),
        /** Commit is not fsynced in WAL mode; durable only against process death, not host failure. */
        NORMAL("NORMAL"),
        /** No syncing at all. Never compatible with {@link StoreCapability#DURABLE}. */
        OFF("OFF");

        private final String pragmaValue;

        SynchronousMode(String pragmaValue) {
            this.pragmaValue = pragmaValue;
        }

        String pragmaValue() {
            return pragmaValue;
        }
    }

    /**
     * Five minutes of lease TTL, one mebibyte of payload, five seconds of clock skew, five seconds of
     * busy timeout, and {@link SynchronousMode#FULL}.
     *
     * <p>The three published bounds match {@code InMemoryExecutionStore}'s so that a deployment can
     * swap adapters without a caller discovering a different limit, and two of them are additionally
     * constrained by the conformance suite: the payload limit must stay under 64 MiB or the
     * payload-rejection assertion refuses to allocate a payload large enough to exceed it and skips,
     * and the skew budget must exceed two seconds or the ambiguity-window assertion has no room to
     * probe and skips. Both are the kind of threshold that quietly turns a passing assertion into an
     * unrun one, so they are stated here rather than discovered.</p>
     */
    public static SqliteStoreConfig defaults() {
        return new SqliteStoreConfig(SynchronousMode.FULL, Duration.ofSeconds(5), Duration.ofMinutes(5),
                1024 * 1024, Duration.ofSeconds(5), Duration.ofHours(24));
    }

    public SqliteStoreConfig {
        Objects.requireNonNull(synchronousMode, "synchronousMode");
        Objects.requireNonNull(busyTimeout, "busyTimeout");
        Objects.requireNonNull(maxLeaseTtl, "maxLeaseTtl");
        Objects.requireNonNull(maxClockSkew, "maxClockSkew");
        if (busyTimeout.isNegative()) {
            throw new IllegalArgumentException("busyTimeout cannot be negative");
        }
        if (maxLeaseTtl.isZero() || maxLeaseTtl.isNegative()) {
            throw new IllegalArgumentException("maxLeaseTtl must be positive");
        }
        if (maxPayloadBytes < 1) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
        if (maxClockSkew.isNegative()) {
            throw new IllegalArgumentException("maxClockSkew cannot be negative");
        }
        Objects.requireNonNull(journalRetention, "journalRetention");
        if (journalRetention.isZero() || journalRetention.isNegative()) {
            throw new IllegalArgumentException("journalRetention must be positive");
        }
    }

    public SqliteStoreConfig withSynchronousMode(SynchronousMode mode) {
        return new SqliteStoreConfig(mode, busyTimeout, maxLeaseTtl, maxPayloadBytes, maxClockSkew,
                journalRetention);
    }

    public SqliteStoreConfig withBusyTimeout(Duration timeout) {
        return new SqliteStoreConfig(synchronousMode, timeout, maxLeaseTtl, maxPayloadBytes, maxClockSkew,
                journalRetention);
    }

    /**
     * How long journal records are retained before {@code compactJournal} may discard them.
     *
     * <p>Twenty-four hours by default. It is deployment configuration rather than a product promise —
     * ADR 0010 defers concrete retention values — but it has to be a declared number, because it is
     * exactly the bound a disconnected consumer needs in order to know whether resuming is still
     * possible.</p>
     */
    public SqliteStoreConfig withJournalRetention(Duration retention) {
        return new SqliteStoreConfig(synchronousMode, busyTimeout, maxLeaseTtl, maxPayloadBytes,
                maxClockSkew, retention);
    }
}
