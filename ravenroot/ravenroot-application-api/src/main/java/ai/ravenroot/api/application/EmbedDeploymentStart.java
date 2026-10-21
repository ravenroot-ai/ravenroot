package ai.ravenroot.api.application;

/**
 * Sanitized result of an idempotent embed deployment traversal request.
 * @param outcome authoritative admission or reconciliation outcome
 * @param requestId caller-supplied bounded request identity
 * @param processInstanceId original accepted process, absent on refused/legacy results
 * @param traversalId original accepted initial traversal, absent on refused/legacy results
 */
public record EmbedDeploymentStart(Outcome outcome, String requestId,
                                   java.util.UUID processInstanceId, java.util.UUID traversalId) {
    /** Compatibility result from a producer that does not expose admitted identities. */
    public EmbedDeploymentStart(Outcome outcome, String requestId) {
        this(outcome, requestId, null, null);
    }

    /** Public outcomes that do not disclose deployment internals. */
    public enum Outcome {
        /** The traversal was durably admitted. */
        ACCEPTED,
        /** The deployment already accepted its initial START; this reconciles that original admission. */
        DUPLICATE,
        /** The request was not admitted. */
        REFUSED,
        /** Admission is uncertain and authoritative reconciliation is required. */
        RECONCILE
    }

    /** Validates the bounded public result. */
    public EmbedDeploymentStart {
        if ((processInstanceId == null) != (traversalId == null)) {
            throw new IllegalArgumentException("initial START identities must be supplied together");
        }
        if (outcome == null || requestId == null || requestId.isBlank() || requestId.length() > 256) {
            throw new IllegalArgumentException("invalid embed deployment start result");
        }
    }
}
