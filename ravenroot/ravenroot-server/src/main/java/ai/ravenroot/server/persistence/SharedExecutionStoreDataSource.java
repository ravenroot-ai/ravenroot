package ai.ravenroot.server.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * The one place a connection pool is constructed, and the only file in this repository that names
 * HikariCP.
 *
 * <h2>Why the composition root pools and the adapter does not</h2>
 * <p>The shared adapter takes a {@link DataSource} and deliberately does not pool: pool sizing,
 * credentials, TLS material and acquisition timeouts are operational surface, and an adapter that
 * owned them would be publishing bounds it does not control. That decision is only correct if
 * somebody else pools. A {@code DataSource} that opened a connection per operation would satisfy the
 * same interface and be a defect rather than a deferral — every store operation is a transaction, a
 * PostgreSQL connection is a server-side process, and paying a process start per lease renewal is not
 * a slower version of the right design but a different and wrong one.</p>
 *
 * <h2>Why this is one small file</h2>
 * <p>So that replacing the pool is a one-file change. Nothing outside this class sees a Hikari type:
 * the return is {@link Pool}, whose {@code dataSource()} is the JDBC interface and whose
 * {@code close()} is the only lifecycle. A future move to another pool, or to a deployment-supplied
 * {@code DataSource} handed in by an embedder, edits this file and nothing else.</p>
 *
 * <h2>What is deliberately not configured here</h2>
 * <p>No statement cache, no leak-detection threshold, no minimum idle below the maximum, no
 * connection test query. Each would be a bound this process invented on the deployment's behalf, and
 * two of them (idle sizing, leak detection) interact with the adapter's own lock and statement
 * timeouts in ways that would need measurement before they could be defended. The pool is sized, it
 * is bounded in how long it makes a caller wait, and it is auto-commit off — nothing else.</p>
 */
public final class SharedExecutionStoreDataSource {

    private SharedExecutionStoreDataSource() {
    }

    /**
     * Builds the pool for the shared store.
     *
     * @param connection the operator's connection settings.
     * @return a pool the caller owns and must close.
     */
    public static Pool open(SharedStoreConnection connection) {
        Objects.requireNonNull(connection, "connection");
        var config = new HikariConfig();
        config.setJdbcUrl(connection.url());
        connection.user().ifPresent(config::setUsername);
        connection.password().ifPresent(config::setPassword);
        config.setMaximumPoolSize(connection.poolSize());
        config.setConnectionTimeout(connection.poolTimeout().toMillis());
        // Off, because every store operation runs inside a transaction the adapter opens and commits
        // itself. Leaving it on would let a statement the adapter believes is inside a transaction
        // commit on its own, which is the failure mode that makes a compare-and-set stop being one.
        config.setAutoCommit(false);
        // Named so a thread dump, a pg_stat_activity row and a Hikari diagnostic all say which pool.
        // Not derived from the URL or the role: both are secret-bearing and this name is printed.
        config.setPoolName("ravenroot-execution-store");
        try {
            return new Pool(new HikariDataSource(config));
        } catch (RuntimeException failed) {
            // The pool's own message quotes the JDBC URL when it cannot reach the server. Replacing
            // it rather than wrapping it is the point: a startup diagnostic that prints the URL
            // prints the deployment's topology, and often its credentials.
            throw new IllegalStateException("Shared execution store connection pool could not be created");
        }
    }

    /** An owned pool, closed exactly once by whoever opened it. */
    public static final class Pool implements AutoCloseable {
        private final HikariDataSource dataSource;

        private Pool(HikariDataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        }

        /** The JDBC interface the adapters are handed; they never learn the implementation. */
        public DataSource dataSource() {
            return dataSource;
        }

        @Override
        public void close() {
            dataSource.close();
        }
    }
}
