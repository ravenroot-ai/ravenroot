package ai.ravenroot.api.correlation;

/** Fixed public refusal categories; errors never include selected values, payloads or foreign IDs. */
public final class BusinessHistoryException extends IllegalStateException {
    /** Observable admission/closure reason independent from transport custody decisions. */
    public enum Reason {
        /** The policy requires an existing history and none exists in this scope. */
        UNKNOWN_HISTORY,
        /** The selected history or join generation is already closed. */
        CLOSED_HISTORY,
        /** The selected generation or retained data expired. */
        EXPIRED_HISTORY,
        /** A supposedly identical admission conflicts with established immutable context or data. */
        CONTEXT_CONFLICT,
        /** An operator-owned admission or retention bound was reached. */
        RESOURCE_LIMIT,
        /** A competing writer changed the expected compare-and-set revision. */
        REVISION_CONFLICT,
        /** The request violates the explicit history or join contract. */
        INVALID_REQUEST
    }
    /** Closed failure classification.
     * @serial Closed failure category without business data.
     */
    private final Reason reason;
    /** Creates a payload-free refusal.
 * @param reason closed payload-safe failure classification
 */
    public BusinessHistoryException(Reason reason) {
        super("business history " + java.util.Objects.requireNonNull(reason).name().toLowerCase(java.util.Locale.ROOT));
        this.reason = reason;
    }
    /** Fixed reason suitable for APIs and sanitized ingress outcomes.
 * @return closed failure category suitable for a payload-safe response
 */
    public Reason reason() { return reason; }
}
