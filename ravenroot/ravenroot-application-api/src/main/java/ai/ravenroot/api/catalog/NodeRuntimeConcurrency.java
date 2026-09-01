package ai.ravenroot.api.catalog;

/**
 * Trusted catalog constraint for per-node, per-traversal runtime admission.
 *
 * <p>This is deliberately independent of {@link NodeRuntimeNature}. A worker and a traversal-scoped
 * runtime can have the same admission bound, while choosing a lifecycle never grants capacity. A
 * behavior's own transport/profile gate remains an additional constraint, so the effective limit is
 * always the stricter of this platform gate and the behavior's gate.</p>
 *
 * @param defaultValue value used when graph content omits {@code runtime.maxConcurrency}
 * @param ceiling largest value graph content may select
 */
public record NodeRuntimeConcurrency(int defaultValue, int ceiling) {
    /** Conservative platform contract inherited by legacy descriptors. */
    public static final NodeRuntimeConcurrency DEFAULT = new NodeRuntimeConcurrency(64, 256);

    /** Validates that the default and ceiling form a positive range. */
public NodeRuntimeConcurrency {
        if (defaultValue < 1) {
            throw new IllegalArgumentException("Runtime max-concurrency default must be positive");
        }
        if (ceiling < 1) {
            throw new IllegalArgumentException("Runtime max-concurrency ceiling must be positive");
        }
        if (defaultValue > ceiling) {
            throw new IllegalArgumentException("Runtime max-concurrency default cannot exceed its ceiling");
        }
    }
}
