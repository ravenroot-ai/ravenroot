package ai.ravenroot.server.audit;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.StableEdgeId;

import java.io.PrintStream;
import java.util.function.Consumer;

/**
 * Emits execution events to the server's own log as one JSON object per line.
 *
 * <p>This is the <strong>server-side audit</strong> projection and is deliberately <em>not</em> the
 * SSE projection, which {@code RavenrootServer} serialises separately. They have different audiences
 * and different disclosure rules, which is why the two serialisers are not merged.</p>
 *
 * <h2>Everything this line carries that the SSE frame does not</h2>
 * <p>Kept as a list rather than a sentence, because it used to say "exactly one respect" and had
 * silently become three. Each entry is a deliberate divergence with its own reason, and
 * {@code StructuredExecutionLoggerTest} asserts the set so the next addition cannot slip in
 * unrecorded:</p>
 * <ul>
 *   <li>{@code tenantId} and {@code requestId} — so an operator can join an execution to the
 *       authorization decision that permitted it. The SSE frame carries neither, so a browser client
 *       is not told about tenant naming (SEC-07).</li>
 *   <li>{@code attemptOrdinal} — without it a retry's {@code NODE_STARTED} is byte-identical to an
 *       initial attempt's on the line an operator greps, so three starts could not be told from one
 *       visit retried twice. It is durable state the aggregate already holds, so the HTTP projection
 *       leaves it out rather than shipping a copy a durable replay could not reproduce; a log line is
 *       written once, at the moment it was true, and has no replay to disagree with.</li>
 *   <li>{@code connectorAttempts} — the same argument, for retries a connector performed inside one
 *       orchestration attempt.</li>
 * </ul>
 *
 * <p>{@code publicReason} is carried by both and is not a divergence.</p>
 */
public final class StructuredExecutionLogger implements Consumer<ExecutionEvent> {
    private final PrintStream output;

    public StructuredExecutionLogger(PrintStream output) {
        this.output = output;
    }

    @Override
    public void accept(ExecutionEvent event) {
        output.println(toJson(event));
    }

    public static String toJson(ExecutionEvent event) {
        return "{\"event\":\"ravenroot.execution\""
                + ",\"sequence\":" + event.sequence()
                + ",\"occurredAt\":\"" + event.occurredAt() + "\""
                + ",\"tenantId\":\"" + escape(event.tenantId()) + "\""
                + ",\"requestId\":\"" + escape(event.requestId()) + "\""
                + ",\"engineId\":\"" + escape(event.engineId()) + "\""
                + ",\"graphVersion\":\"" + escape(event.graphVersion()) + "\""
                + ",\"processInstanceId\":\"" + event.processInstanceId() + "\""
                + ",\"traversalId\":\"" + event.traversalId() + "\""
                + ",\"executionId\":\"" + event.executionId() + "\""
                + ",\"invocationId\":" + (event.invocationId() == null ? "null" : "\"" + event.invocationId() + "\"")
                + ",\"attemptId\":" + (event.attemptId() == null ? "null" : "\"" + event.attemptId() + "\"")
                + ",\"type\":\"" + event.type() + "\""
                + ",\"nodeId\":" + (event.nodeId() == null ? "null" : "\"" + escape(event.nodeId()) + "\"")
                + ",\"edgeId\":" + (event.edgeId() == null ? "null"
                        : "\"" + escape(StableEdgeId.requireValid(event.edgeId())) + "\"")
                // Appended as a trailing key, per this method's own additive-JSON rule.
                + ",\"nodeCatalogKey\":" + (event.nodeCatalogKey() == null ? "null"
                        : "\"" + escape(event.nodeCatalogKey()) + "\"")
                + ",\"activeInstances\":" + event.activeInstances()
                // Appended next to the number it must not be confused with, deliberately: read in
                // a log line the two are only tellable apart by their keys.
                + ",\"inFlightArrivals\":" + event.inFlightArrivals()
                + ",\"fallback\":" + event.fallback()
                + ",\"detail\":\"" + escape(event.detail()) + "\""
                // A duration, in seconds -- never identifying, unlike
                // every other field this line already carries (SEC-07). null on every event that is
                // not a join settlement (JOIN_SATISFIED/JOIN_FAILED), matching
                // ExecutionEvent.joinWaitDuration's own contract: absent is not recorded as zero.
                // Appended as a new trailing key rather than inserted among the existing ones, so an
                // external consumer keying on field order (as opposed to field name, which every
                // JSON parser supports) is the only kind this could affect -- see this method's own
                // test, which asserts by substring/key presence and needed no change for this.
                + ",\"joinWaitDuration\":" + (event.joinWaitDuration() == null ? "null"
                        : event.joinWaitDuration().toNanos() / 1_000_000_000.0)
                + ",\"processingDuration\":" + (event.processingDuration() == null ? "null"
                        : event.processingDuration().toNanos() / 1_000_000_000.0)
                // ADR 0021 D5 identity channel, with the same discipline as every
                // other identifier on this line (SEC-07): absent (a one-shot/playground submission)
                // is null, never an empty string that would look like a real, if blank, identity.
                // Trailing, for the same field-order reason joinWaitDuration/processingDuration are.
                + ",\"deploymentId\":" + (event.deploymentId() == null ? "null"
                        : "\"" + escape(event.deploymentId()) + "\"")
                + ",\"workloadId\":" + (event.workloadId() == null ? "null"
                        : "\"" + escape(event.workloadId()) + "\"")
                // The attempt-scoped counts, trailing for the same field-order reason as the keys
                // above. Without the ordinal a retry's NODE_STARTED is byte-identical to an initial
                // attempt's on the one line an operator actually greps, so the audit log could show a
                // node starting three times and give no way to tell three visits from one visit
                // retried twice. 0 on both keys reads as "not stated", exactly as it does on the
                // event: it is not a claim that this was an initial attempt, nor that a connector
                // tried exactly once.
                + ",\"attemptOrdinal\":" + event.attemptOrdinal()
                + ",\"connectorAttempts\":" + event.connectorAttempts()
                // The bounded classifier, and the only new key here that can be absent. On a retry it
                // names why the failure was considered repeatable, which is what turns a run of retry
                // lines from noise into a diagnosis.
                + ",\"publicReason\":" + (event.publicReason() == null ? "null"
                        : "\"" + escape(event.publicReason()) + "\"")
                + "}";
    }

    /** The one shared implementation; see {@link JsonStrings}'s own Javadoc for why. */
    private static String escape(String value) {
        return JsonStrings.escape(value);
    }
}
