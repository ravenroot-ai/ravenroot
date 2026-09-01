package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.DurableExecutionEvent;
import ai.ravenroot.api.application.EdgeTraversalWireBudget;
import ai.ravenroot.api.application.StableEdgeId;
import ai.ravenroot.api.persistence.EdgeTraversalEventData;
import ai.ravenroot.server.audit.StructuredExecutionLogger;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StableEdgeIdWireContractTest {

    @Test
    void worstCaseEscapedMaximumFitsTheCompleteRuntimeClientFrame() {
        String oneByteControl = Character.toString(1);
        String edgeId = oneByteControl.repeat(StableEdgeId.MAX_UTF8_BYTES);
        String escapedId = "\\u0001".repeat(StableEdgeId.MAX_UTF8_BYTES);

        ExecutionEvent event = edgeEvent(edgeId);
        String recentJson = RavenrootServer.executionEventJson(event);
        byte[] liveFrame = RavenrootServer.executionEventFrame(event);

        assertTrue(recentJson.contains("\"edgeId\":\"" + escapedId + "\""),
                "the shared recent/live projection must preserve every escaped identity byte");
        assertEquals(recentJson, new String(liveFrame, StandardCharsets.UTF_8)
                .substring(new String(liveFrame, StandardCharsets.UTF_8).indexOf("data: ") + 6).trim());
        assertTrue(liveFrame.length < StableEdgeId.SSE_FRAME_MAX_BYTES,
                () -> "complete SSE frame is " + liveFrame.length + " bytes");
        assertTrue(StableEdgeId.SSE_FRAME_MAX_BYTES - liveFrame.length
                        < StableEdgeId.SSE_NON_ID_RESERVE_BYTES,
                "the control-character identity exercises the six-byte worst-case expansion");
    }

    @Test
    void oneByteOverCannotReachEitherWireProjection() {
        String over = "x".repeat(StableEdgeId.MAX_UTF8_BYTES + 1);

        assertThrows(IllegalArgumentException.class, () -> edgeEvent(over));
    }

    @Test
    void saturatedLiveAndLogFieldsStillFitWithTheMaximumEscapedIdentity() {
        String edgeId = Character.toString(1).repeat(StableEdgeId.MAX_UTF8_BYTES);
        int fixedDynamic = escapedLength("tenant") + escapedLength("request")
                + escapedLength("graph") + escapedLength("source") + escapedLength("continue")
                + escapedLength("edge traversed") + escapedLength("catalog")
                + escapedLength("deployment") + escapedLength("workload");
        String engine = escapedBytes(EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES - fixedDynamic);
        ExecutionEvent event = fullEdgeEvent(edgeId, "tenant", "request", engine, "graph", "source",
                "continue", "edge traversed", "catalog", "deployment", "workload");

        byte[] liveFrame = RavenrootServer.executionEventFrame(event);
        byte[] logLine = StructuredExecutionLogger.toJson(event).getBytes(StandardCharsets.UTF_8);

        assertTrue(liveFrame.length < StableEdgeId.SSE_FRAME_MAX_BYTES,
                () -> "saturated live frame is " + liveFrame.length + " bytes");
        assertTrue(logLine.length < StableEdgeId.SSE_FRAME_MAX_BYTES,
                () -> "saturated structured log is " + logLine.length + " bytes");
        int liveDynamic = escapedLength(engine) + escapedLength("graph") + escapedLength("source")
                + escapedLength("continue");
        assertTrue(liveFrame.length - StableEdgeId.MAX_UTF8_BYTES * 6 - liveDynamic
                        <= EdgeTraversalWireBudget.FIXED_PROJECTION_RESERVE_BYTES,
                "fixed live syntax must stay inside its canonical reserve");
        assertTrue(logLine.length - StableEdgeId.MAX_UTF8_BYTES * 6
                        - EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES
                        <= EdgeTraversalWireBudget.FIXED_PROJECTION_RESERVE_BYTES,
                "fixed structured-log syntax must stay inside its canonical reserve");
        assertTrue(new String(liveFrame, StandardCharsets.UTF_8)
                .contains("\"edgeId\":\"" + "\\u0001".repeat(StableEdgeId.MAX_UTF8_BYTES) + "\""));
    }

    @Test
    void saturatedDurableProjectionAndPayloadStayInsideTheirExplicitBounds() {
        String edgeId = Character.toString(1).repeat(StableEdgeId.MAX_UTF8_BYTES);
        int eventTypeBytes = escapedLength(ExecutionEventType.EDGE_TRAVERSED.name());
        String graphVersion = escapedBytes(EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES
                - eventTypeBytes - escapedLength("source"));
        DurableExecutionEvent event = durableEdgeEvent(edgeId, graphVersion, "source");

        byte[] durableFrame = RavenrootServer.durableExecutionEventFrame(event);

        assertTrue(durableFrame.length < StableEdgeId.SSE_FRAME_MAX_BYTES,
                () -> "saturated durable frame is " + durableFrame.length + " bytes");
        assertTrue(durableFrame.length - StableEdgeId.MAX_UTF8_BYTES * 6
                        - EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES
                        <= EdgeTraversalWireBudget.FIXED_PROJECTION_RESERVE_BYTES,
                "fixed durable syntax must stay inside its canonical reserve");
        assertEquals(StableEdgeId.MAX_UTF8_BYTES, EdgeTraversalEventData.payload(edgeId).size());
    }

    @Test
    void oneAuxiliaryByteOverIsRejectedBeforeLiveDurableOrLogSerialization() {
        String edgeId = Character.toString(1).repeat(StableEdgeId.MAX_UTF8_BYTES);
        String over = escapedBytes(EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES + 1);

        assertThrows(IllegalArgumentException.class,
                () -> fullEdgeEvent(edgeId, "t", "r", over, null, null,
                        null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> durableEdgeEvent(edgeId, over, null));
    }

    @Test
    void wireProjectionPreservesWhitespaceAsExactIdentity() {
        ExecutionEvent event = edgeEvent(" edge ");

        assertTrue(RavenrootServer.executionEventJson(event).contains("\"edgeId\":\" edge \""));
        assertTrue(StructuredExecutionLogger.toJson(event).contains("\"edgeId\":\" edge \""));
        assertTrue(new String(RavenrootServer.durableExecutionEventFrame(
                durableEdgeEvent(" edge ", "graph", "source")), StandardCharsets.UTF_8)
                .contains("\"edgeId\":\" edge \""));
    }

    private static ExecutionEvent edgeEvent(String edgeId) {
        return fullEdgeEvent(edgeId, "tenant", "request", "engine", "graph", "source",
                "continue", "edge traversed", null, null, null);
    }

    private static ExecutionEvent fullEdgeEvent(String edgeId, String tenantId, String requestId,
                                                String engineId, String graphVersion, String nodeId,
                                                String publicReason, String detail, String nodeCatalogKey,
                                                String deploymentId, String workloadId) {
        UUID execution = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID invocation = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID attempt = UUID.fromString("00000000-0000-0000-0000-000000000003");
        return new ExecutionEvent(Long.MIN_VALUE, Instant.MAX,
                tenantId, requestId, engineId, graphVersion, execution, execution, invocation, attempt,
                ExecutionEventType.EDGE_TRAVERSED, nodeId, 0, false, detail, null, null,
                nodeCatalogKey, deploymentId, workloadId, 0, publicReason, null, null, edgeId);
    }

    private static DurableExecutionEvent durableEdgeEvent(String edgeId, String graphVersion, String nodeId) {
        UUID execution = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID invocation = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID attempt = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID causation = UUID.fromString("00000000-0000-0000-0000-000000000005");
        return new DurableExecutionEvent(UUID.fromString("00000000-0000-0000-0000-000000000004"),
                Long.MAX_VALUE, Long.MAX_VALUE, "tenant", ExecutionEventType.EDGE_TRAVERSED.name(),
                execution, execution, invocation, attempt, causation, "correlation", graphVersion,
                Instant.MAX, nodeId, edgeId);
    }

    private static int escapedLength(String value) {
        return EdgeTraversalWireBudget.jsonEscapedUtf8Length(value);
    }

    private static String escapedBytes(int count) {
        return Character.toString(1).repeat(count / 6) + "x".repeat(count % 6);
    }
}
