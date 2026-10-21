package ai.ravenroot.server;

import ai.ravenroot.api.application.DurableExecutionEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                + "\"contributionKind\":null,\"roundId\":null,\"publicReference\":null,\"outcome\":null}\n\n", frame);
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
        assertEquals("part-2", fields.get("contributionId")); assertEquals("silver", fields.get("contributionKind"));
        assertEquals("round-1", fields.get("roundId")); assertEquals("file.csv", fields.get("publicReference"));
        assertEquals("correlated-ready", fields.get("outcome"));
        var recent = (java.util.Map<?, ?>) ai.ravenroot.api.payload.PayloadJson.read(RavenrootServer.durableRecentEventJson(event)
                .getBytes(StandardCharsets.UTF_8), ai.ravenroot.api.payload.PayloadLimits.DEFAULTS).toJava();
        assertEquals(fields.get("applicationCorrelationId"), recent.get("applicationCorrelationId"));
        assertEquals(fields.get("outcome"), recent.get("outcome"));
    }
}
