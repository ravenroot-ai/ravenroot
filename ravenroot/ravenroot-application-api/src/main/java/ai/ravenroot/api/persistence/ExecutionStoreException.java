package ai.ravenroot.api.persistence;

import java.util.Objects;

/**
 * The single exception every adapter throws, carrying a sealed {@link ExecutionStoreFailure}.
 *
 * <p>One exception type carrying a sealed classification works identically in a failed
 * {@link java.util.concurrent.CompletionStage} and in direct inspection, which a hierarchy of
 * distinct exception classes does not: a caller joining a stage would otherwise have to unwrap and
 * then {@code instanceof}-test a dozen types.</p>
 */
public final class ExecutionStoreException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient ExecutionStoreFailure failure;

/**
 * Creates a classified store failure without preserving a lower-level cause.
 * @param failure classified store failure.
 */
    public ExecutionStoreException(ExecutionStoreFailure failure) {
        this(failure, null);
    }

/**
 * Creates a classified store failure retaining its adapter cause.
 * @param failure classified store failure.
 * @param cause sanitized reason that explains the change.
 */
    public ExecutionStoreException(ExecutionStoreFailure failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").describe(), cause);
        this.failure = failure;
    }

/**
 * Returns the sealed failure classification rather than requiring message parsing.
 * @return classified execution-store failure.
 */
    public ExecutionStoreFailure failure() {
        return failure;
    }

/**
 * Returns retry guidance associated with the failure classification.
 * @return retryability category for callers and adapters.
 */
    public Retryability retryability() {
        return failure.retryability();
    }

    /**
     * Unwraps the {@link java.util.concurrent.CompletionException} or {@link java.util.concurrent.ExecutionException}
     * that {@code join}/{@code get} wraps around a store failure, so callers that block on a stage
     * still observe the classification rather than a wrapper.
 * @param thrown adapter exception being translated.
 * @return nested store exception, or {@code null} when the cause chain contains none.
     */
    public static ExecutionStoreException unwrap(Throwable thrown) {
        Throwable current = thrown;
        while (current != null) {
            if (current instanceof ExecutionStoreException failure) {
                return failure;
            }
            if (current == current.getCause()) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }
}
