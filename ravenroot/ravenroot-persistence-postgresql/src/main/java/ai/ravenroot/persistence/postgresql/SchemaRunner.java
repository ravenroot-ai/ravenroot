package ai.ravenroot.persistence.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The forward-only migration runner, serialized across concurrently starting processes.
 *
 * <h2>Why an advisory lock and not {@code IF NOT EXISTS}</h2>
 * <p>The single-host adapter gets concurrent-startup safety for free: SQLite's write lock admits one
 * writer to the whole database, so two processes opening the same file cannot interleave DDL. Here
 * the processes are on different hosts and PostgreSQL will happily run two migrations at once. Making
 * each statement idempotent does not fix it — two transactions can both observe version 4, both
 * decide to apply 5, and the loser then fails on a constraint that the winner created a moment
 * earlier, turning an ordinary concurrent start into a crash loop. Worse, a migration that both
 * inserts and creates would apply its data half twice.</p>
 *
 * <p>{@code pg_advisory_xact_lock} on a fixed key is taken before the version is read and released by
 * the transaction that read it, so the read-decide-apply sequence is atomic against every other
 * process addressing this database. A process that arrives second blocks, then observes the version
 * the winner installed and finds nothing left to do. The lock is transaction-scoped rather than
 * session-scoped precisely so that a process killed mid-migration cannot leave it held: the backend's
 * transaction ends when the connection dies, and the lock ends with it.</p>
 *
 * <h2>Where the version lives</h2>
 * <p>PostgreSQL has no file-header integer, so {@code store_schema_version} is a one-row table and is
 * the authority. It is created outside the migration list, under the same advisory lock, because
 * reading it must not itself require a migration to have run. {@code store_schema_history} is the
 * audit trail: written in the same transaction so it cannot disagree, and present because an operator
 * inspecting a database needs to see what ran and when rather than only a number.</p>
 *
 * <h2>One transaction per step</h2>
 * <p>Each migration commits separately, so a run interrupted between two steps leaves the database at
 * a real, complete intermediate version and the next start resumes from exactly that point. The
 * advisory lock is re-taken per step, which is what makes that resumption safe when the process that
 * resumes is not the one that stopped.</p>
 *
 * <h2>The downgrade guard</h2>
 * <p>A database whose version exceeds the highest this binary knows is refused rather than opened. An
 * older binary cannot see the columns it does not know about, so it would write rows the newer binary
 * later reads as incomplete: silent, and discovered as corruption long afterwards. Refusing is loud
 * and reversible, and during a rolling upgrade it is the correct answer — the old pods should fail to
 * start, not quietly write a shape the new schema no longer means.</p>
 */
final class SchemaRunner {

    /**
     * The advisory-lock key, chosen once and never derived from anything that could change.
     *
     * <p>Advisory locks share one namespace per database, so this number is a name: another
     * application using the same database and the same number would block against this migration for
     * reasons neither side could diagnose. It is recorded here so that a deployment sharing a database
     * has something to check against, which is also why the operator documentation recommends a
     * dedicated schema.</p>
     */
    static final long MIGRATION_LOCK_KEY = 0x52_41_56_4E_44_42L;

    private SchemaRunner() {
    }

    /**
     * Applies every migration above the database's current version, in ascending order.
     *
     * @return the version the database is at when this returns
     */
    static int migrate(Connection connection, List<SchemaMigration> migrations, Clock clock)
            throws SQLException {
        List<SchemaMigration> ordered = requireContiguous(migrations);
        int highestKnown = ordered.isEmpty() ? 0 : ordered.getLast().version();

        int installed = prepareAndRead(connection, highestKnown);
        for (SchemaMigration migration : ordered) {
            if (migration.version() <= installed) {
                continue;
            }
            installed = applyOne(connection, migration, clock.instant(), installed);
        }
        return installed;
    }

