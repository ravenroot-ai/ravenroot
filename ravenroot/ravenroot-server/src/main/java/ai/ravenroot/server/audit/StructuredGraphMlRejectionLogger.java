package ai.ravenroot.server.audit;

import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.core.graph.GraphMlRejectionDetail;

import java.io.PrintStream;
import java.util.Map;
import java.util.Objects;

/**
 * The embedded/narrower-constructor default for {@link GraphMlRejectionAuditSink} (FIX-03).
 *
 * <p>{@code GraphMlRejection} removes document content from the message the caller receives and
 * leaves it on {@link GraphMlRejectionDetail#diagnosticDetail()}. This is where that detail is
 * recorded, so the problem stays diagnosable without appearing in the response. The
 * {@code incidentId} is written here and returned to the caller, and is what joins the two.</p>
 *
 * <p>JSON-lines on a {@link PrintStream}, matching {@link StructuredAuthorizationLogger} and its
 * siblings. Like them it carries no severity level, because these are audit streams rather than
 * logs, and a refused submission is an audit fact rather than a service error.</p>
 *
 * <p><b>Not the production path.</b> {@code RavenrootServerMain} wires
 * {@link AuditTrailGraphMlRejectionSink} instead, which is what actually satisfies
 * {@code diagnosticDetail()}'s "server-side sink" requirement in the sense of control rather than
 * merely of topology: this class writes to an unconditional, ungoverned, no-retention stream that a
 * container's log aggregator captures unconditionally. It remains the default for narrower
 * constructors and embedded/test callers that never supply an {@link AuditTrail}.
 */
public final class StructuredGraphMlRejectionLogger implements GraphMlRejectionAuditSink {
    private final PrintStream output;

    public StructuredGraphMlRejectionLogger(PrintStream output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    @Override
    public void record(GraphMlRejectionAuditEvent event) {
        Objects.requireNonNull(event, "event");
        GraphMlRejectionDetail rejection = event.rejection();
        var line = new StringBuilder("{\"event\":\"graphml_rejected\"")
                .append(",\"requestId\":\"").append(escape(event.requestId())).append('"')
                .append(",\"tenantId\":\"").append(escape(event.tenantId())).append('"')
                .append(",\"subject\":\"").append(escape(event.subject())).append('"')
                .append(",\"incidentId\":\"").append(escape(rejection.incidentId())).append('"')
                .append(",\"reason\":\"").append(rejection.reason()).append('"')
                .append(",\"message\":\"").append(escape(((Throwable) rejection).getMessage())).append('"')
                .append(",\"detail\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : rejection.diagnosticDetail().entrySet()) {
            if (!first) {
                line.append(',');
            }
            first = false;
            line.append('"').append(escape(entry.getKey())).append("\":\"")
                    .append(escape(entry.getValue())).append('"');
        }
        output.println(line.append("}}"));
        if (output.checkError()) {
            throw new IllegalStateException("graphml rejection audit output failed");
        }
    }

    /**
     * Escaping only backslash, quote, newline and carriage return would leave other control
     * characters able to produce invalid JSON. {@link JsonStrings} is the one implementation used
     * across every JSON-lines sink in this module; see its own Javadoc for why.
     */
    private static String escape(String value) {
        return JsonStrings.escape(value);
    }
}
