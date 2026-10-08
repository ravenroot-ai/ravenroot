package ai.ravenroot.api.authoring;

/** Classified source-authoring failure with no provider payload or secret-bearing message. */
public final class GraphAuthoringException extends RuntimeException {
    public enum Failure {
        NOT_FOUND, CONFLICT, UNAUTHORIZED, UNAVAILABLE, INVALID_DOCUMENT, LIMIT_EXCEEDED,
        UNSUPPORTED_PROVIDER, PUBLICATION_REQUIRED
    }

    private final Failure failure;

    public GraphAuthoringException(Failure failure) {
        super(failure.name());
        this.failure = java.util.Objects.requireNonNull(failure, "failure");
    }

    public GraphAuthoringException(Failure failure, Throwable cause) {
        super(failure.name(), cause);
        this.failure = java.util.Objects.requireNonNull(failure, "failure");
    }

    public Failure failure() { return failure; }
}
