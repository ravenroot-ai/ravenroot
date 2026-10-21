package ai.ravenroot.api.correlation;

/** Fixed public refusal categories; errors never include selected values, payloads or foreign IDs. */
public final class BusinessHistoryException extends IllegalStateException {
    /** Observable admission/closure reason independent from transport custody decisions. */
    public enum Reason { UNKNOWN_HISTORY, CLOSED_HISTORY, EXPIRED_HISTORY, CONTEXT_CONFLICT, RESOURCE_LIMIT, REVISION_CONFLICT, INVALID_REQUEST }
    private final Reason reason;
    /** Creates a payload-free refusal. */
    public BusinessHistoryException(Reason reason) {
        super("business history " + java.util.Objects.requireNonNull(reason).name().toLowerCase(java.util.Locale.ROOT));
        this.reason = reason;
    }
    /** Fixed reason suitable for APIs and sanitized ingress outcomes. */
    public Reason reason() { return reason; }
}
