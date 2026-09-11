package ai.ravenroot.server.interaction;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import ai.ravenroot.core.humantask.HumanTaskResult;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionProtocolTest {
    @Test
    void parsesEmptyResolvePayloadAndComment() {
        UUID task = UUID.randomUUID();
        var parsed = assertInstanceOf(InteractionProtocol.Command.class, InteractionProtocol.parse(
                "{\"version\":1,\"type\":\"command\",\"messageId\":\"m1\","
                        + "\"command\":\"human-task.resolve\",\"taskId\":\"" + task + "\","
                        + "\"generation\":1,\"payloadBase64\":\"\",\"comment\":\"\"}", 524_288));

        assertArrayEquals(new byte[0], parsed.payload());
        assertEquals("", parsed.comment());
        assertEquals("application/octet-stream", parsed.contentType());
    }

    @Test
    void configuredAggregateAllowsPayloadLargerThanSixtyFourKibibytes() {
        byte[] payload = new byte[70_000];
        String encoded = Base64.getEncoder().encodeToString(payload);
        var parsed = assertInstanceOf(InteractionProtocol.Command.class, InteractionProtocol.parse(
                "{\"version\":1,\"type\":\"command\",\"messageId\":\"m1\","
                        + "\"command\":\"human-task.resolve\",\"taskId\":\"" + UUID.randomUUID() + "\","
                        + "\"generation\":1,\"payloadBase64\":\"" + encoded + "\"}", 524_288));
        assertEquals(payload.length, parsed.payload().length);
    }

    @Test
    void rejectsUnsupportedOrOversizedMessages() {
        assertThrows(InteractionProtocol.ProtocolFailure.class, () -> InteractionProtocol.parse(
                "{\"version\":1,\"type\":\"command\",\"messageId\":\"m\","
                        + "\"command\":\"execution.cancel\",\"taskId\":\"" + UUID.randomUUID() + "\","
                        + "\"generation\":1}", 1024));
        var oversized = assertThrows(InteractionProtocol.ProtocolFailure.class,
                () -> InteractionProtocol.parse("x".repeat(1025), 1024));
        assertEquals(1009, oversized.closeCode());
    }

    @Test
    void fragmentsOnUtf8Boundaries() {
        var fragments = InteractionWebSocketServer.utf8Fragments("ab🙂cd", 4);
        assertTrue(fragments.stream().allMatch(part -> part.getBytes(StandardCharsets.UTF_8).length <= 4));
        assertEquals("ab🙂cd", String.join("", fragments));
    }

    @Test
    void missingAndUnauthorizedTasksHaveIdenticalRedactedResults() {
        UUID task = UUID.randomUUID();
        var command = new InteractionProtocol.Command("correlation", "human-task.cancel", task, 3,
                null, null, null);

        String missing = InteractionProtocol.commandResult(command,
                new HumanTaskResult(HumanTaskResult.Code.NOT_FOUND, null, null));
        String unauthorized = InteractionProtocol.commandResult(command,
                new HumanTaskResult(HumanTaskResult.Code.UNAUTHORIZED, null, null));

        assertEquals(missing, unauthorized);
        assertEquals("{\"version\":1,\"type\":\"error\",\"inReplyTo\":\"correlation\","
                + "\"code\":\"RESOURCE_REFUSED\"}", missing);
    }

    @Test
    void reportsCanonicalRetentionFloor() {
        assertEquals("{\"version\":1,\"type\":\"stream.truncated\","
                        + "\"code\":\"STREAM_RETENTION_EXCEEDED\",\"retainedFrom\":8,\"resumeFrom\":7}",
                InteractionProtocol.streamTruncated(8));
    }
}
