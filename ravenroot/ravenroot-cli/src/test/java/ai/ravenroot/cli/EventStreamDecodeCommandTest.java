package ai.ravenroot.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventStreamDecodeCommandTest {
    private static final String PROCESS = "10000000-0000-0000-0000-000000000001";
    private static final String TRAVERSAL = "20000000-0000-0000-0000-000000000002";
    private static final String EVENT_ID = "30000000-0000-0000-0000-000000000003";

    @Test
    void decodesVersionedRingAndDurableFramesWithExactLongIdsAndUnknownMembers() {
        String ring = versionedRing(Long.MIN_VALUE, ",\"future\":{\"recognized\":false}")
                .replace("2026-01-01T00:00:00Z", Instant.MIN.toString());
        String durable = versionedDurable(Long.MAX_VALUE, ",\"future\":[1,2]")
                .replace("2026-01-01T00:00:00Z", Instant.MAX.toString());

        Result result = run("id: " + Long.MIN_VALUE + "\nevent: execution\ndata: " + ring + "\n\n"
                + "id: " + Long.MAX_VALUE + "\nevent: execution\ndata: " + durable + "\n\n");

        assertEquals(0, result.code);
        assertTrue(result.output.contains("\"id\":\"-9223372036854775808\""), result.output);
        assertTrue(result.output.contains("\"sequence\":-9223372036854775808"), result.output);
        assertTrue(result.output.contains("\"id\":\"9223372036854775807\""), result.output);
        assertTrue(result.output.contains("\"journalOffset\":9223372036854775807"), result.output);
        assertTrue(result.output.contains(Instant.MIN.toString()), result.output);
        assertTrue(result.output.contains(Instant.MAX.toString()), result.output);
        assertTrue(result.output.contains("\"future\""), result.output);
    }

    @Test
    void decodesJsonDataNearTheSharedRawFrameCeiling() {
        String json = versionedRing(1, ",\"futureText\":\"" + "x".repeat(60_000) + "\"");
        String stream = "id: 1\nevent: execution\ndata: " + json + "\n\n";

        assertTrue(stream.getBytes(StandardCharsets.UTF_8).length
                < ExecutionEventStreamDecoder.MAX_FRAME_BYTES);
        Result result = run(stream);

        assertEquals(0, result.code);
        assertTrue(result.output.contains("\"futureText\""),
                () -> result.output.substring(0, Math.min(200, result.output.length())));
    }

    @Test
    void normalizesUnversionedTypeWithoutInventingSchemaOrCommonId() {
        String legacy = "{\"sequence\":9007199254740993,\"type\":\"NODE_ENTERED\""
                + ",\"extra\":\"line\\n\\\"quoted\\\"\"}";

        Result result = run("id: 9007199254740993\nevent: execution\ndata: " + legacy + "\n\n");

        assertEquals(0, result.code);
        assertTrue(result.output.contains("\"eventType\":\"NODE_ENTERED\""), result.output);
        assertTrue(result.output.contains("\"source\":\"RING\""), result.output);
        assertFalse(result.output.contains("schemaVersion"), result.output);
        assertEquals(1, occurrences(result.output, "\"id\":"), result.output);
        assertTrue(result.output.contains("line\\n\\\"quoted\\\""), result.output);
    }

    @Test
    void acceptsBomEveryLineEndingMultilineDataAndIgnoresTransportOnlyFields() {
        String json = versionedRing(7, "").replace(",\"eventType\"", ",\n\"eventType\"");
        String stream = "\ufeff: comment\rretry: 1\r\nunknown: value\nid: 7\r\nevent: execution\r\n"
                + "data: " + json.substring(0, json.indexOf('\n')) + "\r\n"
                + "data: " + json.substring(json.indexOf('\n') + 1) + "\r\n\r\n";

        Result result = runOneByteChunks(stream.getBytes(StandardCharsets.UTF_8));

        assertEquals(0, result.code);
        assertTrue(result.output.contains("\"event\":\"execution\""), result.output);
        assertTrue(result.errors.isEmpty(), result.errors);
    }

    @Test
    void rejectsKnownContradictionsAndUnsupportedVersionsWithoutInputDisclosure() {
        Result version = run("id: 1\nevent: execution\ndata: "
                + versionedRing(1, "").replace("\"schemaVersion\":1", "\"schemaVersion\":2") + "\n\n");
        Result opposite = run("id: 1\nevent: execution\ndata: "
                + versionedRing(1, ",\"journalOffset\":1") + "\n\n");
        String sentinel = "SECRET-SENTINEL";
        Result alias = run("id: 1\nevent: execution\ndata: "
                + versionedRing(1, "").replace("\"type\":\"NODE_ENTERED\"",
                        "\"type\":\"" + sentinel + "\"") + "\n\n");

        assertEquals("Error: UNSUPPORTED_EVENT_SCHEMA frame=1\n", version.errors);
        assertEquals("Error: INVALID_EXECUTION_EVENT frame=1\n", opposite.errors);
        assertEquals("Error: INVALID_EXECUTION_EVENT frame=1\n", alias.errors);
        assertFalse(alias.errors.contains(sentinel), alias.errors);
    }

    @Test
    void versionOneRequiresCanonicalEventTypeButAllowsEmptyEngineAndEqualDurableTypeAlias() {
        Result missingCanonical = run("id: 1\nevent: execution\ndata: "
                + versionedRing(1, "").replace(",\"eventType\":\"NODE_ENTERED\"", "") + "\n\n");
        Result emptyEngine = run("id: 1\nevent: execution\ndata: "
                + versionedRing(1, "").replace("\"engineId\":\"pekko\"", "\"engineId\":\"\"") + "\n\n");
        Result durableAlias = run("id: 2\nevent: execution\ndata: "
                + versionedDurable(2, ",\"type\":\"NODE_ENTERED\"") + "\n\n");

        assertEquals("Error: INVALID_EXECUTION_EVENT frame=1\n", missingCanonical.errors);
        assertEquals(0, emptyEngine.code);
        assertEquals(0, durableAlias.code);
    }

    @Test
    void versionOneRejectsInvalidKnownOptionalFieldsAndAbbreviatedUuids() {
        Result publicReason = run("id: 1\nevent: execution\ndata: "
                + versionedRing(1, "").replace("\"publicReason\":null",
                        "\"publicReason\":\"contains space\"") + "\n\n");
        Result flag = run("id: 1\nevent: execution\ndata: "
                + versionedRing(1, ",\"outputRedacted\":\"false\"") + "\n\n");
        Result common = run("id: 1\nevent: execution\ndata: "
                + versionedRing(1, ",\"invocationId\":\"1-1-1-1-1\"") + "\n\n");

        assertEquals("Error: INVALID_EXECUTION_EVENT frame=1\n", publicReason.errors);
        assertEquals("Error: INVALID_EXECUTION_EVENT frame=1\n", flag.errors);
        assertEquals("Error: INVALID_EXECUTION_EVENT frame=1\n", common.errors);
    }

    @Test
    void legacyTraversalAliasMustAgreeWhenBothNamesArePresent() {
        Result result = run("id: 1\nevent: execution\ndata: "
                + "{\"sequence\":1,\"type\":\"X\",\"traversalId\":\"" + TRAVERSAL
                + "\",\"executionId\":\"40000000-0000-0000-0000-000000000004\"}\n\n");

        assertEquals(1, result.code);
        assertEquals("Error: INVALID_EXECUTION_EVENT frame=1\n", result.errors);
    }

    @Test
    void rejectsAmbiguousLegacySourceAndNoncanonicalOrMismatchedIds() {
        Result ambiguous = run("id: 1\nevent: execution\ndata: "
                + "{\"sequence\":1,\"journalOffset\":1,\"type\":\"X\"}\n\n");
        Result leadingZero = run("id: 01\nevent: execution\ndata: "
                + "{\"sequence\":1,\"type\":\"X\"}\n\n");
        Result mismatch = run("id: 2\nevent: execution\ndata: "
                + "{\"sequence\":1,\"type\":\"X\"}\n\n");

        assertEquals(1, ambiguous.code);
        assertEquals(1, leadingZero.code);
        assertEquals(1, mismatch.code);
    }

    @Test
    void emitsKnownControlsThenTerminatesAndSkipsUnknownNamedEvents() {
        Result truncated = run("event: future-control\ndata: {\"secret\":true}\n\n"
                + "event: stream-truncated\ndata: "
                + "{\"code\":\"STREAM_RETENTION_EXCEEDED\",\"retainedFrom\":10,\"resumeFrom\":9}\n\n");
        Result overrun = run("id: 7\nevent: stream-overrun\ndata: "
                + "{\"code\":\"STREAM_CONSUMER_TOO_SLOW\",\"resumeAfter\":7}\n\n");

        assertEquals(3, truncated.code);
        assertEquals("Error: STREAM_RETENTION_EXCEEDED frame=2\n", truncated.errors);
        assertFalse(truncated.output.contains("future-control"), truncated.output);
        assertTrue(truncated.output.contains("\"event\":\"stream-truncated\""), truncated.output);
        assertEquals(3, overrun.code);
        assertTrue(overrun.output.contains("\"id\":\"7\""), overrun.output);
    }

    @Test
    void flushesEachCompleteRecordBeforeALaterMalformedFrameFails() {
        Result result = run("id: 1\nevent: execution\ndata: {\"sequence\":1,\"type\":\"X\"}\n\n"
                + "id: 2\nevent: execution\ndata: {bad}\n\n");

        assertEquals(1, result.code);
        assertEquals(1, result.output.lines().count());
        assertTrue(result.output.contains("\"id\":\"1\""), result.output);
        assertEquals("Error: INVALID_EVENT_JSON frame=2\n", result.errors);
    }

    @Test
    void requiresACompleteBlankLineBoundaryButAllowsBoundaryEof() {
        String complete = "id: 7\nevent: execution\ndata: " + versionedRing(7, "") + "\n\n";
        Result boundary = run(complete);
        Result incomplete = run(complete.substring(0, complete.length() - 1));

        assertEquals(0, boundary.code);
        assertEquals(1, incomplete.code);
        assertTrue(incomplete.output.isEmpty(), incomplete.output);
        assertEquals("Error: INCOMPLETE_EVENT_STREAM frame=1\n", incomplete.errors);
    }

    @Test
    void enforcesTheSharedRawFrameCeilingBeforeLineAllocation() {
        String accepted = ":" + "a".repeat(ExecutionEventStreamDecoder.MAX_FRAME_BYTES - 3) + "\n\n";
        String refused = ":" + "a".repeat(ExecutionEventStreamDecoder.MAX_FRAME_BYTES - 2) + "\n\n";

        assertEquals(0, run(accepted).code);
        Result oversized = run(refused);
        assertEquals(1, oversized.code);
        assertEquals("Error: FRAME_TOO_LARGE frame=1\n", oversized.errors);
    }

    @Test
    void rejectsMalformedUtf8DuplicateKeysAndExcessiveJsonDepthWithSafeCodes() {
        byte[] malformed = "event: execution\ndata: \"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"\n\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[malformed.length + 1 + suffix.length];
        System.arraycopy(malformed, 0, bytes, 0, malformed.length);
        bytes[malformed.length] = (byte) 0xff;
        System.arraycopy(suffix, 0, bytes, malformed.length + 1, suffix.length);
        Result utf8 = run(bytes);
        Result duplicate = run("id: 1\nevent: execution\ndata: "
                + "{\"sequence\":1,\"sequence\":1,\"type\":\"X\"}\n\n");
        Result depth = run("id: 1\nevent: execution\ndata: "
                + "[".repeat(33) + "null" + "]".repeat(33) + "\n\n");

        assertEquals("Error: INVALID_UTF8 frame=1\n", utf8.errors);
        assertEquals("Error: INVALID_EVENT_JSON frame=1\n", duplicate.errors);
        assertEquals("Error: INVALID_EVENT_JSON frame=1\n", depth.errors);
    }

    @Test
    void reportsIoAndArgumentFailuresWithFixedOutput() {
        InputStream failing = new InputStream() {
            @Override public int read() throws java.io.IOException { throw new java.io.IOException("SECRET"); }
            @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                throw new java.io.IOException("SECRET");
            }
        };
        Result io = run(failing);
        Result usage = runArgs(new String[] {"events"}, new ByteArrayInputStream(new byte[0]));

        assertEquals("Error: EVENT_STREAM_IO_FAILED\n", io.errors);
        assertEquals(2, usage.code);
        assertEquals("Usage: ravenroot events decode\n", usage.errors);
    }

    @Test
    @ResourceLock("SYSTEM_IN")
    void ravenrootCliDispatchesLocallyWithoutCallingItsBackend() {
        CliBackend backend = (CliBackend) Proxy.newProxyInstance(CliBackend.class.getClassLoader(),
                new Class<?>[] {CliBackend.class}, (proxy, method, args) -> {
                    throw new AssertionError("backend called: " + method.getName());
                });
        InputStream original = System.in;
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream(
                    ("id: 1\nevent: execution\ndata: {\"sequence\":1,\"type\":\"X\"}\n\n")
                            .getBytes(StandardCharsets.UTF_8)));
            assertEquals(0, new RavenrootCli(backend, new PrintStream(output), new PrintStream(errors))
                    .run("events", "decode"));
        } finally {
            System.setIn(original);
        }
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"eventType\":\"X\""));
        assertTrue(errors.toString(StandardCharsets.UTF_8).isEmpty());
    }

    @Test
    void mainLocalDispatchSeamRecognizesOnlyEventsAndNeedsNoEngine() {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        Integer code = RavenrootCliMain.runLocalEventCommand(new String[] {"events", "decode"},
                new ByteArrayInputStream(new byte[0]), new PrintStream(output), new PrintStream(errors));

        assertEquals(0, code);
        assertNull(RavenrootCliMain.runLocalEventCommand(new String[] {"status"},
                new ByteArrayInputStream(new byte[0]), new PrintStream(output), new PrintStream(errors)));
    }

    private static String versionedRing(long id, String extra) {
        return "{\"schemaVersion\":1,\"source\":\"RING\",\"id\":\"" + id
                + "\",\"eventType\":\"NODE_ENTERED\",\"occurredAt\":\"2026-01-01T00:00:00Z\""
                + ",\"processInstanceId\":\"" + PROCESS + "\",\"traversalId\":\"" + TRAVERSAL
                + "\",\"sequence\":" + id + ",\"engineId\":\"pekko\",\"executionId\":\""
                + TRAVERSAL + "\",\"type\":\"NODE_ENTERED\",\"activeInstances\":0"
                + ",\"inFlightArrivals\":0,\"fallback\":false,\"publicReason\":null,\"message\":null"
                + ",\"messageRedacted\":false,\"messageTruncated\":false,\"processingDuration\":null"
                + extra + "}";
    }

    private static String versionedDurable(long id, String extra) {
        return "{\"schemaVersion\":1,\"source\":\"DURABLE\",\"id\":\"" + id
                + "\",\"eventType\":\"NODE_ENTERED\",\"occurredAt\":\"2026-01-01T00:00:00Z\""
                + ",\"processInstanceId\":\"" + PROCESS + "\",\"traversalId\":\"" + TRAVERSAL
                + "\",\"eventId\":\"" + EVENT_ID + "\",\"journalOffset\":" + id
                + ",\"streamSequence\":1,\"causationId\":null,\"handlerId\":null" + extra + "}";
    }

    private static Result run(String input) {
        return run(input.getBytes(StandardCharsets.UTF_8));
    }

    private static Result run(byte[] input) {
        return run(new ByteArrayInputStream(input));
    }

    private static Result runOneByteChunks(byte[] input) {
        return run(new ByteArrayInputStream(input) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(length, 1));
            }
        });
    }

    private static Result run(InputStream input) {
        return runArgs(new String[] {"events", "decode"}, input);
    }

    private static Result runArgs(String[] args, InputStream input) {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        int code = EventStreamDecodeCommand.run(args, input, new PrintStream(output), new PrintStream(errors));
        return new Result(code, output.toString(StandardCharsets.UTF_8), errors.toString(StandardCharsets.UTF_8));
    }

    private static int occurrences(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private record Result(int code, String output, String errors) {
    }
}
