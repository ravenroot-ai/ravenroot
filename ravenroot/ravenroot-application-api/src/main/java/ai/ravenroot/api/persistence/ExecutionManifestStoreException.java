package ai.ravenroot.api.persistence;

import java.util.Objects;

/**
 * The single exception every {@link ExecutionManifestStore} adapter throws, carrying a sealed
 * {@link ExecutionManifestStoreFailure}.
 *
 * <p>One exception type carrying a sealed classification works identically in a failed
 * {@link java.util.concurrent.CompletionStage} and in direct inspection, which a hierarchy of
 * distinct exception classes does not. {@link ExecutionStoreException} and
 * {@link GraphDefinitionStoreException} divide the same responsibility the same way, and all three
 * are deliberately unrelated types: a caller catching one must never silently absorb another,
 * because they classify different stores.</p>
 */
public final class ExecutionManifestStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ExecutionManifestStoreFailure failure;

    /**
     * Creates a classified manifest-store failure without a lower-level cause.
     *
     * @param failure classified manifest-store failure.
     */
    public ExecutionManifestStoreException(ExecutionManifestStoreFailure failure) {
        this(failure, null);
    }

    /**
     * Creates a classified manifest-store failure retaining its adapter cause.
     *
     * @param failure classified manifest-store failure.
     * @param cause adapter-level reason that explains the failure.
     */
    public ExecutionManifestStoreException(ExecutionManifestStoreFailure failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").describe(), cause);
        this.failure = failure;
    }

    /**
     * Returns the sealed failure classification rather than requiring message parsing.
     *
     * @return classified manifest-store failure.
     */
    public ExecutionManifestStoreFailure failure() {
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
     * @return nested manifest-store exception, or {@code null} when the cause chain contains none.
     */
    public static ExecutionManifestStoreException unwrap(Throwable thrown) {
        Throwable current = thrown;
        while (current != null) {
            if (current instanceof ExecutionManifestStoreException failure) {
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
