package ai.ravenroot.persistence.postgresql;

import java.io.Serial;

/**
 * Raised when a transaction's {@code COMMIT} neither succeeded nor demonstrably failed.
 *
 * <p>This is package-private and never reaches a caller: each store catches it and reports its own
 * port's outcome-unknown failure, which carries the key the caller needs in order to reconcile.</p>
 *
 * <p>The single-host adapter records that this state is unreachable through its operations, because
 * its {@code COMMIT} is a local write and a failure there means the file is gone. Here it is an
 * ordinary event: the network can drop between the client sending {@code COMMIT} and the server's
 * acknowledgement arriving, and in that window the transaction may have been applied. Retrying is not
 * available — a retry of a committed transaction is a duplicate — so the honest answer is to say the
 * outcome is unknown and let the caller resolve it against the store, which is exactly what the port
 * defines the failure for.</p>
 */
final class OutcomeUnknownException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    OutcomeUnknownException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
