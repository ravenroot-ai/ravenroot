package ai.ravenroot.persistence.postgresql;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Two-column encoding of an {@link Instant}, plus the SQL comparison fragments that go with it.
 *
 * <h2>Why not {@code timestamptz}</h2>
 * <p>PostgreSQL's native timestamp is microsecond-resolution, so it truncates the nanosecond
 * component of every instant the store round-trips. That is not a rounding nicety here: the
 * conformance suite sets its clock to <em>exactly</em> an expiry the store reported and requires the
 * next claim to succeed at that instant, so a caller that waits for the reported instant must find
 * the lease gone. An expiry stored as a truncated value and compared against an untruncated clock
 * answers that question wrongly for up to a microsecond. {@link Instant#MIN} — the value
 * {@link ai.ravenroot.api.persistence.ExecutionStore#forgottenBefore(String)} returns until something
 * is purged — is also outside {@code timestamptz}'s range.</p>
 *
 * <p>Storing {@code (epochSecond, nano)} is exact and order-preserving under lexicographic
 * comparison: {@link Instant#getNano()} is always in {@code [0, 999999999]} even for instants before
 * the epoch, where {@link Instant#getEpochSecond()} is negative. So {@code (s1, n1) < (s2, n2)}
 * lexicographically exactly when {@code i1.isBefore(i2)}, which is what lets every temporal predicate
 * be evaluated in SQL rather than by pulling rows into Java and filtering there.</p>
 *
 * <h2>Row-value comparison rather than an unrolled disjunction</h2>
 * <p>PostgreSQL compares row constructors lexicographically, so {@code (s, n) <= (?, ?)} says
 * directly what the SQLite adapter has to spell out as {@code s < ? OR (s = ? AND n <= ?)}. Besides
 * being the same predicate written once, the row form is the one the planner can satisfy from a
 * composite index on {@code (s, n)}; the unrolled disjunction usually cannot be. The bind count
 * differs from the SQLite adapter's for this reason — two, not three — which is stated here because
 * the two adapters' binding code otherwise looks close enough to copy.</p>
 *
 * <h2>Why the comparison operand is always a bind parameter</h2>
 * <p>No fragment here contains {@code now()}, {@code CURRENT_TIMESTAMP} or any other database-side
 * clock. The store is its own clock authority through its injected {@link java.time.Clock}: the
 * conformance suite drives lease expiry and timer due-ness by moving that clock rather than by
 * sleeping, so a predicate consulting the server's clock would ignore the injected one entirely and
 * every temporal assertion would either hang or pass for the wrong reason. With several hosts there
 * is a second reason: the database's clock and the deployment's clocks are different clocks, and
 * only one of them is the one the caller reasons about.</p>
 */
final class StoredInstant {

    private StoredInstant() {
    }

    /** Column definitions for an instant-valued field. */
    static String columns(String name, String constraint) {
        return name + "_epoch_second BIGINT " + constraint + ",\n    "
                + name + "_nano INTEGER " + constraint;
    }

    /** {@code column <= bound}. Two binds: second, nano. */
    static String atOrBefore(String column) {
        return "(" + column + "_epoch_second, " + column + "_nano) <= (?, ?)";
    }

    /** {@code column < bound}, strictly. Two binds: second, nano. */
    static String strictlyBefore(String column) {
        return "(" + column + "_epoch_second, " + column + "_nano) < (?, ?)";
    }

    /** {@code column > bound}, strictly. Two binds: second, nano. */
    static String strictlyAfter(String column) {
        return "(" + column + "_epoch_second, " + column + "_nano) > (?, ?)";
    }

    /** {@code column >= bound}. Two binds: second, nano. */
    static String atOrAfter(String column) {
        return "(" + column + "_epoch_second, " + column + "_nano) >= (?, ?)";
    }

    /** Binds the right-hand operand of one comparison fragment. Returns the next free index. */
    static int bindComparison(PreparedStatement statement, int index, Instant bound) throws SQLException {
        statement.setLong(index, bound.getEpochSecond());
        statement.setInt(index + 1, bound.getNano());
        return index + 2;
    }

    /** Binds a stored instant as two consecutive columns. Returns the next free index. */
    static int bindValue(PreparedStatement statement, int index, Instant value) throws SQLException {
        statement.setLong(index, value.getEpochSecond());
        statement.setInt(index + 1, value.getNano());
        return index + 2;
    }

    static Instant read(ResultSet rows, String column) throws SQLException {
        return Instant.ofEpochSecond(rows.getLong(column + "_epoch_second"), rows.getInt(column + "_nano"));
    }
}
