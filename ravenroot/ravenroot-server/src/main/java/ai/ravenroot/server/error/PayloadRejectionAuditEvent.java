package ai.ravenroot.server.error;

import ai.ravenroot.api.payload.PayloadException;

import java.time.Instant;
import java.util.Objects;

/**
 * One rejected structured payload, described in fields a server-side sink can act on (API-01).
 *
 * <p>{@code tenantId} and {@code subject} are {@link #UNKNOWN} when the rejection happens before
 * authentication resolved an identity — the same honest placeholder
 * {@link ai.ravenroot.server.ratelimit.RateLimitAuditEvent} already establishes for exactly this case.
 */
public record PayloadRejectionAuditEvent(Instant occurredAt, String requestId, String tenantId, String subject,
                                         PayloadException rejection) {
    /** Placeholder for an identity that is genuinely unknown at the point the rejection happened. */
    public static final String UNKNOWN = "-";

    public PayloadRejectionAuditEvent {
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        requestId = Objects.requireNonNull(requestId, "requestId");
        tenantId = Objects.requireNonNullElse(tenantId, UNKNOWN);
        subject = Objects.requireNonNullElse(subject, UNKNOWN);
        Objects.requireNonNull(rejection, "rejection");
    }
}
