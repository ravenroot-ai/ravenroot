package ai.ravenroot.api.application;

import java.util.Map;
import java.util.Objects;

/** A fail-closed, redaction-safe refusal from the process-local source-session boundary. */
public final class SourceSessionException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /** Closed taxonomy; messages contain no submitted identifiers, values or adapter text. */
    public enum Reason {
        /** No effective inbound source remains after catalog validation. */
NO_EFFECTIVE_SOURCE("the graph has no effective inbound SOURCE node"),
        /** An effective source lacks the trusted inbound-source capability. */
SOURCE_CAPABILITY_MISMATCH("an effective SOURCE node has no trusted inbound-source capability"),
        /** The session identity is already bound to different graph content. */
GRAPH_CONFLICT("the session id is already bound to a different graph"),
        /** The supplied session identity violates the public format contract. */
SESSION_ID_INVALID("the source session id is invalid");

        private final String message;
        Reason(String message) { this.message = message; }
        String publicMessage() { return message; }
    }

    private final transient Reason reason;
    private final transient Map<String, Object> diagnosticDetail;

    /**
 * Creates a refusal without diagnostic details.
 * @param reason operator-safe reason recorded with the authorized operation
 */
public SourceSessionException(Reason reason) {
        this(reason, Map.of());
    }

    /**
 * Creates a refusal with server-only diagnostic details.
 * @param reason operator-safe reason recorded with the authorized operation
 * @param diagnosticDetail immutable server-only diagnostic fields
 */
public SourceSessionException(Reason reason, Map<String, Object> diagnosticDetail) {
        super(Objects.requireNonNull(reason, "reason").publicMessage());
        this.reason = reason;
        this.diagnosticDetail = Map.copyOf(Objects.requireNonNull(diagnosticDetail, "diagnosticDetail"));
    }

    /**
 * Returns the stable refusal reason.
 * @return stable refusal reason
 */
public Reason reason() { return reason; }

    /**
 * Submitted graph detail for a server-side diagnostic sink only; never serialize this map.
* @return immutable diagnostic details reserved for trusted server sinks
 */
    public Map<String, Object> diagnosticDetail() { return diagnosticDetail; }
}
