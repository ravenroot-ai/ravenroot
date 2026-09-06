package ai.ravenroot.persistence.postgresql;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

/**
 * The one transaction boundary every operation in this package goes through.
 *
 * <h2>What it guarantees, and why it is a single place</h2>
 * <p>Three properties have to hold for every statement this adapter runs, and each of them is the
 * kind that is correct in the code that remembers it and silently wrong everywhere else. A write must
 * be one transaction with one {@code COMMIT}. A transaction the server aborted for a serialization
 * conflict or a deadlock must be retried rather than reported, because those two outcomes mean "this
 * did not happen" and the port has no vocabulary in which a caller could be told to try again. And a
 * failure of {@code COMMIT} itself must be distinguished from every other failure, because only that
 * one leaves the outcome genuinely unknown. Spreading those three across ninety call sites is how an
 * adapter comes to be correct in most of its operations.</p>
 *
 * <h2>Why retrying is sound</h2>
 * <p>A retried transaction re-reads everything it decides on. Fencing tokens, revisions, lease
 * holders and idempotency records are all read inside the transaction that acts on them, so a second
 * attempt either reaches the same decision against unchanged state, or correctly reaches a different
 * one because the state genuinely changed while it was losing. What makes this safe is a rule the
 * callers must keep: <strong>the work passed here must not mutate anything outside the transaction
 * and must not be assumed to run once.</strong> Nothing here can enforce that, so it is stated as the
 * contract of the parameter.</p>
 *
 * <h2>Why {@code READ COMMITTED} and explicit row locks</h2>
 * <p>This adapter does not run at {@code SERIALIZABLE}. Under {@code READ COMMITTED} the conflicts it
 * has to survive are the ones it creates deliberately — a row it locked with {@code FOR UPDATE}, or a
 * conditional update whose {@code WHERE} carries the value the decision was made on — and those
 * produce a bounded, well-understood retry rate. {@code SERIALIZABLE} would additionally abort
 * transactions for read-write dependencies the adapter never reasons about, converting correctness
 * that is already enforced by explicit locks into a retry storm under load. The explicit locks are not
 * an optimisation over the stronger isolation level; they are the mechanism, and the isolation level
 * is chosen to match them.</p>
 *
 * <h2>Bounds are set per connection, not per deployment</h2>
 * <p>{@code lock_timeout} and {@code statement_timeout} are applied to every connection this class
 * hands out, rather than left to the server's configuration. An adapter whose contention behaviour
 * depended on a setting it does not control could not publish the bounds the port requires it to, and
 * a deployment that left {@code lock_timeout} at its default of zero would give a worker an unbounded
 * wait on a row whose holder has already gone.</p>
 */
final class Transactions {

    /** Work that runs inside one transaction. Must be free of external side effects; may run twice. */
    @FunctionalInterface
    interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private final DataSource dataSource;
    private final PostgresStoreConfig config;
    private final CommitBoundary commitBoundary;

    Transactions(DataSource dataSource, PostgresStoreConfig config, CommitBoundary commitBoundary) {
        this.dataSource = dataSource;
        this.config = config;
        this.commitBoundary = commitBoundary;
    }

    /**
     * Runs {@code work} in one transaction, retrying a serialization failure or deadlock.
     *
     * @throws OutcomeUnknownException when {@code COMMIT} neither succeeded nor demonstrably failed
     * @throws SQLException            for every other database failure, for the caller to classify
     */
    <T> T inTransaction(Work<T> work) throws SQLException {
        SQLException lastRetryable = null;
        for (int attempt = 0; attempt <= config.serializationRetries(); attempt++) {
            try (Connection connection = open()) {
                connection.setAutoCommit(false);
                T result;
                try {
                    result = work.run(connection);
                } catch (SQLException | RuntimeException failed) {
                    safeRollback(connection);
                    if (failed instanceof SQLException sql && SqlStates.isRetryable(sql)) {
                        lastRetryable = sql;
                        continue;
                    }
                    throw failed;
                }
                commitBoundary.beforeCommit();
                try {
                    connection.commit();
                } catch (SQLException failed) {
                    if (SqlStates.isRetryable(failed)) {
                        // The server aborted the transaction and told us so: nothing was applied, and
                        // this is the one commit-phase failure whose outcome is not in doubt.
                        lastRetryable = failed;
                        continue;
                    }
                    throw new OutcomeUnknownException(
                            "the transaction's commit did not report success or a definite failure", failed);
                }
                commitBoundary.afterCommit();
                return result;
            }
        }
        throw lastRetryable;
    }

    /**
     * Runs {@code work} against a connection in autocommit, for reads of a <em>single</em> statement.
     *
     * <p>One statement is already atomic, and wrapping it in an explicit transaction buys nothing but
     * a second round trip. A read that issues more than one statement and needs them to agree must
     * use {@link #readConsistent} instead — see the reason there, which is not the reason one would
     * guess.</p>
     */
    <T> T readOnly(Work<T> work) throws SQLException {
        try (Connection connection = open()) {
            return work.run(connection);
        }
    }

