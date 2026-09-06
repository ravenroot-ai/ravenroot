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

        assertEquals("id: 9\nevent: execution\ndata: {\"journalOffset\":9,\"streamSequence\":3,"
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"eventType\":\"EXECUTION_STARTED\","
                + "\"description\":\"Execution started.\",\"graphVersion\":\"graph-v1\","
                + "\"processInstanceId\":\"20000000-0000-0000-0000-000000000002\","
                + "\"traversalId\":\"30000000-0000-0000-0000-000000000003\",\"invocationId\":null,"
                + "\"attemptId\":null,\"causationId\":null,\"nodeId\":null,\"edgeId\":null,"
                + "\"handlerId\":null}\n\n", frame);
        assertEquals(frame.substring(frame.indexOf("data: ") + 6, frame.length() - 2),
                ExecutionEventWireJson.durable(event));
    }
}
