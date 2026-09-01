package ai.ravenroot.persistence.sqlite;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Two-column encoding of an {@link Instant}, plus the SQL comparison fragments that go with it.
 *
 * <h2>Why two columns</h2>
 * <p>The obvious encoding — a single {@code INTEGER} of epoch nanoseconds — cannot represent the
 * range this port actually uses. {@link Instant#MIN} is the value
 * {@link ai.ravenroot.api.persistence.ExecutionStore#forgottenBefore(String)} is specified to return
 * until something is purged, and its epoch-nanosecond value overflows a signed 64-bit integer by
 * nine orders of magnitude. Epoch milliseconds would fit, but would silently truncate the nanosecond
 * component of every instant the store round-trips, so a lease claimed at {@code T} would be reported
 * as expiring at a slightly different instant than the caller can compute — and the conformance suite
 * sets its clock to <em>exactly</em> a reported expiry and requires the claim to succeed there.</p>
 *
 * <p>Storing {@code (epochSecond, nano)} is exact and, critically, <strong>order-preserving under
 * lexicographic comparison</strong>: {@link Instant#getNano()} is always in {@code [0, 999999999]}
 * even for instants before the epoch, where {@link Instant#getEpochSecond()} is negative. So
 * {@code (s1, n1) < (s2, n2)} lexicographically exactly when {@code i1.isBefore(i2)}, which is what
 * lets every time predicate be evaluated in SQL rather than by pulling rows into Java and filtering
 * them there.</p>
 *
 * <h2>Why the comparison operand is always a bind parameter</h2>
 * <p>None of these fragments contains {@code strftime('now')} or any other SQL-side clock. ADR 0010
 * sections 4 and 7 make the store its own clock authority through the injected {@link java.time.Clock}
 * — the suite drives lease expiry and timer due-ness by moving that clock rather than by sleeping, so
 * a predicate that consulted SQLite's own clock would ignore the injected one entirely and every
 * temporal assertion would either hang or pass for the wrong reason. The instant is read from the
 * clock once per operation, in Java, and bound as a parameter.</p>
 */
final class StoredInstant {

    private StoredInstant() {
    }

    /** Column definitions for an instant-valued field. */
    static String columns(String name, String constraint) {
        return name + "_epoch_second INTEGER " + constraint + ",\n    "
                + name + "_nano INTEGER " + constraint;
    }

    /** {@code column <= bound}. Three binds: second, second, nano. */
    static String atOrBefore(String column) {
        return "(" + column + "_epoch_second < ? OR (" + column + "_epoch_second = ? AND "
                + column + "_nano <= ?))";
    }

    /** {@code column < bound}, strictly. Three binds: second, second, nano. */
    static String strictlyBefore(String column) {
        return "(" + column + "_epoch_second < ? OR (" + column + "_epoch_second = ? AND "
                + column + "_nano < ?))";
    }

    /** {@code column > bound}, strictly. Three binds: second, second, nano. */
    static String strictlyAfter(String column) {
        return "(" + column + "_epoch_second > ? OR (" + column + "_epoch_second = ? AND "
                + column + "_nano > ?))";
    }

    /** Binds the right-hand operand of one comparison fragment. Returns the next free index. */
    static int bindComparison(PreparedStatement statement, int index, Instant bound) throws SQLException {
        statement.setLong(index, bound.getEpochSecond());
        statement.setLong(index + 1, bound.getEpochSecond());
        statement.setInt(index + 2, bound.getNano());
        return index + 3;
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
