package ai.ravenroot.api.persistence;

import java.util.Objects;

/**
 * The only exception type a {@link JoinStore} adapter may surface, following the
 * {@link ExecutionStoreException} precedent: adapter-specific exception types must not leak, or the
 * core would have to know each adapter to classify a failure.
 */
public final class JoinStoreException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Why the operation failed, and therefore what the caller may do about it. */
    public enum Reason {
        /**
         * The presented revision was not the current one. The caller must re-read and re-decide;
         * retrying the same write would overwrite whatever the winner recorded.
         */
        CONCURRENCY_CONFLICT(Retryability.RETRY_AFTER_REREAD),

        /** The request was malformed. Retrying it unchanged will fail identically. */
        INVALID_REQUEST(Retryability.DETERMINISTIC_REJECT),

        /** The store could not answer. Whether the write landed is unknown. */
        UNAVAILABLE(Retryability.INDETERMINATE);

        private final Retryability retryability;

        Reason(Retryability retryability) {
            this.retryability = retryability;
        }

/**
 * Returns retry guidance for the classified join-store failure.
 * @return retryability category attached to the reason.
 */
        public Retryability retryability() {
            return retryability;
        }
    }

    /** Machine-readable classification for this adapter-neutral failure. */
    private final Reason reason;
    private final transient JoinKey key;

/**
 * Creates a join-store failure without retaining a lower-level cause.
 * @param reason machine-readable reason for the store failure.
 * @param key the stable key used to identify the requested resource.
 * @param message diagnostic message for the failure.
 * <p>Creates an adapter-neutral join-store failure without an underlying cause.</p>
 */
    public JoinStoreException(Reason reason, JoinKey key, String message) {
        this(reason, key, message, null);
    }

/**
 * Creates a join-store failure retaining the lower-level adapter cause.
 * @param reason machine-readable reason for the store failure.
 * @param key the stable key used to identify the requested resource.
 * @param message diagnostic message for the failure.
 * @param cause sanitized reason that explains the change.
 */
    public JoinStoreException(Reason reason, JoinKey key, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.key = key;
    }

/**
 * Returns the machine-readable reason for the join-store failure.
 * @return sealed failure reason.
 */
    public Reason reason() {
        return reason;
    }

/**
 * The join the failure is about, or {@code null} for a tenant-scoped operation.
 * @return affected join key, or {@code null} when the operation is tenant-scoped.
 */
    public JoinKey key() {
        return key;
    }
}
