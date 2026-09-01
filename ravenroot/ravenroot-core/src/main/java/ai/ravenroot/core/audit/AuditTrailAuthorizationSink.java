package ai.ravenroot.core.audit;

import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.AuthorizationAuditEvent;
import ai.ravenroot.api.security.AuthorizationAuditSink;

import java.util.Objects;

/**
 * Bridges SEC-03's {@link AuthorizationAuditEvent} into the SEC-13 durable audit trail, replacing
 * {@code StructuredAuthorizationLogger}'s stdout line for production use.
 *
 * <p>This sink lives here rather than in {@code ravenroot-server} because the operator CLI writes
 * embed registrations directly to their durable store and must audit the authorization decisions it makes while doing so,
 * and {@code ravenroot-cli} depends on {@code ravenroot-server} only in test scope. A second copy in
 * the CLI would be a second redaction and categorisation policy, and the one that eventually drifts is
 * always the one nobody is looking at.</p>
 *
 * <p>{@code AuthorizationAuditEvent} carries only a bare {@code subject}, not SEC-07's full
 * {@code SecurityContext.qualifiedIdentity()} (issuer and principal type). That is a pre-existing
 * limitation of the event this class receives, not something invented here; widening
 * {@code AuthorizationAuditEvent} to carry the qualified identity is SEC-07/SEC-03 follow-on work, out
 * of this component's scope.</p>
 *
 * <p>Propagates any {@link ai.ravenroot.api.audit.AuditTrailException} from the trail unchanged: this
 * class performs no fail-open handling of its own, so the fail-closed behaviour already established at
 * every call site of {@code AuthorizationAuditSink} ({@code DefaultAuthorizationService.decide}) governs
 * unchanged.</p>
 */
public final class AuditTrailAuthorizationSink implements AuthorizationAuditSink {
    private final AuditTrail auditTrail;

    public AuditTrailAuthorizationSink(AuditTrail auditTrail) {
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
    }

    @Override
    public void record(AuthorizationAuditEvent event) {
        Objects.requireNonNull(event, "event");
        auditTrail.append(AuditEnvelope.of(event.tenantId(), event.subject(), categoryFor(event.action()),
                "authorize:" + event.action(), event.resourceType(), event.resourceId(),
                event.allowed() ? AuditOutcome.ALLOWED : AuditOutcome.DENIED, event.reason(), event.requestId(),
                event.occurredAt(), OpaquePayload.empty("text/plain")));
    }

    private static AuditCategory categoryFor(AuthorizationAction action) {
        return switch (action) {
            case ARTIFACT_APPROVE, ARTIFACT_ACTIVATE, ARTIFACT_RETIRE -> AuditCategory.APPROVAL;
            case AUDIT_ADMIN -> AuditCategory.ADMINISTRATION;
            case AUDIT_READ, AUDIT_EXPORT -> AuditCategory.ACCESS;
            // The reference-monitor DECISION for a cancel/drain request is itself categorised as
            // CONTROL, not the generic DECISION every other action falls through to below -- the
            // decision to allow or deny an operator command that changes running work is part of the
            // control-plane story, not indistinguishable from e.g. an EXECUTION_START decision.
            case EXECUTION_CONTROL -> AuditCategory.CONTROL;
            default -> AuditCategory.DECISION;
        };
    }
}
