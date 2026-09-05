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
 * @param journalRetention the window published by {@link SqliteExecutionStore#journalRetention()}
 * @param maxInventoryPageSize the bound published by
 *                        {@link SqliteExecutionStore#maxInventoryPageSize()}
 * @param terminalRetention the window published by
 *                        {@link SqliteExecutionStore#terminalRetention()}
 * @param executionResultRetention the window published by
 *                        {@link SqliteExecutionStore#executionResultRetention()}
 */
public record SqliteStoreConfig(SynchronousMode synchronousMode, Duration busyTimeout,
                                Duration maxLeaseTtl, int maxPayloadBytes, Duration maxClockSkew,
                                Duration journalRetention, int maxInventoryPageSize,
                                Duration terminalRetention, Duration executionResultRetention) {

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
                1024 * 1024, Duration.ofSeconds(5), Duration.ofHours(24), 100, Duration.ofDays(7),
                Duration.ofDays(7));
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
        if (maxInventoryPageSize < 1) {
            throw new IllegalArgumentException("maxInventoryPageSize must be positive");
        }
        Objects.requireNonNull(terminalRetention, "terminalRetention");
        if (terminalRetention.isZero() || terminalRetention.isNegative()) {
            throw new IllegalArgumentException("terminalRetention must be positive");
        }
        if (terminalRetention.compareTo(journalRetention) < 0) {
            // A terminal instance pruned while its own events are still readable would leave the
            // journal naming a process instance the inventory can no longer describe, and a consumer
            // replaying those events would resolve every one of them to "never existed". The
            // inventory row is the cheaper of the two to keep, so it outlives the events rather than
            // the other way round.
            throw new IllegalArgumentException("terminalRetention " + terminalRetention
                    + " cannot be shorter than journalRetention " + journalRetention
                    + ": events would outlive the instance they name");
        }
        Objects.requireNonNull(executionResultRetention, "executionResultRetention");
        if (executionResultRetention.isZero() || executionResultRetention.isNegative()) {
            throw new IllegalArgumentException("executionResultRetention must be positive");
        }
        if (terminalRetention.compareTo(executionResultRetention) < 0) {
            // Same rule as the journal window above, in the same direction, and for the same reason:
            // no retained record may name a record that has already been purged. A result carries the
            // process instance and traversal it belongs to, so a result surviving its instance would
            // name a row the inventory can no longer describe. The instance row is the cheaper of the
            // two to keep -- it is a handful of columns, while a result carries a payload -- so it
            // outlives the result rather than the other way round.
            //
            // The schema makes the violation unreachable anyway: execution_result cascades from
            // process_instance, so purging an instance takes its results with it. That is exactly why
            // the check is here rather than left implicit. Without it a longer result window would be
            // accepted and then silently not honoured, and an operator who configured thirty days of
            // results behind seven days of instances would discover the real number during an
            // investigation.
            throw new IllegalArgumentException("terminalRetention " + terminalRetention
                    + " cannot be shorter than executionResultRetention " + executionResultRetention
                    + ": results would outlive the instance they name");
        }
    }

    public SqliteStoreConfig withSynchronousMode(SynchronousMode mode) {
        return new SqliteStoreConfig(mode, busyTimeout, maxLeaseTtl, maxPayloadBytes, maxClockSkew,
                journalRetention, maxInventoryPageSize, terminalRetention, executionResultRetention);
    }

    public SqliteStoreConfig withBusyTimeout(Duration timeout) {
        return new SqliteStoreConfig(synchronousMode, timeout, maxLeaseTtl, maxPayloadBytes, maxClockSkew,
                journalRetention, maxInventoryPageSize, terminalRetention, executionResultRetention);
    }

    /**
     * How long a terminal process instance stays discoverable before
     * {@link SqliteExecutionStore#purgeExpiredProcessInstances(String)} may remove it.
     *
     * <p>Seven days by default. The number is chosen against two constraints rather than picked for
     * roundness. It must span a weekend plus a working day, because an execution that fails late on a
     * Friday has to still be discoverable when somebody looks on Monday morning — a 24- or 48-hour
     * window makes the commonest investigation impossible for reasons of the calendar. And it must be
     * at least {@link #journalRetention()}, which the canonical constructor enforces, so a terminal
     * instance is never pruned while its own events are still readable.</p>
     *
     * <p>It is deployment configuration and not a product promise, exactly as journal retention is.
     * Lowering it below the journal window is rejected rather than accepted and worked around,
     * because the resulting dangling events would be discovered as a diagnosis failure months
     * later.</p>
     * @param retention how long terminal instances are retained.
     * @return a copy of this configuration with the given terminal retention.
     */
    public SqliteStoreConfig withTerminalRetention(Duration retention) {
        return new SqliteStoreConfig(synchronousMode, busyTimeout, maxLeaseTtl, maxPayloadBytes,
                maxClockSkew, journalRetention, maxInventoryPageSize, retention, executionResultRetention);
    }

    /**
     * The largest inventory page this store will return, rejecting anything above it rather than
     * clamping.
     * @param maximum largest accepted page size.
     * @return a copy of this configuration with the given page bound.
     */
    public SqliteStoreConfig withMaxInventoryPageSize(int maximum) {
        return new SqliteStoreConfig(synchronousMode, busyTimeout, maxLeaseTtl, maxPayloadBytes,
                maxClockSkew, journalRetention, maximum, terminalRetention, executionResultRetention);
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
                maxClockSkew, retention, maxInventoryPageSize, terminalRetention, executionResultRetention);
    }

    /**
     * How long a recorded terminal result stays readable before
     * {@link SqliteExecutionStore#purgeExpiredExecutionResults(String)} may remove it, and before a
     * read stops offering its payload.
     *
     * <p>Seven days by default, matching {@link #terminalRetention()} rather than picking an
     * independent number. The two windows answer the same operational question — how long after a run
     * ends can somebody still find out what happened — and defaulting them apart would mean a
     * terminal execution that is discoverable in the inventory and whose result has silently gone,
     * for no reason an operator asked for.</p>
     *
     * <p>It may be shortened freely, and that is the case this knob exists for: a result carries a
     * payload while an inventory row does not, so an operator under storage pressure reduces this one
     * first. Lengthening it beyond {@link #terminalRetention()} is rejected rather than accepted and
     * quietly not honoured — the result table cascades from {@code process_instance}, so a longer
     * window would be cut short by the instance purge and the configured number would be a
     * fiction.</p>
     *
     * @param retention how long recorded results are retained.
     * @return a copy of this configuration with the given result retention.
     */
    public SqliteStoreConfig withExecutionResultRetention(Duration retention) {
        return new SqliteStoreConfig(synchronousMode, busyTimeout, maxLeaseTtl, maxPayloadBytes,
                maxClockSkew, journalRetention, maxInventoryPageSize, terminalRetention, retention);
    }
}
