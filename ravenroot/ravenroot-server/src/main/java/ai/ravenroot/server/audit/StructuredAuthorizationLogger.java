package ai.ravenroot.server.audit;

import ai.ravenroot.api.security.AuthorizationAuditEvent;
import ai.ravenroot.api.security.AuthorizationAuditSink;

import java.io.PrintStream;
import java.util.Objects;

/** Payload-free JSON-lines authorization audit sink. */
public final class StructuredAuthorizationLogger implements AuthorizationAuditSink {
    private final PrintStream output;

    public StructuredAuthorizationLogger(PrintStream output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    @Override
    public void record(AuthorizationAuditEvent event) {
        Objects.requireNonNull(event, "event");
        output.println("{\"event\":\"authorization_decision\",\"occurredAt\":\"" + event.occurredAt()
                + "\",\"requestId\":\"" + escape(event.requestId())
                + "\",\"subject\":\"" + escape(event.subject())
                + "\",\"tenantId\":\"" + escape(event.tenantId())
                + "\",\"action\":\"" + event.action()
                + "\",\"resourceType\":\"" + escape(event.resourceType())
                + "\",\"resourceId\":\"" + escape(event.resourceId())
                + "\",\"allowed\":" + event.allowed()
                + ",\"reason\":\"" + escape(event.reason()) + "\"}");
        if (output.checkError()) {
            throw new IllegalStateException("authorization audit output failed");
        }
    }

    /**
     * The previous escaping here handled only backslash, quote, newline and carriage
     * return would let a raw tab or any other sub-{@code 0x20} character reach the output as invalid
     * JSON. {@link JsonStrings} is the one implementation every JSON-lines sink in this module uses.
     */
    private static String escape(String value) {
        return JsonStrings.escape(value);
    }
}
