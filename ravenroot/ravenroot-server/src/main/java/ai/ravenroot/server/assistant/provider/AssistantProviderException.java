package ai.ravenroot.server.assistant.provider;

import ai.ravenroot.server.assistant.AssistantOutcome;

/**
 * A provider fault, already classified into the panel's own failure vocabulary.
 *
 * <p>The classification happens here, at the boundary that knows what a 401 from this provider means,
 * rather than in {@code AssistantService} — a service switching on HTTP status codes would be a
 * service that knows one provider's conventions, which is what the port exists to prevent.</p>
 *
 * <p>The message is <b>authored</b>, never assembled from an upstream response body. That is not
 * stylistic: a provider error body is attacker-influenceable in the general case and internal in
 * every case, and this exception's message reaches the author's screen.</p>
 */
public final class AssistantProviderException extends Exception {
    private static final long serialVersionUID = 1L;

    private final AssistantOutcome.Reason reason;

    public AssistantProviderException(AssistantOutcome.Reason reason) {
        this(reason, reason.defaultMessage(), null);
    }

    public AssistantProviderException(AssistantOutcome.Reason reason, String message, Throwable cause) {
        // The cause is retained for a server-side log and is never rendered to the author: the
        // author-facing text is `reason.defaultMessage()`, which is product-authored.
        super(message, cause);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    public AssistantOutcome.Reason reason() {
        return reason;
    }

    /** The outcome this fault becomes. One place, so the mapping cannot drift per call site. */
    public AssistantOutcome.Failure asOutcome() {
        return new AssistantOutcome.Failure(reason, reason.defaultMessage());
    }
}
