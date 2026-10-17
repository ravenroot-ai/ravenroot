package ai.ravenroot.api.authoring;

/** Classified source-authoring failure with no provider payload or secret-bearing message. */
public final class GraphAuthoringException extends RuntimeException {
    /** Stable failure classes suitable for transport without provider response details. */
    public enum Failure {
        /** The tenant-owned document or immutable revision does not exist. */
        NOT_FOUND,
        /** Draft, release, publication, idempotency, or immutable identity state is stale. */
        CONFLICT,
        /** Provider credentials or the authenticated actor lack the required authority. */
        UNAUTHORIZED,
        /** The configured provider or artifact origin could not complete the operation. */
        UNAVAILABLE,
        /** Supplied GraphML, identifier, cursor, or mutation state is invalid. */
        INVALID_DOCUMENT,
        /** A request or provider response exceeds its configured byte or page ceiling. */
        LIMIT_EXCEEDED,
        /** The selected source-archive provider has no installed adapter. */
        UNSUPPORTED_PROVIDER,
        /** The requested deployment identity lacks verified immutable publication evidence. */
        PUBLICATION_REQUIRED
    }

    /** Sanitized failure class returned by {@link #failure()}. */
    private final Failure failure;

    /**
     * Creates a sanitized authoring failure without a provider cause.
     *
     * @param failure stable failure class exposed to an adapter
     */
    public GraphAuthoringException(Failure failure) {
        super(failure.name());
        this.failure = java.util.Objects.requireNonNull(failure, "failure");
    }

    /**
     * Creates a sanitized authoring failure retaining an internal diagnostic cause.
     *
     * @param failure stable failure class exposed to an adapter
     * @param cause internal provider, parsing, or transport failure
     */
    public GraphAuthoringException(Failure failure, Throwable cause) {
        super(failure.name(), cause);
        this.failure = java.util.Objects.requireNonNull(failure, "failure");
    }

    /**
     * Returns the stable classification without exposing provider response content.
     *
     * @return transport-safe authoring failure class
     */
    public Failure failure() { return failure; }
}
