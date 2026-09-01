package ai.ravenroot.server.audit;

import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.server.embed.EmbedSecurityAuditSink;

import java.util.Objects;

/** Durable redacted embed lifecycle audit adapter. Propagates append failure to preserve fail-closed semantics. */
public final class AuditTrailEmbedSecuritySink implements EmbedSecurityAuditSink {
    private final AuditTrail trail;
    public AuditTrailEmbedSecuritySink(AuditTrail trail) { this.trail = Objects.requireNonNull(trail, "trail"); }
    @Override public void record(Event event) {
        trail.append(AuditEnvelope.of(event.tenantId(), event.principal(), AuditCategory.ACCESS,
                "embed:" + event.phase().name().toLowerCase(java.util.Locale.ROOT),
                "embed-session", "", switch (event.outcome()) {
                    case ALLOWED -> AuditOutcome.ALLOWED;
                    case DENIED -> AuditOutcome.DENIED;
                    case FAILED -> AuditOutcome.FAILED;
                }, event.outcome().name(), event.requestId(), event.occurredAt(),
                OpaquePayload.empty("text/plain")));
    }
}
