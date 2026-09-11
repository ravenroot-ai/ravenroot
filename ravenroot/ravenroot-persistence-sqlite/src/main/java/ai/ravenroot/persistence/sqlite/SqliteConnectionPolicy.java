package ai.ravenroot.persistence.sqlite;

import java.time.Duration;
import java.util.Objects;
import java.sql.Connection;
import java.sql.SQLException;

/** Shared, typed connection-contention policy for Ravenroot's SQLite stores. */
public record SqliteConnectionPolicy(Duration busyTimeout) {
    /** One authority for the five-second wait used by every SQLite connection. */
    public static final SqliteConnectionPolicy DEFAULTS = new SqliteConnectionPolicy(Duration.ofSeconds(5));

    public SqliteConnectionPolicy {
        Objects.requireNonNull(busyTimeout, "busyTimeout");
        if (busyTimeout.isNegative()) throw new IllegalArgumentException("busyTimeout cannot be negative");
        long milliseconds;
        try {
            milliseconds = busyTimeout.toMillis();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("busyTimeout is too large", overflow);
        }
        if (milliseconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("busyTimeout exceeds SQLite's millisecond range");
        }
    }

    void apply(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + busyTimeout.toMillis());
        }
    }
}
