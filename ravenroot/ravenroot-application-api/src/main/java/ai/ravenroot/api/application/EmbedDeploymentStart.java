package ai.ravenroot.api.application;

/**
 * Sanitized result of an idempotent embed deployment traversal request.
 * @param outcome authoritative admission or reconciliation outcome
 * @param requestId caller-supplied bounded idempotency identity
 */
public record EmbedDeploymentStart(Outcome outcome, String requestId) {
    /** Public outcomes that do not disclose deployment internals. */
    public enum Outcome {
        /** The traversal was durably admitted. */
        ACCEPTED,
        /** The same request was already durably admitted. */
        DUPLICATE,
        /** The request was not admitted. */
        REFUSED,
        /** Admission is uncertain and authoritative reconciliation is required. */
        RECONCILE
    }

    /** Validates the bounded public result. */
    public EmbedDeploymentStart {
        if (outcome == null || requestId == null || requestId.isBlank() || requestId.length() > 256) {
            throw new IllegalArgumentException("invalid embed deployment start result");
        }
    }
}