    /**
     * Runs a multi-statement read that must see one consistent snapshot.
     *
     * <h2>Why this is not simply {@link #inTransaction}</h2>
     * <p>Opening a transaction is not what makes several statements agree. Under
     * {@code READ COMMITTED} — the level everything else in this adapter runs at, and PostgreSQL's
     * default — <strong>every statement takes a fresh snapshot, transaction or not</strong>. A fold
     * that reads an instance's revision and then its traversals, invocations, causal edges and
     * attempts would therefore see up to five different committed states while looking exactly like
     * an atomic read, and would return an aggregate whose revision does not describe the state beside
     * it. Worse, the fold checks references <em>across</em> those reads: an invocation observed in one
     * snapshot can name a traversal absent from the next, and the adapter would report
     * {@link ai.ravenroot.api.persistence.ExecutionStoreFailure.Corrupted} — its loudest signal — for
     * a database that is perfectly healthy and merely busy.</p>
     *
     * <p>{@code REPEATABLE READ} takes one snapshot at the first statement and holds it for the whole
     * transaction, which is exactly and only what a fold needs. It costs nothing extra for a read-only
     * transaction: PostgreSQL's snapshot isolation means readers still never block writers and are
     * never blocked by them. Retries are kept because a serialization failure remains possible, and a
     * read is the safest possible thing to retry.</p>
     *
     * <p>This is deliberately a separate method rather than a stricter default for every read. A
     * single-statement read gains nothing from it and would pay a round trip, and a reader that has
     * to choose is a reader who has to think about whether their statements must agree.</p>
     */
    <T> T readConsistent(Work<T> work) throws SQLException {
        SQLException lastRetryable = null;
        for (int attempt = 0; attempt <= config.serializationRetries(); attempt++) {
            try (Connection connection = open()) {
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setAutoCommit(false);
                try {
                    T result = work.run(connection);
                    connection.commit();
                    return result;
                } catch (SQLException failed) {
                    safeRollback(connection);
                    if (SqlStates.isRetryable(failed)) {
                        lastRetryable = failed;
                        continue;
                    }
                    throw failed;
                } catch (RuntimeException failed) {
                    safeRollback(connection);
                    throw failed;
                } finally {
                    // The isolation level is set while autocommit is still on, which the driver sends
                    // as a session-scoped change rather than a transaction-scoped one. This adapter's
                    // own open() resets it on every acquisition, so it would never notice - but the
                    // module accepts whatever DataSource a deployment hands it, and a pool that does
                    // not reset on return would lend a REPEATABLE READ session to the next borrower,
                    // who may not be Ravenroot at all. Handing a connection back at the server's
                    // default is cheap and is not this adapter's judgement call to skip.
                    //
                    // ORDER-SENSITIVE: this runs immediately after the commit or the rollback, and
                    // nothing may be placed between them. PostgreSQL refuses an isolation change in
                    // the middle of a transaction, and restoreReadCommitted swallows that refusal
                    // because it has no better answer on a failing path - so a statement inserted
                    // above would not break the build or fail a test, it would silently stop the
                    // restore from happening at all.
                    restoreReadCommitted(connection);
                }
            }
        }
        throw lastRetryable;
    }

    private Connection open() throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            applyBounds(connection);
            return connection;
        } catch (SQLException | RuntimeException failed) {
            closeQuietly(connection);
            throw failed;
        }
    }

    /**
     * Applies the two bounds to this connection.
     *
     * <p>The values are interpolated rather than bound, because {@code SET} does not take parameters.
     * That is safe here and only here: both are millisecond counts derived from a
     * {@link Duration} in a validated record, so nothing reaches this string that was not already a
     * {@code long}. No other statement in this package builds SQL from a value.</p>
     */
    private void applyBounds(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET lock_timeout = " + millis(config.lockTimeout()));
            statement.execute("SET statement_timeout = " + millis(config.statementTimeout()));
        }
    }

    private static long millis(Duration duration) {
        long value = duration.toMillis();
        // A sub-millisecond bound would round to zero, and zero means "no limit" to PostgreSQL - the
        // exact opposite of what a caller asking for a very short timeout meant.
        return Math.max(1L, value);
    }

    /**
     * Returns the connection to {@code READ COMMITTED}, which is the server's default and this
     * adapter's own level for everything else.
     *
     * <p>Not literally "as it was found": {@link #open} already overwrites a borrowed connection's
     * level unconditionally, so a pool lending a {@code SERIALIZABLE} session gets it back at
     * {@code READ COMMITTED} either way. What this closes is the case that would otherwise be this
     * adapter's own doing — a session left at the stricter level by a read that raised it.</p>
     */
    private static void restoreReadCommitted(Connection connection) {
        try {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        } catch (SQLException ignored) {
            // The connection is being closed regardless, and a failure to reset it means it is already
            // gone - which the pool will discover for itself and which must not replace the caller's
            // original failure.
        }
    }

    private static void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The caller is already propagating the original failure, and a rollback that itself
            // fails means the connection is gone, which is the same diagnosis reported worse if it
            // replaces the cause.
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing useful can be done with a failure to close a connection that is already being
            // discarded because opening it did not complete.
        }
    }
}
