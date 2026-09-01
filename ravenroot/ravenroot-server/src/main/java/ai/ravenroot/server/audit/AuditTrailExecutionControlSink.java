package ai.ravenroot.server.audit;

import ai.ravenroot.api.application.ExecutionControlAuditEvent;
import ai.ravenroot.api.application.ExecutionControlAuditSink;
import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.api.persistence.OpaquePayload;

import java.util.Objects;

/**
 * Bridges the API-02 cancel-and-drain execution-control audit event into the SEC-13 durable
 * trail as {@link AuditCategory#CONTROL}. Mirrors {@link AuditTrailArtifactLifecycleSink} exactly, one
 * category over.
 */
public final class AuditTrailExecutionControlSink implements ExecutionControlAuditSink {
    private final AuditTrail auditTrail;

    public AuditTrailExecutionControlSink(AuditTrail auditTrail) {
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
    }

    @Override
    public void record(ExecutionControlAuditEvent event) {
        Objects.requireNonNull(event, "event");
        auditTrail.append(AuditEnvelope.of(event.tenantId(), event.subject(), AuditCategory.CONTROL,
                event.action(), event.resourceType(), event.resourceId(), outcomeFor(event.disposition()),
                event.disposition().name() + (event.detail().isBlank() ? "" : ":" + event.detail()),
                event.requestId(), event.occurredAt(), OpaquePayload.empty("text/plain")));
    }

    /** Same asymmetry as {@code AuditTrailArtifactLifecycleSink.outcomeFor}: an ATTEMPT is not a result. */
    private static AuditOutcome outcomeFor(ExecutionControlAuditEvent.Disposition disposition) {
        return switch (disposition) {
            case ATTEMPT -> AuditOutcome.ATTEMPTED;
            case DENIED -> AuditOutcome.DENIED;
            case FAILED -> AuditOutcome.FAILED;
            case SUCCEEDED -> AuditOutcome.ALLOWED;
        };
    }
}
