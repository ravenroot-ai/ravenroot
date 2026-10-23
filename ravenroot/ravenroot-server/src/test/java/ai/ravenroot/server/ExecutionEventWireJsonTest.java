package ai.ravenroot.server;

import ai.ravenroot.api.application.DurableExecutionEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionEventWireJsonTest {
    @Test
    void extractedSerializerPreservesSseDataExactly() {
        var event = new DurableExecutionEvent(UUID.fromString("10000000-0000-0000-0000-000000000001"), 9, 3,
                "tenant", "EXECUTION_STARTED", UUID.fromString("20000000-0000-0000-0000-000000000002"),
                UUID.fromString("30000000-0000-0000-0000-000000000003"), null, null, null,
                "correlation", "graph-v1", Instant.parse("2026-01-01T00:00:00Z"), null, null, null);
        String frame = new String(RavenrootServer.durableExecutionEventFrame(event), StandardCharsets.UTF_8);

        // The expected frame carries the versioned envelope this stream gained while this branch
        // was open: schemaVersion, source, id and eventId ahead of the fields already there. Every
        // legacy field is still present and unchanged, which is what this test is about - it asserts
        // that extracting the serializer preserved the data, not that the format is frozen.
        assertEquals("id: 9\nevent: execution\ndata: {\"schemaVersion\":1,\"source\":\"DURABLE\","
                + "\"id\":\"9\",\"eventId\":\"10000000-0000-0000-0000-000000000001\","
                + "\"journalOffset\":9,\"streamSequence\":3,"
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"eventType\":\"EXECUTION_STARTED\","
                + "\"description\":\"Execution started.\",\"graphVersion\":\"graph-v1\","
                + "\"processInstanceId\":\"20000000-0000-0000-0000-000000000002\","
                + "\"traversalId\":\"30000000-0000-0000-0000-000000000003\",\"invocationId\":null,"
                + "\"attemptId\":null,\"causationId\":null,\"nodeId\":null,\"edgeId\":null,"
                + "\"handlerId\":null,\"applicationCorrelationId\":null,\"contributionId\":null,"
                + "\"contributionKind\":null,\"roundId\":null,\"publicReference\":null,"
                + "\"applicationCorrelationAvailability\":\"NOT_RECORDED\",\"outcome\":null}\n\n", frame);
        assertEquals(frame.substring(frame.indexOf("data: ") + 6, frame.length() - 2),
                ExecutionEventWireJson.durable(event));
    }
    @Test void durableWireExposesTypedBusinessFactsAndSafeOutcomeWithoutPayloadContent() {
        var context = new ai.ravenroot.api.correlation.ApplicationCorrelationContext("business-42",
                java.util.Optional.of("part-2"), java.util.Optional.of("silver"), "round-1", java.util.Optional.of("file.csv"));
        var event = new DurableExecutionEvent(UUID.randomUUID(), 9, 3, "tenant", "NODE_COMPLETED", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "transport-request", "graph-v1",
                Instant.parse("2026-01-01T00:00:00Z"), "join", null, null, context, "correlated-ready");
        var body = ai.ravenroot.api.payload.PayloadJson.read(ExecutionEventWireJson.durable(event)
                .getBytes(StandardCharsets.UTF_8), ai.ravenroot.api.payload.PayloadLimits.DEFAULTS).toJava();
        var fields = (java.util.Map<?, ?>) body;
        assertEquals("business-42", fields.get("applicationCorrelationId"));
        assertEquals("AVAILABLE", fields.get("applicationCorrelationAvailability"));
        assertEquals("part-2", fields.get("contributionId")); assertEquals("silver", fields.get("contributionKind"));
        assertEquals("round-1", fields.get("roundId")); assertEquals("file.csv", fields.get("publicReference"));
        assertEquals("correlated-ready", fields.get("outcome"));
        var recent = (java.util.Map<?, ?>) ai.ravenroot.api.payload.PayloadJson.read(RavenrootServer.durableRecentEventJson(event)
                .getBytes(StandardCharsets.UTF_8), ai.ravenroot.api.payload.PayloadLimits.DEFAULTS).toJava();
        assertEquals(fields.get("applicationCorrelationId"), recent.get("applicationCorrelationId"));
        assertEquals(fields.get("outcome"), recent.get("outcome"));
    }

    @Test void fullCorrelationCannotOverflowTheStableEdgeFrameOrChangeItsIdentity() {
        for (int size : java.util.List.of(256, 4096)) {
            String value = "界".repeat(size);
            var context = new ai.ravenroot.api.correlation.ApplicationCorrelationContext(value,
                    java.util.Optional.of(value), java.util.Optional.of(value), value, java.util.Optional.of(value));
            String edge = Character.toString(1).repeat(ai.ravenroot.api.application.StableEdgeId.MAX_UTF8_BYTES);
            String engine = "x".repeat(ai.ravenroot.api.application.EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES
                    - "tenant".length() - "request".length() - "graph".length() - "source".length());
            var original = liveEvent(engine, ai.ravenroot.api.application.ExecutionEventType.EDGE_TRAVERSED, edge, context);
            String graph = "x".repeat(ai.ravenroot.api.application.EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES
                    - "EDGE_TRAVERSED".length() - "source".length());
            var durable = new DurableExecutionEvent(UUID.randomUUID(), Long.MAX_VALUE, Long.MAX_VALUE, "tenant",
                    "EDGE_TRAVERSED", original.processInstanceId(), original.traversalId(), original.invocationId(),
                    original.attemptId(), null, "request", graph, Instant.MAX, "source", edge, null, context, null);
            byte[] liveFrame = RavenrootServer.executionEventFrame(original);
            byte[] durableFrame = RavenrootServer.durableExecutionEventFrame(durable);
            assertTrue(liveFrame.length < ai.ravenroot.api.application.StableEdgeId.SSE_FRAME_MAX_BYTES);
            assertTrue(durableFrame.length < ai.ravenroot.api.application.StableEdgeId.SSE_FRAME_MAX_BYTES);
            for (var json : java.util.List.of(ExecutionEventWireJson.live(original), ExecutionEventWireJson.durable(durable))) {
                var fields = fields(json);
                assertEquals("NOT_PROJECTED", fields.get("applicationCorrelationAvailability"));
                assertEquals(edge, fields.get("edgeId"));
                assertEquals(original.processInstanceId().toString(), fields.get("processInstanceId"));
                assertEquals(original.traversalId().toString(), fields.get("traversalId"));
                for (String name : java.util.List.of("applicationCorrelationId", "contributionId", "contributionKind", "roundId", "publicReference"))
                    assertNull(fields.get(name), name);
            }
            assertEquals(context, original.applicationCorrelation());
            assertEquals(context, durable.applicationCorrelation());
            assertEquals(fields(ExecutionEventWireJson.live(original)).get("applicationCorrelationAvailability"),
                    fields(RavenrootServer.executionEventJson(original)).get("applicationCorrelationAvailability"));
        }
    }

    @Test void correlationProjectionUsesTheActualCompleteFrameBoundaryWithoutTruncation() {
        var context = largeContext();
        var first = liveEvent("x", ai.ravenroot.api.application.ExecutionEventType.EXECUTION_STARTED, null, context);
        int padding = ai.ravenroot.api.application.StableEdgeId.SSE_FRAME_MAX_BYTES - 1
                - RavenrootServer.executionEventFrame(first).length;
        assertTrue(padding > 0);
        var atBoundary = liveEvent("x".repeat(1 + padding), first.type(), null, context);
        assertEquals(ai.ravenroot.api.application.StableEdgeId.SSE_FRAME_MAX_BYTES - 1,
                RavenrootServer.executionEventFrame(atBoundary).length);
        var full = fields(ExecutionEventWireJson.live(atBoundary));
        assertEquals("AVAILABLE", full.get("applicationCorrelationAvailability"));
        assertEquals(context.applicationCorrelationId(), full.get("applicationCorrelationId"));
        assertEquals(context.publicReference().orElseThrow(), full.get("publicReference"));
        var oneMore = liveEvent("x".repeat(2 + padding), first.type(), null, context);
        var omitted = fields(ExecutionEventWireJson.live(oneMore));
        assertEquals("NOT_PROJECTED", omitted.get("applicationCorrelationAvailability"));
        assertNull(omitted.get("applicationCorrelationId"));
        assertTrue(RavenrootServer.executionEventFrame(oneMore).length
                < ai.ravenroot.api.application.StableEdgeId.SSE_FRAME_MAX_BYTES);
        assertEquals(context, oneMore.applicationCorrelation());
    }

    @Test void independentlyOversizedEventFieldsRefuseInsteadOfPublishingAnInvalidFrame() {
        for (var context : java.util.Arrays.asList(null, largeContext())) {
            var live = liveEvent("x".repeat(ai.ravenroot.api.application.StableEdgeId.SSE_FRAME_MAX_BYTES),
                    ai.ravenroot.api.application.ExecutionEventType.EXECUTION_STARTED, null, context);
            assertThrows(IllegalArgumentException.class, () -> RavenrootServer.executionEventFrame(live));
            var durable = new DurableExecutionEvent(UUID.randomUUID(), Long.MAX_VALUE, Long.MAX_VALUE, "tenant",
                    "EXECUTION_STARTED", live.processInstanceId(), live.traversalId(), null, null, null, "request",
                    "x".repeat(ai.ravenroot.api.application.StableEdgeId.SSE_FRAME_MAX_BYTES), Instant.MAX,
                    null, null, null, context, null);
            assertThrows(IllegalArgumentException.class, () -> RavenrootServer.durableExecutionEventFrame(durable));
            assertEquals(context, live.applicationCorrelation());
            assertEquals(context, durable.applicationCorrelation());
        }
    }

    private static ai.ravenroot.api.correlation.ApplicationCorrelationContext largeContext() {
        String value = "界".repeat(4096);
        return new ai.ravenroot.api.correlation.ApplicationCorrelationContext(value,
                java.util.Optional.of(value), java.util.Optional.of(value), value, java.util.Optional.of(value));
    }

    private static ai.ravenroot.api.application.ExecutionEvent liveEvent(String engine,
            ai.ravenroot.api.application.ExecutionEventType type, String edge,
            ai.ravenroot.api.correlation.ApplicationCorrelationContext context) {
        UUID process = UUID.fromString("20000000-0000-0000-0000-000000000002");
        UUID traversal = UUID.fromString("30000000-0000-0000-0000-000000000003");
        return new ai.ravenroot.api.application.ExecutionEvent(Long.MAX_VALUE, Instant.MAX, "tenant", "request",
                engine, "graph", process, traversal, null, null, type,
                edge == null ? null : "source", 0, false, null, null, null, null, null, null, 0,
                null, null, null, edge, 0, 0, context);
    }

    private static java.util.Map<?, ?> fields(String json) {
        return (java.util.Map<?, ?>) ai.ravenroot.api.payload.PayloadJson.read(json.getBytes(StandardCharsets.UTF_8),
                ai.ravenroot.api.payload.PayloadLimits.DEFAULTS).toJava();
    }
}
