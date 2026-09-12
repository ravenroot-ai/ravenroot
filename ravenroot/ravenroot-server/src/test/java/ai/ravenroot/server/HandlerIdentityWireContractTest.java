package ai.ravenroot.server;

import ai.ravenroot.api.application.DurableExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.persistence.HandlerEventData;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handler identity has to survive the two public event projections, or the fourth level exists
 * only inside the JVM.
 *
 * <p>Both projections are asserted in both directions — present on a handler event, explicitly null
 * on every other one. The null half is the half that rots quietly: a serializer that emitted the
 * field only when set would leave a client unable to tell "this event has no handler" from "this
 * server is too old to say", and that ambiguity is exactly what a nullable JSON field is for.</p>
 */
class HandlerIdentityWireContractTest {

    private static final UUID HANDLER_ID = UUID.fromString("6f1f3a4c-1d2e-4a5b-8c7d-9e0f1a2b3c4d");

    @Test
    void theDurableStreamFrameNamesTheHandlerAndNullsItOnEveryOtherEventType() {
        String handlerFrame = frame(HandlerEventData.HANDLER_RESOLVED, HANDLER_ID);
        assertTrue(handlerFrame.contains("\"handlerId\":\"" + HANDLER_ID + "\""), handlerFrame);
        assertTrue(handlerFrame.contains("\"processInstanceId\":")
                        && handlerFrame.contains("\"traversalId\":")
                        && handlerFrame.contains("\"invocationId\":"),
                () -> "the handler is an addition to the other three, not a replacement: " + handlerFrame);
        assertTrue(handlerFrame.contains("A handler was resolved and the process re-entered."),
                () -> "a handler event must not render as generic activity: " + handlerFrame);

        String nodeFrame = frame(ExecutionEventType.NODE_COMPLETED.name(), null);
        assertTrue(nodeFrame.contains("\"handlerId\":null"),
                () -> "absent must be stated, or a client cannot tell it from an old server: " + nodeFrame);
    }

    @Test
    void theRecentPollingRowNamesTheHandlerAndNullsItOnEveryOtherEventType() {
        String handlerRow = RavenrootServer.durableRecentEventJson(
                event(HandlerEventData.HANDLER_DENIED, HANDLER_ID));
        assertTrue(handlerRow.contains("\"handlerId\":\"" + HANDLER_ID + "\""), handlerRow);
        assertTrue(handlerRow.contains("A handler was denied and the process continued."), handlerRow);

        String nodeRow = RavenrootServer.durableRecentEventJson(
                event(ExecutionEventType.NODE_STARTED.name(), null));
        assertTrue(nodeRow.contains("\"handlerId\":null"), nodeRow);
    }

    /**
     * The identity is a bare UUID, so it cannot need escaping and its cost is a fixed 36 bytes inside
     * the projection's own reserve. Asserted rather than assumed, because the frame's size budget is
     * a published contract and this field was added to a shape that was already saturated in test.
     */
    @Test
    void theHandlerIdentityIsAFixedWidthUnescapedAddition() {
        int withHandler = frame(HandlerEventData.HANDLER_RESOLVED, HANDLER_ID)
                .getBytes(StandardCharsets.UTF_8).length;
        int withoutHandler = frame(HandlerEventData.HANDLER_RESOLVED, null)
                .getBytes(StandardCharsets.UTF_8).length;

        int quotedUuidBytes = ("\"" + HANDLER_ID + "\"").getBytes(StandardCharsets.UTF_8).length;
        assertEquals(38, quotedUuidBytes, "a canonical UUID in quotes");
        assertEquals(quotedUuidBytes - "null".length(), withHandler - withoutHandler,
                "the widest this field can ever be is one quoted UUID replacing the literal null, so "
                        + "its cost to the frame's published size budget is fixed and knowable");
        assertFalse(frame(HandlerEventData.HANDLER_RESOLVED, HANDLER_ID).contains("\\u"),
                "a UUID contains nothing this serializer escapes");
    }

    private static String frame(String eventType, UUID handlerId) {
        return new String(RavenrootServer.durableExecutionEventFrame(event(eventType, handlerId)),
                StandardCharsets.UTF_8);
    }

    private static DurableExecutionEvent event(String eventType, UUID handlerId) {
        return new DurableExecutionEvent(UUID.randomUUID(), 9L, 3L, "acme", eventType,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null, "request-1",
                "graph-v1", Instant.parse("2026-01-01T00:00:00Z"), "await-approval", null, handlerId);
    }
}
