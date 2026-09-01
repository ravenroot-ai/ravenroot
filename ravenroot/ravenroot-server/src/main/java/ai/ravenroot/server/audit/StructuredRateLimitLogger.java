package ai.ravenroot.server.audit;

import ai.ravenroot.server.ratelimit.RateLimitAuditEvent;
import ai.ravenroot.server.ratelimit.RateLimitAuditSink;

import java.io.PrintStream;
import java.util.Objects;

/**
 * Payload-free JSON-lines rate-limit audit sink, matching the SEC-07 record style.
 *
 * <p>The record shares {@code requestId} with the authorization, artifact-lifecycle and execution
 * records, so a throttled request can be joined to whatever else the same request produced. That is the
 * point of emitting it here rather than as an unstructured log line.</p>
 */
public final class StructuredRateLimitLogger implements RateLimitAuditSink {
    private final PrintStream output;

    public StructuredRateLimitLogger(PrintStream output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    /**
     * Not every limiter record is a refusal.
     *
     * <p>Reclaiming an active-execution entry whose execution never terminated is a limiter decision
     * that must be visible, but nothing was refused and no status was sent. Labelling it
     * {@code rate_limit_rejection} would put a fabricated event in the audit stream, so records that
     * carry no HTTP status are labelled for what they are.</p>
     */
    private static String eventName(RateLimitAuditEvent event) {
        return event.status() >= 400 ? "rate_limit_rejection" : "rate_limit_reclaim";
    }

    @Override
    public void record(RateLimitAuditEvent event) {
        Objects.requireNonNull(event, "event");
        output.println("{\"event\":\"" + eventName(event) + "\",\"occurredAt\":\"" + event.occurredAt()
                + "\",\"requestId\":\"" + escape(event.requestId())
                + "\",\"clientAddress\":\"" + escape(event.clientAddress())
                + "\",\"forwarded\":" + event.forwarded()
                + ",\"tenantId\":\"" + escape(event.tenantId())
                + "\",\"subject\":\"" + escape(event.subject())
                + "\",\"method\":\"" + escape(event.method())
                + "\",\"path\":\"" + escape(event.path())
                + "\",\"code\":\"" + escape(event.code())
                + "\",\"scope\":\"" + escape(event.scope())
                + "\",\"status\":" + event.status()
                + ",\"retryAfterSeconds\":" + event.retryAfterSeconds() + "}");
        if (output.checkError()) {
            throw new IllegalStateException("rate limit audit output failed");
        }
    }

    /** {@link JsonStrings} is the one implementation every sink in this module uses. */
    private static String escape(String value) {
        return JsonStrings.escape(value);
    }
}
