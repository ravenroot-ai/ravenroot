package ai.ravenroot.server.audit;

import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.core.graph.GraphMlRejectionDetail;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Bridges GraphML rejections into the SEC-13 durable trail as {@link AuditCategory#ACCESS}, replacing
 * {@link StructuredGraphMlRejectionLogger}'s stdout line for production use (FIX-03).
 *
 * <h2>Why this satisfies FIX-03's constraint rather than reopening it</h2>
 * <p>{@link GraphMlRejectionDetail#diagnosticDetail()} declares that its content "must only ever reach
 * a server-side sink". FIX-03 settled <em>where</em> the detail may go — never the caller, never a
 * response, never a redirect — and left <em>what protects the sink itself</em> unspecified, because at
 * the time nothing in this tree offered more than a process's own stdout to answer that with. The
 * audit trail is exactly that answer: per-tenant hash-chained persistence, access control through
 * {@code AuthorizedAuditTrail}, a declared retention policy, and provable redaction. Routing here does
 * not weaken FIX-03's rule; it is the first sink that is server-side in the sense of <em>control</em>
 * rather than merely of <em>topology</em> — stdout was reachable by anything the deployment's log
 * aggregator could reach, which was never actually "server-side" in the sense the rule meant.</p>
 *
 * <p>{@link GraphMlRejectionAuditEvent#tenantId()} and {@link GraphMlRejectionAuditEvent#subject()} are
 * {@code "-"} when the rejection happened before authentication resolved an identity — the same honest
 * placeholder {@link AuditTrailRateLimitSink} already establishes, landing in the same shared
 * pseudo-tenant chain.</p>
 */
public final class AuditTrailGraphMlRejectionSink implements GraphMlRejectionAuditSink {
    private final AuditTrail auditTrail;

    public AuditTrailGraphMlRejectionSink(AuditTrail auditTrail) {
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
    }

    @Override
    public void record(GraphMlRejectionAuditEvent event) {
        Objects.requireNonNull(event, "event");
        GraphMlRejectionDetail rejection = event.rejection();
        String detail = detail(((Throwable) rejection).getMessage(), rejection.diagnosticDetail());
        auditTrail.append(AuditEnvelope.of(event.tenantId(), event.subject(), AuditCategory.ACCESS,
                "graphml.reject", "graphml", rejection.incidentId(), AuditOutcome.DENIED,
                rejection.reason().name(), event.requestId(), event.occurredAt(),
                OpaquePayload.of(detail.getBytes(StandardCharsets.UTF_8), "application/json")));
    }

    /**
     * {@code message} (already public/sanitised, per {@code GraphMlRejectionDetail}'s own contract) and
     * {@code diagnosticDetail} (document-derived, not public) side by side, so the record is complete
     * without conflating the two disclosure levels the source type keeps apart.
     */
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
