package ai.ravenroot.server.audit;

import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.server.ratelimit.RateLimitAuditEvent;
import ai.ravenroot.server.ratelimit.RateLimitAuditSink;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Bridges rate-limit decisions into the SEC-13 durable trail as {@link AuditCategory#ACCESS},
 * replacing {@link StructuredRateLimitLogger}'s stdout line for production use.
 *
 * <p>{@link RateLimitAuditEvent#tenantId()} and {@link RateLimitAuditEvent#subject()} are
 * {@code "-"} when the limit fired before authentication resolved an identity — the same honest
 * placeholder the source event already documents. Every such record lands in a single pseudo-tenant
 * chain named {@code "-"}, which is the correct behaviour: pre-authentication rejections have no real
 * tenant to scope them to, and inventing one would misattribute them.</p>
 */
public final class AuditTrailRateLimitSink implements RateLimitAuditSink {
    private final AuditTrail auditTrail;

    public AuditTrailRateLimitSink(AuditTrail auditTrail) {
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
    }

    @Override
    public void record(RateLimitAuditEvent event) {
        Objects.requireNonNull(event, "event");
        boolean refused = event.status() >= 400;
        String detail = "code=" + event.code() + ";scope=" + event.scope() + ";clientAddress="
                + event.clientAddress() + ";forwarded=" + event.forwarded() + ";status=" + event.status()
                + ";retryAfterSeconds=" + event.retryAfterSeconds();
        auditTrail.append(AuditEnvelope.of(event.tenantId(), event.subject(), AuditCategory.ACCESS,
                refused ? "ratelimit.reject" : "ratelimit.reclaim", "http", event.method() + " " + event.path(),
                refused ? AuditOutcome.DENIED : AuditOutcome.ALLOWED, event.code(), event.requestId(),
                event.occurredAt(), OpaquePayload.of(detail.getBytes(StandardCharsets.UTF_8), "text/plain")));
    }
}