    /**
     * Creates the version and history tables and reads the installed version, all under the lock.
     *
     * <p>Table creation is inside the locked transaction rather than before it because two processes
     * meeting an empty database would otherwise race on the {@code CREATE TABLE} itself.</p>
     */
    private static int prepareAndRead(Connection connection, int highestKnown) throws SQLException {
        boolean restoreAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            lock(connection);
            createVersionTables(connection);
            int installed = versionOf(connection);
            if (installed > highestKnown) {
                connection.rollback();
                throw new IllegalStateException("database schema version " + installed
                        + " is newer than this build understands (" + highestKnown + "); refusing to open, "
                        + "because an older binary writing rows a newer schema expects would corrupt them "
                        + "silently");
            }
            connection.commit();
            return installed;
        } catch (SQLException | RuntimeException failed) {
            safeRollback(connection);
            throw failed;
        } finally {
            connection.setAutoCommit(restoreAutoCommit);
        }
    }

    /**
     * Applies one migration, re-reading the version under the lock so that a process which lost an
     * earlier race does not re-run a step the winner already applied.
     *
     * @return the version installed when this returns, which is the migration's own version whether
     *         this process applied it or observed another process having applied it
     */
    private static int applyOne(Connection connection, SchemaMigration migration, Instant appliedAt,
                                int expectedFrom) throws SQLException {
        boolean restoreAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            lock(connection);
            int installed = versionOf(connection);
            if (installed >= migration.version()) {
                // Another process applied this step while this one waited for the lock. Committing
                // rather than rolling back keeps the lock's release on the ordinary path.
                connection.commit();
                return installed;
            }
            if (installed != expectedFrom) {
                connection.rollback();
                throw new IllegalStateException("schema version moved from " + expectedFrom + " to "
                        + installed + " while migration " + migration.version()
                        + " was waiting; refusing to apply out of order");
            }
            try (Statement statement = connection.createStatement()) {
                for (String ddl : migration.statements()) {
                    statement.execute(ddl);
                }
            }
            recordVersion(connection, migration, appliedAt);
            connection.commit();
            return migration.version();
        } catch (SQLException | RuntimeException failed) {
            safeRollback(connection);
            throw failed;
        } finally {
            connection.setAutoCommit(restoreAutoCommit);
        }
    }

    private static void lock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, MIGRATION_LOCK_KEY);
            statement.execute();
        }
    }

    private static void createVersionTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS store_schema_version (
                        one_row BOOLEAN NOT NULL PRIMARY KEY DEFAULT TRUE CHECK (one_row),
                        version INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS store_schema_history (
                        version                 INTEGER NOT NULL PRIMARY KEY,
                        description             TEXT    NOT NULL,
                        applied_at_epoch_second BIGINT  NOT NULL,
                        applied_at_nano         INTEGER NOT NULL
                    )
                    """);
        }
    }

    private static void recordVersion(Connection connection, SchemaMigration migration, Instant appliedAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO store_schema_version (one_row, version) VALUES (TRUE, ?) "
                        + "ON CONFLICT (one_row) DO UPDATE SET version = EXCLUDED.version")) {
            statement.setInt(1, migration.version());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO store_schema_history "
                        + "(version, description, applied_at_epoch_second, applied_at_nano) "
                        + "VALUES (?, ?, ?, ?)")) {
            statement.setInt(1, migration.version());
            statement.setString(2, migration.description());
            StoredInstant.bindValue(statement, 3, appliedAt);
            statement.executeUpdate();
        }
    }

    /** {@code 0} for a database no migration has reached yet, including one with no version table. */
    static int versionOf(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT version FROM store_schema_version WHERE one_row")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static List<SchemaMigration> requireContiguous(List<SchemaMigration> migrations) {
        var ordered = new ArrayList<>(migrations);
        ordered.sort((left, right) -> Integer.compare(left.version(), right.version()));
        for (int index = 0; index < ordered.size(); index++) {
            if (ordered.get(index).version() != index + 1) {
                throw new IllegalStateException("schema migrations must be numbered 1..n with no gaps "
                        + "or duplicates; found " + ordered.get(index).version() + " at position "
                        + (index + 1));
            }
        }
        return ordered;
    }

    private static void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The caller is already propagating the original failure, and a rollback that itself
            // fails means the connection is gone - which is the same diagnosis, reported worse if it
            // replaces the cause.
        }
    }
}
