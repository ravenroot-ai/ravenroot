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
     * Runs {@code work} against a connection in autocommit, for reads that need no transaction.
     *
     * <p>A single statement is already atomic, and wrapping one read in an explicit transaction buys
     * nothing but a second round trip. Multi-statement reads that must see one consistent snapshot use
     * {@link #inTransaction} instead, and the distinction is deliberate rather than incidental: a read
     * that folds an aggregate from several tables is not correct here.</p>
     */
    <T> T readOnly(Work<T> work) throws SQLException {
        try (Connection connection = open()) {
            return work.run(connection);
        }
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
