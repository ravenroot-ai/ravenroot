package ai.ravenroot.persistence.postgresql;

import java.sql.SQLException;

/**
 * Classification of PostgreSQL {@code SQLSTATE} codes into the few outcomes the ports distinguish.
 *
 * <h2>Why by class and not by driver exception type</h2>
 * <p>{@code SQLSTATE} is defined by the server and is stable across driver versions; the driver's own
 * exception hierarchy is not, and its message text is a diagnostic rather than a contract. Matching on
 * the five-character code — and, where the whole class means one thing, on its two-character class —
 * is the only classification that keeps meaning the same after a driver upgrade.</p>
 *
 * <h2>The codes that matter, and why each one is a different answer</h2>
 * <ul>
 *   <li><strong>{@code 40001} serialization failure and {@code 40P01} deadlock detected.</strong>
 *   Both mean the transaction did not happen and may succeed if run again. They are retried inside
 *   the adapter rather than reported, because the port has no vocabulary for "try again" and a caller
 *   given one would have to reimplement this loop without knowing the isolation level it is retrying
 *   under. Retrying is sound because every retried transaction re-reads the state it decides on.</li>
 *   <li><strong>{@code 23505} unique violation.</strong> A genuine identity collision, which the
 *   callers turn into {@code AlreadyExists} or a correlation-taken failure depending on which
 *   constraint was hit. It is not retryable: running it again collides again.</li>
 *   <li><strong>Class {@code 08} connection exception, {@code 57P01} admin shutdown, {@code 57P03}
 *   cannot connect now, {@code 53300} too many connections.</strong> The store is unreachable. These
 *   are {@code Unavailable} when they happen with the transaction demonstrably not committed, and are
 *   the reason {@code OutcomeUnknown} exists when they happen during {@code COMMIT} — see
 *   {@link Transactions}.</li>
 *   <li><strong>Class {@code 42} syntax or access rule violation, and {@code 28} invalid
 *   authorization.</strong> {@code 42501} insufficient privilege and the {@code 28} class are
 *   {@code NotAuthorized}: a credential that cannot do what the adapter needs is an operator
 *   condition, not a caller error. The rest of class {@code 42} is a programming fault in this
 *   adapter or a schema that does not match the binary, and must not be laundered into a store
 *   failure that a caller might retry.</li>
 *   <li><strong>Class {@code XX} internal error, including {@code XX001} data corrupted and
 *   {@code XX002} index corrupted.</strong> The database itself reports damage; this is the one class
 *   that maps to {@code Corrupted} without the adapter having to detect anything.</li>
 * </ul>
 *
 * <p>Deliberately absent: a default that turns an unrecognised code into {@code Unavailable}. An
 * unknown code is unknown, and reporting it as a transient condition invites a caller to retry
 * something that will never succeed. Unrecognised codes propagate as themselves.</p>
 */
final class SqlStates {

    static final String SERIALIZATION_FAILURE = "40001";
    static final String DEADLOCK_DETECTED = "40P01";
    static final String UNIQUE_VIOLATION = "23505";
    static final String FOREIGN_KEY_VIOLATION = "23503";
    static final String INSUFFICIENT_PRIVILEGE = "42501";
    static final String ADMIN_SHUTDOWN = "57P01";
    static final String CANNOT_CONNECT_NOW = "57P03";
    static final String TOO_MANY_CONNECTIONS = "53300";
    static final String LOCK_NOT_AVAILABLE = "55P03";
    static final String QUERY_CANCELED = "57014";

    private static final String CONNECTION_EXCEPTION_CLASS = "08";
    private static final String INVALID_AUTHORIZATION_CLASS = "28";
    private static final String INTERNAL_ERROR_CLASS = "XX";

    private SqlStates() {
    }

    /** The transaction did not happen and running it again may succeed. */
    static boolean isRetryable(SQLException failed) {
        return is(failed, SERIALIZATION_FAILURE) || is(failed, DEADLOCK_DETECTED);
    }

    /** An identity collision. Running it again collides again. */
    static boolean isUniqueViolation(SQLException failed) {
        return is(failed, UNIQUE_VIOLATION);
    }

    static boolean isForeignKeyViolation(SQLException failed) {
        return is(failed, FOREIGN_KEY_VIOLATION);
    }

    /** The store could not be reached, or gave up serving this caller. */
    static boolean isUnavailable(SQLException failed) {
        return inClass(failed, CONNECTION_EXCEPTION_CLASS)
                || is(failed, ADMIN_SHUTDOWN)
                || is(failed, CANNOT_CONNECT_NOW)
                || is(failed, TOO_MANY_CONNECTIONS);
    }

    /**
     * The statement waited out its {@code lock_timeout} or {@code statement_timeout}.
     *
     * <p>Separate from {@link #isUnavailable(SQLException)} because the operator action differs: an
     * unreachable database is an outage, whereas a lock wait that expired is contention against a
     * holder that is very much alive, and the fix is capacity or a longer bound rather than recovery.
     * Both are reported to the caller as unavailability, since the port has no third answer, but the
     * reason text keeps them apart.</p>
     */
    static boolean isTimedOut(SQLException failed) {
        return is(failed, LOCK_NOT_AVAILABLE) || is(failed, QUERY_CANCELED);
    }

    /** The credential cannot do what the adapter asked, which is an operator condition. */
    static boolean isNotAuthorized(SQLException failed) {
        return is(failed, INSUFFICIENT_PRIVILEGE) || inClass(failed, INVALID_AUTHORIZATION_CLASS);
    }

    /** The database reports damage to its own storage. */
    static boolean isCorrupted(SQLException failed) {
        return inClass(failed, INTERNAL_ERROR_CLASS);
    }

    /**
     * Whether any exception in the chain carries this state.
     *
     * <p>The chain is walked because the driver wraps a server error inside a batch or a nested call,
     * and the outermost exception then carries no state at all. A classification that looked only at
     * the top would silently answer "not retryable" for a serialization failure that arrived one link
     * down, which is the case this whole class exists to get right.</p>
     */
    private static boolean is(SQLException failed, String state) {
        for (SQLException current = failed; current != null; current = current.getNextException()) {
            if (state.equals(current.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static boolean inClass(SQLException failed, String stateClass) {
        for (SQLException current = failed; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.length() >= 2 && state.startsWith(stateClass)) {
                return true;
            }
        }
        return false;
    }
}
