package ai.ravenroot.server.audit;

import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.server.error.PayloadRejectionAuditEvent;
import ai.ravenroot.server.error.PayloadRejectionAuditSink;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Bridges payload rejections into the SEC-13 durable trail as {@link AuditCategory#ACCESS}, replacing
 * {@code StructuredPayloadRejectionLogger}'s stdout line for production use (API-01).
 *
 * <p>Mirrors {@link AuditTrailGraphMlRejectionSink} exactly, including why routing here satisfies
 * rather than reopens the source type's "server-side sink" constraint — see that class's Javadoc.
 * {@link PayloadException#diagnosticDetail()} is the milder of the two sources handled by these sinks
 * (an offset, an observed size, a token class, never document content), but the sink and the constraint
 * it satisfies are the same shape.</p>
 */
public final class AuditTrailPayloadRejectionSink implements PayloadRejectionAuditSink {
    private final AuditTrail auditTrail;

    public AuditTrailPayloadRejectionSink(AuditTrail auditTrail) {
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
    }

    @Override
    public void record(PayloadRejectionAuditEvent event) {
        Objects.requireNonNull(event, "event");
        PayloadException rejection = event.rejection();
        String detail = detail(rejection.getMessage(), rejection.diagnosticDetail());
        auditTrail.append(AuditEnvelope.of(event.tenantId(), event.subject(), AuditCategory.ACCESS,
                "payload.reject", "payload", rejection.incidentId(), AuditOutcome.DENIED, rejection.code(),
                event.requestId(), event.occurredAt(),
                OpaquePayload.of(detail.getBytes(StandardCharsets.UTF_8), "application/json")));
    }

    private static String detail(String message, Map<String, String> diagnosticDetail) {
        var line = new StringBuilder("{\"message\":\"").append(escape(message)).append("\",\"detail\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : diagnosticDetail.entrySet()) {
            if (!first) {
                line.append(',');
            }
            first = false;
            line.append('"').append(escape(entry.getKey())).append("\":\"")
                    .append(escape(entry.getValue())).append('"');
        }
        return line.append("}}").toString();
    }

    /**
     * The previous escaping here handled only backslash, quote, newline and carriage
     * return. {@code detail(...)} above builds a JSON blob for {@code AuditEnvelope.detail} -- Base64
     * protects the byte sequence {@code FileAuditTrail} persists it as, not the JSON syntax inside it,
     * so this blob still needs full escaping on its own terms. {@link JsonStrings} is the one
     * implementation every JSON-emitting class in this module now uses.
     */
    private static String escape(String value) {
        return JsonStrings.escape(value);
    }
}
