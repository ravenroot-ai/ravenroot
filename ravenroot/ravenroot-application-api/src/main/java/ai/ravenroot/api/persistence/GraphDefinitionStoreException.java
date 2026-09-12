package ai.ravenroot.api.persistence;

import java.util.Objects;

/**
 * The single exception every {@link GraphDefinitionStore} adapter throws, carrying a sealed
 * {@link GraphDefinitionStoreFailure}.
 *
 * <p>One exception type carrying a sealed classification works identically in a failed
 * {@link java.util.concurrent.CompletionStage} and in direct inspection, which a hierarchy of
 * distinct exception classes does not. {@link ExecutionStoreException} divides the same
 * responsibility the same way, and the two are deliberately unrelated types: a caller catching one
 * must never silently absorb the other, because they classify different stores.</p>
 */
public final class GraphDefinitionStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient GraphDefinitionStoreFailure failure;

    /**
     * Creates a classified definition-store failure without a lower-level cause.
     *
     * @param failure classified definition-store failure.
     */
    public GraphDefinitionStoreException(GraphDefinitionStoreFailure failure) {
        this(failure, null);
    }

    /**
     * Creates a classified definition-store failure retaining its adapter cause.
     *
     * @param failure classified definition-store failure.
     * @param cause adapter-level reason that explains the failure.
     */
    public GraphDefinitionStoreException(GraphDefinitionStoreFailure failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").describe(), cause);
        this.failure = failure;
    }

    /**
     * Returns the sealed failure classification rather than requiring message parsing.
     *
     * @return classified definition-store failure.
     */
    public GraphDefinitionStoreFailure failure() {
        return failure;
    }

    /**
     * Returns retry guidance associated with the failure classification.
     *
     * @return retryability category for callers and adapters.
     */
    public Retryability retryability() {
        return failure.retryability();
    }

    /**
     * Unwraps the completion wrapper that {@code join} or {@code get} places around a store failure,
     * so callers that block on a stage still observe the classification rather than a wrapper.
     *
     * @param thrown exception observed by a caller.
     * @return nested definition-store exception, or {@code null} when the cause chain contains none.
     */
    public static GraphDefinitionStoreException unwrap(Throwable thrown) {
        Throwable current = thrown;
        while (current != null) {
            if (current instanceof GraphDefinitionStoreException failure) {
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
