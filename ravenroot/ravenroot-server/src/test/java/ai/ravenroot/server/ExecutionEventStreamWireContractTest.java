package ai.ravenroot.server;

import ai.ravenroot.api.application.DurableExecutionEvent;
import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionEventStreamWireContractTest {
    private static final UUID PROCESS = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TRAVERSAL = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID INVOCATION = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID EVENT = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID CAUSE = UUID.fromString("00000000-0000-0000-0000-000000000006");

    @Test
    void ringEnvelopeUsesTheExactTransportIdentityAndRetainsTheLegacyProjection() {
        var event = new ExecutionEvent(Long.MIN_VALUE, Instant.MAX, "private-tenant", "private-request",
                "engine\"line\n", "graph", PROCESS, TRAVERSAL, INVOCATION, ATTEMPT,
                ExecutionEventType.NODE_FAILED, "node", 2, false, "private-detail");

        String legacy = RavenrootServer.executionEventJson(event);
        String body = ExecutionEventWireJson.live(event);
        String frame = new String(RavenrootServer.executionEventFrame(event), StandardCharsets.UTF_8);
        String prefix = "{\"schemaVersion\":1,\"source\":\"RING\",\"id\":\""
                + Long.MIN_VALUE + "\",\"eventType\":\"NODE_FAILED\",";

        assertEquals(prefix + legacy.substring(1), body,
                "the versioned stream projection must wrap the byte-identical legacy field projection");
        assertEquals("id: " + Long.MIN_VALUE + "\nevent: execution\ndata: " + body + "\n\n", frame);
        assertInstanceOf(PayloadValue.MapValue.class,
                PayloadJson.read(frameBody(frame).getBytes(StandardCharsets.UTF_8), PayloadLimits.DEFAULTS));
        assertTrue(body.contains("\"sequence\":" + Long.MIN_VALUE));
        assertTrue(body.contains("\"occurredAt\":\"" + Instant.MAX + "\""));
        assertTrue(body.contains("\"processInstanceId\":\"" + PROCESS + "\""));
        assertTrue(body.contains("\"traversalId\":\"" + TRAVERSAL + "\""));
        assertTrue(body.contains("\"type\":\"NODE_FAILED\""));
        assertTrue(body.contains("\"engineId\":\"engine\\\"line\\n\""));
        assertFalse(body.contains("private-tenant"));
        assertFalse(body.contains("private-request"));
        assertFalse(body.contains("private-detail"));
        assertFalse(legacy.contains("\"schemaVersion\""));
        assertFalse(legacy.contains("\"source\""));
        assertFalse(legacy.contains("\"id\""));
        assertFalse(legacy.contains("\"eventType\""));
    }

    @Test
    void durableEnvelopeUsesTheJournalCursorAndPersistedEventIdentityWithoutLiveDiagnostics() {
        var event = new DurableExecutionEvent(EVENT, Long.MAX_VALUE, 73, "private-tenant",
                "FUTURE_EVENT", PROCESS, TRAVERSAL, INVOCATION, ATTEMPT, CAUSE,
                "private-correlation", "graph\"line\n", Instant.MAX, "node\"line\n", null);

        String body = ExecutionEventWireJson.durable(event);
        String frame = new String(RavenrootServer.durableExecutionEventFrame(event), StandardCharsets.UTF_8);

        assertTrue(body.startsWith("{\"schemaVersion\":1,\"source\":\"DURABLE\","
                + "\"id\":\"" + Long.MAX_VALUE + "\",\"eventId\":\"" + EVENT + "\""));
        assertTrue(body.contains("\"journalOffset\":" + Long.MAX_VALUE));
        assertTrue(body.contains("\"streamSequence\":73"));
        assertTrue(body.contains("\"eventType\":\"FUTURE_EVENT\""));
        assertTrue(body.contains("\"occurredAt\":\"" + Instant.MAX + "\""));
        assertTrue(body.contains("\"processInstanceId\":\"" + PROCESS + "\""));
        assertTrue(body.contains("\"traversalId\":\"" + TRAVERSAL + "\""));
        assertTrue(body.contains("\"causationId\":\"" + CAUSE + "\""));
        assertTrue(body.contains("\"graphVersion\":\"graph\\\"line\\n\""));
        assertTrue(body.contains("\"nodeId\":\"node\\\"line\\n\""));
        assertEquals("id: " + Long.MAX_VALUE + "\nevent: execution\ndata: " + body + "\n\n", frame);
        assertInstanceOf(PayloadValue.MapValue.class,
                PayloadJson.read(frameBody(frame).getBytes(StandardCharsets.UTF_8), PayloadLimits.DEFAULTS));
        assertFalse(body.contains("private-tenant"));
        assertFalse(body.contains("private-correlation"));
        assertFalse(body.contains("\"sequence\""));
        assertFalse(body.contains("\"type\""));
        assertFalse(body.contains("\"activeInstances\""));
        assertFalse(body.contains("\"detail\""));
        assertFalse(body.contains("\"message\""));
        assertFalse(body.contains("\"output\""));
    }

    private static String frameBody(String frame) {
        int start = frame.indexOf("data: ") + "data: ".length();
        return frame.substring(start, frame.length() - 2);
    }
}
