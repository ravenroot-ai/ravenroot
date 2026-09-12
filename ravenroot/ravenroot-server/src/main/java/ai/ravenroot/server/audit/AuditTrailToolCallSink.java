package ai.ravenroot.server.audit;

import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.security.ToolCallAuditEvent;
import ai.ravenroot.api.security.ToolCallAuditSink;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Writes sanitized, correlated model-requested tool events to the durable tenant audit chain. */
public final class AuditTrailToolCallSink implements ToolCallAuditSink {
    private final AuditTrail auditTrail;

    public AuditTrailToolCallSink(AuditTrail auditTrail) {
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
    }

    @Override
    public void record(ToolCallAuditEvent event) {
        Objects.requireNonNull(event, "event");
        String detail = "process=" + event.processInstanceId()
                + ";traversal=" + event.traversalId()
                + ";invocation=" + event.invocationId()
                + ";attempt=" + event.attemptId()
                + ";tool=" + event.tool()
                + ";arguments=" + event.argumentsDigest();
        auditTrail.append(AuditEnvelope.of(event.tenantId(), event.principal(), AuditCategory.TOOL,
                "tool.invoke", "tool-call", event.callId().toString(), outcome(event.disposition()),
                event.reason(), event.requestId(), event.occurredAt(),
                OpaquePayload.of(detail.getBytes(StandardCharsets.UTF_8), "text/plain")));
    }

    private static AuditOutcome outcome(ToolCallAuditEvent.Disposition disposition) {
        return switch (disposition) {
            case ATTEMPT -> AuditOutcome.ATTEMPTED;
            case DENIED, APPROVAL_REQUIRED -> AuditOutcome.DENIED;
            case SUCCEEDED -> AuditOutcome.ALLOWED;
            case FAILED -> AuditOutcome.FAILED;
        };
    }
}
