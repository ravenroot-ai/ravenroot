package ai.ravenroot.server;

import ai.ravenroot.api.application.DurableExecutionEvent;
import ai.ravenroot.api.application.PublicExecutionDescription;
import ai.ravenroot.api.application.StableEdgeId;

/** Canonical durable-event JSON shared by SSE and bidirectional transports. */
public final class ExecutionEventWireJson {
    private ExecutionEventWireJson() {
    }

    public static String durable(DurableExecutionEvent event) {
        String description = PublicExecutionDescription.forEventType(event.eventType());
        return "{\"journalOffset\":" + event.journalOffset()
                + ",\"streamSequence\":" + event.streamSequence()
                + ",\"occurredAt\":\"" + event.occurredAt() + "\""
                + ",\"eventType\":\"" + escape(event.eventType()) + "\""
                + ",\"description\":\"" + escape(description) + "\""
                + ",\"graphVersion\":\"" + escape(event.graphVersion()) + "\""
                + ",\"processInstanceId\":\"" + event.processInstanceId() + "\""
                + ",\"traversalId\":\"" + event.traversalId() + "\""
                + ",\"invocationId\":" + nullable(event.invocationId())
                + ",\"attemptId\":" + nullable(event.attemptId())
                + ",\"causationId\":" + nullable(event.causationId())
                + ",\"nodeId\":" + nullableEscaped(event.nodeId())
                + ",\"edgeId\":" + nullableEscaped(event.edgeId() == null ? null
                        : StableEdgeId.requireValid(event.edgeId()))
                + ",\"handlerId\":" + nullable(event.handlerId())
                + "}";
    }

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

    private static String nullable(Object value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static String nullableEscaped(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }
}
