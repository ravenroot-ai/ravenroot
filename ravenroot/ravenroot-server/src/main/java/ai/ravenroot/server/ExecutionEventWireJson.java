package ai.ravenroot.server;

import ai.ravenroot.api.application.DurableExecutionEvent;
import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.PublicExecutionDescription;
import ai.ravenroot.api.application.RuntimeActivityData;
import ai.ravenroot.api.application.StableEdgeId;
import ai.ravenroot.api.payload.PayloadJson;

/** Versioned JSON projections carried by {@code event: execution} SSE frames. */
public final class ExecutionEventWireJson {
    public static final int SCHEMA_VERSION = 1;
    public static final String RING_SOURCE = "RING";
    public static final String DURABLE_SOURCE = "DURABLE";

    private ExecutionEventWireJson() {
    }

    /**
     * Projects one process-local event for the SSE stream. The legacy live projection remains
     * embedded unchanged so old readers keep every field and alias they already understand.
     */
    public static String live(ExecutionEvent event) {
        String legacy = legacyLive(event);
        return "{\"schemaVersion\":" + SCHEMA_VERSION
                + ",\"source\":\"" + RING_SOURCE + "\""
                + ",\"id\":\"" + event.sequence() + "\""
                + ",\"eventType\":\"" + event.type() + "\","
                + legacy.substring(1);
    }

    /**
     * Projects one journal row for the SSE stream. Ordering remains the tenant-local journal
     * offset; {@code eventId} is the existing persisted deduplication identity, not a new cursor.
     */
    public static String durable(DurableExecutionEvent event) {
        String description = PublicExecutionDescription.forEventType(event.eventType());
        return "{\"schemaVersion\":" + SCHEMA_VERSION
                + ",\"source\":\"" + DURABLE_SOURCE + "\""
                + ",\"id\":\"" + event.journalOffset() + "\""
                + ",\"eventId\":\"" + event.eventId() + "\""
                + ",\"journalOffset\":" + event.journalOffset()
                + ",\"streamSequence\":" + event.streamSequence()
                + ",\"occurredAt\":\"" + event.occurredAt() + "\""
                + ",\"eventType\":\"" + escape(event.eventType()) + "\""
                + ",\"description\":\"" + escape(description) + "\""
                + ",\"graphVersion\":\"" + escape(event.graphVersion()) + "\""
                + ",\"processInstanceId\":\"" + event.processInstanceId() + "\""
                + ",\"traversalId\":\"" + event.traversalId() + "\""
                + ",\"invocationId\":" + nullableUuid(event.invocationId())
                + ",\"attemptId\":" + nullableUuid(event.attemptId())
                + ",\"causationId\":" + nullableUuid(event.causationId())
                + ",\"nodeId\":" + nullableEscaped(event.nodeId())
                + ",\"edgeId\":" + (event.edgeId() == null ? "null"
                        : "\"" + escape(StableEdgeId.requireValid(event.edgeId())) + "\"")
                + ",\"handlerId\":" + nullableUuid(event.handlerId())
                + "}";
    }

    /**
     * The process-local projection used by the legacy recent-events endpoint and embedded in
     * {@link #live(ExecutionEvent)}.
     *
     * <p>Additive only: {@code deploymentId} joined the existing fields and no field was renamed,
     * retyped or removed, so a reader that already understood this object still does.</p>
     */
    static String legacyLive(ExecutionEvent event) {
        String description = PublicExecutionDescription.forType(event.type(), event.publicReason());
        RuntimeActivityData.TextProjection message = event.authorMessage();
        return "{\"sequence\":" + event.sequence()
                + ",\"occurredAt\":\"" + event.occurredAt() + "\""
                + ",\"engineId\":\"" + escape(event.engineId()) + "\""
                + ",\"graphVersion\":\"" + escape(event.graphVersion()) + "\""
                + ",\"processInstanceId\":\"" + event.processInstanceId() + "\""
                + ",\"traversalId\":\"" + event.traversalId() + "\""
                + ",\"executionId\":\"" + event.executionId() + "\""
                // The identity of the LONG-LIVED thing the traversal belongs to, which is the only
                // identity a client can hold in advance for a source: a listening session emits
                // traversals whose ids nobody knows before they exist, so an event that names only
                // its own traversal cannot be attributed to the graph that started the session.
                // `null` for a one-shot submission that opened no deployment domain, exactly as
                // ExecutionEvent#deploymentId documents.
                + ",\"deploymentId\":" + nullableEscaped(event.deploymentId())
                + ",\"invocationId\":" + nullableUuid(event.invocationId())
                + ",\"attemptId\":" + nullableUuid(event.attemptId())
                + ",\"type\":\"" + event.type() + "\""
                + ",\"nodeId\":" + nullableEscaped(event.nodeId())
                + ",\"edgeId\":" + (event.edgeId() == null ? "null"
                        : "\"" + escape(StableEdgeId.requireValid(event.edgeId())) + "\"")
                + ",\"activeInstances\":" + event.activeInstances()
                + ",\"inFlightArrivals\":" + event.inFlightArrivals()
                + ",\"fallback\":" + event.fallback()
                + ",\"description\":\"" + escape(description) + "\""
                + ",\"publicReason\":" + nullableEscaped(event.publicReason())
                + ",\"message\":" + (message == null ? "null" : "\"" + escape(message.value()) + "\"")
                + ",\"messageRedacted\":" + (message != null && message.redacted())
                + ",\"messageTruncated\":" + (message != null && message.truncated())
                + (event.authorOutput() == null ? ""
                        : ",\"output\":" + PayloadJson.write(event.authorOutput().value())
                                + ",\"outputRedacted\":" + event.authorOutput().redacted()
                                + ",\"outputTruncated\":" + event.authorOutput().truncated())
                + ",\"processingDuration\":" + (event.processingDuration() == null ? "null"
                        : event.processingDuration().toNanos() / 1_000_000_000.0)
                + "}";
    }

    /** Escapes one JSON string value without admitting a general-purpose object serializer. */
    public static String escape(String value) {
        if (value == null) return "";
        var escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.toString();
    }

    private static String nullableEscaped(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    private static String nullableUuid(java.util.UUID value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
