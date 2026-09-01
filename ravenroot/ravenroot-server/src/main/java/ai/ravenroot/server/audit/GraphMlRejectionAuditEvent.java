package ai.ravenroot.server.audit;

import ai.ravenroot.core.graph.GraphMlRejectionDetail;

import java.time.Instant;
import java.util.Objects;

/**
 * One rejected GraphML submission, described in fields a server-side sink can act on (FIX-03).
 *
 * <p>{@code tenantId} and {@code subject} are {@link #UNKNOWN} when the rejection happens before
 * authentication resolved an identity — the same honest placeholder
 * {@link ai.ravenroot.server.ratelimit.RateLimitAuditEvent} already establishes for exactly this case.
 * Pre-authentication the server genuinely does not know who is calling, and writing anything else would
 * invent attribution.</p>
 */
public record GraphMlRejectionAuditEvent(Instant occurredAt, String requestId, String tenantId, String subject,
                                         GraphMlRejectionDetail rejection) {
    /** Placeholder for an identity that is genuinely unknown at the point the rejection happened. */
    public static final String UNKNOWN = "-";

    public GraphMlRejectionAuditEvent {
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        requestId = Objects.requireNonNull(requestId, "requestId");
        tenantId = Objects.requireNonNullElse(tenantId, UNKNOWN);
        subject = Objects.requireNonNullElse(subject, UNKNOWN);
        Objects.requireNonNull(rejection, "rejection");
    }
}
