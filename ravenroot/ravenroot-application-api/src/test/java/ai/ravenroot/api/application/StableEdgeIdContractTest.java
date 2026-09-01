package ai.ravenroot.api.application;

import ai.ravenroot.api.persistence.EdgeTraversalEventData;
import ai.ravenroot.api.persistence.OpaquePayload;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StableEdgeIdContractTest {

    @Test
    void acceptsTheExactUtf8BoundWithoutChangingIdentityAndRejectsOneByteMore() {
        String exact = "é".repeat(StableEdgeId.MAX_UTF8_BYTES / 2);
        String over = exact + "x";

        assertEquals(8_192, StableEdgeId.MAX_UTF8_BYTES,
                "the public Java and browser GraphML contracts must move together");
        assertEquals(StableEdgeId.SSE_FRAME_MAX_BYTES - StableEdgeId.SSE_NON_ID_RESERVE_BYTES,
                StableEdgeId.MAX_UTF8_BYTES * StableEdgeId.MAX_JSON_ESCAPED_BYTES_PER_ID_BYTE);
        assertEquals(StableEdgeId.MAX_UTF8_BYTES, StableEdgeId.strictUtf8Length(exact));
        assertEquals(exact, StableEdgeId.requireValid(exact));
        assertThrows(IllegalArgumentException.class, () -> StableEdgeId.requireValid(over));
        String malformed = "broken" + Character.highSurrogate(0x10000);
        assertThrows(IllegalArgumentException.class, () -> StableEdgeId.requireValid(malformed));
    }

    @Test
    void executionEventEnforcesTheSameExactBoundary() {
        String exact = "x".repeat(StableEdgeId.MAX_UTF8_BYTES);

        assertEquals(exact, edgeEvent(exact).edgeId());
        assertThrows(IllegalArgumentException.class, () -> edgeEvent(exact + "x"));
    }

    @Test
    void durableCodecAcceptsTheExactBoundaryAndFailsClosedAtOneByteMore() {
        String exact = "x".repeat(StableEdgeId.MAX_UTF8_BYTES);
        OpaquePayload exactPayload = EdgeTraversalEventData.payload(exact);
        OpaquePayload overPayload = OpaquePayload.of((exact + "x").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                EdgeTraversalEventData.CONTENT_TYPE);

        assertEquals(exact, EdgeTraversalEventData.edgeId(exactPayload).orElseThrow());
        assertTrue(EdgeTraversalEventData.edgeId(overPayload).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> EdgeTraversalEventData.payload(exact + "x"));
    }

    @Test
    void durableProjectionEnforcesTheSameBoundaryWithoutChangingValidIdentity() {
        String exact = "x".repeat(StableEdgeId.MAX_UTF8_BYTES);

        assertEquals(exact, durableEvent(exact).edgeId());
        assertThrows(IllegalArgumentException.class, () -> durableEvent(exact + "x"));
    }

    @Test
    void everyTraversalBoundaryPreservesWhitespaceAsIdentity() {
        String exact = " edge ";

        assertEquals(exact, StableEdgeId.requireValid(exact));
        assertEquals(exact, edgeEvent(exact).edgeId());
        assertEquals(exact, EdgeTraversalEventData.edgeId(EdgeTraversalEventData.payload(exact)).orElseThrow());
        assertEquals(exact, durableEvent(exact).edgeId());
    }

    @Test
    void auxiliaryReserveIsEnforcedAsOneCombinedEscapedByteBudget() {
        int maximum = EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES;
        String exact = escapedBytes(maximum);

        EdgeTraversalWireBudget.requireLiveProjection(exact, null, null, null, null,
                null, null, null, null, null);
        EdgeTraversalWireBudget.requireDurableProjection("EDGE_TRAVERSED",
                escapedBytes(maximum - "EDGE_TRAVERSED".length()), null);
        assertThrows(IllegalArgumentException.class,
                () -> EdgeTraversalWireBudget.requireLiveProjection(exact + "x", null, null, null,
                        null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> EdgeTraversalWireBudget.requireLiveProjection(escapedBytes(maximum / 2),
                        escapedBytes(maximum - maximum / 2 + 1), null, null, null,
                        null, null, null, null, null));
    }

    @Test
    void traversalEventsRejectAuxiliaryOverflowBeforeTheyCanReachAnAdapter() {
        String over = escapedBytes(EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES + 1);

        assertThrows(IllegalArgumentException.class, () -> edgeEventWithEngine("edge", over));
        assertThrows(IllegalArgumentException.class,
                () -> durableEvent("edge", over, "source"));
    }

    private static ExecutionEvent edgeEvent(String edgeId) {
        return edgeEventWithEngine(edgeId, "engine");
    }

    private static ExecutionEvent edgeEventWithEngine(String edgeId, String engineId) {
        UUID execution = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID invocation = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID attempt = UUID.fromString("00000000-0000-0000-0000-000000000003");
        return new ExecutionEvent(1, Instant.EPOCH, "tenant", "request", engineId, "graph", execution,
                execution, invocation, attempt, ExecutionEventType.EDGE_TRAVERSED, "source", 0, false,
                "edge traversed", null, null, null, null, null, 0, "continue", null, null, edgeId);
    }

    private static DurableExecutionEvent durableEvent(String edgeId) {
        return durableEvent(edgeId, "graph", "source");
    }

    private static DurableExecutionEvent durableEvent(String edgeId, String graphVersion, String nodeId) {
        UUID execution = UUID.fromString("00000000-0000-0000-0000-000000000001");
        return new DurableExecutionEvent(UUID.fromString("00000000-0000-0000-0000-000000000004"),
                1, 1, "tenant", "EDGE_TRAVERSED", execution, execution, null, null, null,
                "correlation", graphVersion, Instant.EPOCH, nodeId, edgeId);
    }

    private static String escapedBytes(int count) {
        int controls = count / 6;
        int remainder = count % 6;
        return Character.toString(1).repeat(controls) + "x".repeat(remainder);
    }
}
