package ai.ravenroot.server.error;

import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.server.audit.JsonStrings;

import java.io.PrintStream;
import java.util.Map;
import java.util.Objects;

/**
 * The embedded/narrower-constructor default for {@link PayloadRejectionAuditSink} (API-01).
 *
 * <p>The payload module removes payload content from the message the caller receives and leaves it on
 * {@link PayloadException#diagnosticDetail()}. This is where that detail is recorded, so a rejected
 * payload stays diagnosable without the response carrying it. The {@code incidentId} is written here
 * and returned in the error envelope, and is what joins the two.</p>
 *
 * <p>It deliberately sits in its own package rather than beside the GraphML sink because it implements
 * the payload-rejection boundary. The output format — JSON lines on a {@link PrintStream}, no severity
 * level, because this is an audit stream rather than a log — matches its siblings exactly.</p>
 *
 * <p><b>Not the production path.</b> {@code RavenrootServerMain} wires
 * {@code AuditTrailPayloadRejectionSink} instead. That is what actually satisfies
 * {@code diagnosticDetail()}'s "server-side sink" requirement in the sense of control rather than
 * merely of topology: this class writes to an unconditional, ungoverned, no-retention stream. It
 * remains the default for narrower constructors and embedded/test callers that never supply an
 * {@link AuditTrail}.
 */
public final class StructuredPayloadRejectionLogger implements PayloadRejectionAuditSink {
    private final PrintStream output;

    public StructuredPayloadRejectionLogger(PrintStream output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    @Override
    public void record(PayloadRejectionAuditEvent event) {
        Objects.requireNonNull(event, "event");
        PayloadException rejection = event.rejection();
        var line = new StringBuilder("{\"event\":\"payload_rejected\"")
                .append(",\"correlationId\":\"").append(escape(event.requestId())).append('"')
                .append(",\"tenantId\":\"").append(escape(event.tenantId())).append('"')
                .append(",\"subject\":\"").append(escape(event.subject())).append('"')
                .append(",\"incidentId\":\"").append(escape(rejection.incidentId())).append('"')
                .append(",\"code\":\"").append(rejection.code()).append('"')
                .append(",\"message\":\"").append(escape(rejection.getMessage())).append('"')
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
            throw new IllegalStateException("payload rejection audit output failed");
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
