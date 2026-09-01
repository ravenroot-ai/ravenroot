package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The worker's failure path writes ONE envelope, and it is readable.
 *
 * <p><b>The defect.</b> {@code GraalVmWorkerMain} wrote the success envelope straight to stdout.
 * Serialising a value past the protocol's string limit fails PARTWAY -- after the magic, the success
 * flag, the map header, the key and the string tag -- so a half-written success envelope was already
 * on the stream when the {@code catch} block appended a complete failure envelope behind it. The
 * reader consumed the first, then read the second's magic {@code 0x52525031} as the pending string's
 * length and reported {@code Invalid string length: 1381126193}. The correct refusal, {@code Value
 * exceeds worker protocol limit}, sat in those same 78 bytes in clear text and no caller could reach
 * it.
 *
 * <p><b>Why this suite is not in {@link PythonSandboxLimitBreachTest}.</b> That suite's subject is
 * what a PYTHON artifact can reach, while the existing JavaScript behavior had to remain unchanged.
 * The defect was never Python's: it is the worker's failure path, which both
 * languages share. {@link #anOversizedResultIsRefusedIdenticallyInBothLanguages} asserts that
 * directly, and by byte equality rather than by two similar-looking messages -- if the two languages
 * ever diverge here, the assertion that catches it must be one that compares them.
 *
 * <p><b>Measured against the previous worker.</b> Three of the four tests failed -- each on its own property rather than all on
 * one shared symptom. {@link #anOversizedResultIsRefusedIdenticallyInBothLanguages} counted 2
 * envelopes where it requires 1;
 * {@link #aResultBeyondTheResponseCeilingIsRefusedRatherThanExhaustingTheWorkerHeap} got
 * {@code SANDBOX_PROTOCOL_FAILURE}; {@link #writeSuccessLeavesTheStreamCleanWhenSerialisationFails}
 * found 19 bytes -- {@code 5252503101060000000100000004626c6f6205}, exactly the magic, flag, map
 * header, key and dangling string tag described above -- on a stream it requires to be empty.
 *
 * <p>{@link #theGuardedStreamReportsWhetherAnyByteHasAlreadyEscaped} is the exception, and is said
 * so rather than folded into that claim: it exercises a type that did not exist before the fix, so
 * against the old worker it did not fail, it did not compile. It is a regression guard on the new
 * mechanism, not evidence about the old defect.
 */
class WorkerFailureEnvelopeTest {
    /**
     * The protocol's envelope magic, {@code "RRP1"}. Duplicated from {@code ProgramWireProtocol},
     * deliberately: these tests count how many envelopes are on a stream, and a count that read its
     * needle from the same private constant the writer used would still pass if that constant
     * changed underneath it.
     */
    private static final byte[] MAGIC = {0x52, 0x52, 0x50, 0x31};

    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

    /**
     * The envelope, reason and cross-language requirements are exercised together on the real
     * worker in a real child JVM, for both languages.
     *
     * <p>The byte-equality assertion is the load-bearing one. Two separate assertions that each
     * message "contains the reason" would pass while the languages produced materially different
     * envelopes; this path specifically must not know which language ran, and
     * only comparing the bytes tests that claim.
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void anOversizedResultIsRefusedIdenticallyInBothLanguages() throws Exception {
        byte[] python = capture("python", "def handler(request):\n"
                + "    return {'blob': 'x' * (8 * 1024 * 1024)}\n"
                + "handler");
        byte[] javascript = capture("javascript",
                "function (request) { return { blob: 'x'.repeat(8 * 1024 * 1024) }; }");

        assertArrayEquals(python, javascript,
                "the failure path does not know which language ran, so both must produce the same "
                        + "bytes. python=" + HexFormat.of().formatHex(python)
                        + " javascript=" + HexFormat.of().formatHex(javascript));

        // One envelope on the stream, not two. Before the fix this was 2 -- a partial success
        // envelope with a complete failure envelope appended to it.
        assertEquals(1, envelopeCount(python),
                "a partial envelope must never remain on the stream when the failure envelope is "
                        + "written, was: " + HexFormat.of().formatHex(python));

        // The reason, not a format error produced by misreading the second envelope's magic.
        for (byte[] bytes : new byte[][]{python, javascript}) {
            IOException refusal = assertThrows(ProgramWireProtocol.ProgramWorkerException.class,
                    () -> ProgramWireProtocol.readResponse(new ByteArrayInputStream(bytes)));
            assertTrue(refusal.getMessage().contains("Value exceeds worker protocol limit"),
                    "the caller must receive the refusal the worker actually generated, was: "
                            + refusal.getMessage());
            assertFalse(refusal.getMessage().contains("Invalid string length"),
                    "the garbled-envelope symptom must be gone, was: " + refusal.getMessage());
        }
    }

    /**
     * The risk buffering introduces, measured rather than assumed -- and the reason
     * {@code writeSuccess}'s buffer is BOUNDED.
     *
     * <p>Holding a whole response in memory before writing it costs memory the worker does not have:
     * it runs under {@code -Xmx} equal to the policy's {@code memoryMiB}, 64 here. An UNBOUNDED
     * buffer was measured turning a legal 10 MiB result at {@code -Xmx64m} into
     * {@code OutOfMemoryError: Java heap space} -- swapping one wrong answer for another, which
     * would have made the fix a relocation of the defect rather than a repair. This test is the
     * guard on that: it asserts not merely that the oversized result fails, but that it fails for
     * the declared reason and NOT by exhausting the heap.
     *
     * <p>The result here is legal in every per-field respect -- ten strings of exactly
     * {@code MAX_STRING_BYTES}, well inside {@code MAX_COLLECTION_SIZE} -- so nothing but the
     * response ceiling can refuse it. Measured before the fix, through this same supervisor: the
     * caller got {@code SANDBOX_PROTOCOL_FAILURE}, because the supervisor truncated the child's
     * output at {@code maxOutputBytes} and the frame stopped parsing. That is the same
     * illegibility on the neighbouring path.
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void aResultBeyondTheResponseCeilingIsRefusedRatherThanExhaustingTheWorkerHeap() {
        var supervisor = new PythonLimitSupervisor();
        var runtime = new GraalVmProgramRuntime(supervisor, policy());
        GeneratedArtifact artifact = artifact("python", "def handler(request):\n"
                + "    return {str(i): 'x' * (1024 * 1024) for i in range(10)}\n"
                + "handler");

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> runtime.execute(TestAdmission.of(artifact), request()).toCompletableFuture().get());

        String message = error.getCause().getMessage();
        assertTrue(message.contains("Response exceeds worker protocol limit"),
                "a response past the protocol ceiling must be refused by name, was: " + message);
        assertFalse(message.contains("OutOfMemory"),
                "the bounded buffer exists precisely so that a large result cannot be turned into a "
                        + "heap exhaustion by the act of buffering it, was: " + message);
    }

    /**
     * The mechanism itself, without a child JVM: the property is that a failed serialisation leaves
     * NOTHING behind, which is what makes the failure path's stream clean by construction rather
     * than by the caller remembering to check.
     *
     * <p>Before the fix this stream held 15 bytes at this point -- magic, success flag, map header,
     * the key {@code blob} and the string tag.
     */
    @Test
    void writeSuccessLeavesTheStreamCleanWhenSerialisationFails() throws Exception {
        var stream = new ByteArrayOutputStream();
        var oversized = new LinkedHashMap<String, Object>();
        oversized.put("blob", "x".repeat(2 * 1024 * 1024));

        IOException refusal = assertThrows(IOException.class,
                () -> ProgramWireProtocol.writeSuccess(stream, oversized));
        assertEquals("Value exceeds worker protocol limit", refusal.getMessage());
        assertEquals(0, stream.size(),
                "a success envelope that could not be completed must not have reached the stream, "
                        + "was: " + HexFormat.of().formatHex(stream.toByteArray()));

        // And the failure envelope written onto that same stream is therefore the only one on it.
        ProgramWireProtocol.writeFailure(stream, refusal);
        assertEquals(1, envelopeCount(stream.toByteArray()));
        IOException delivered = assertThrows(ProgramWireProtocol.ProgramWorkerException.class,
                () -> ProgramWireProtocol.readResponse(new ByteArrayInputStream(stream.toByteArray())));
        assertTrue(delivered.getMessage().contains("Value exceeds worker protocol limit"),
                "was: " + delivered.getMessage());
    }

    /**
     * The guard that keeps the defect closed rather than merely fixing it once.
     *
     * <p>{@code GraalVmWorkerMain} asks this stream whether anything has already been written before
     * it writes a failure envelope. Nothing on the success path can dirty it today -- that is what
     * the buffer guarantees -- but "today" is the whole of that guarantee, and the failure path's
     * correctness should not rest on every future writer being careful.
     */
    @Test
    void theGuardedStreamReportsWhetherAnyByteHasAlreadyEscaped() throws Exception {
        var sink = new ByteArrayOutputStream();
        var guarded = new ProgramWireProtocol.GuardedOutput(sink);
        assertFalse(guarded.dirty(), "nothing has been written yet");

        // A zero-length bulk write is not a write: reporting it as one would make the worker exit 70
        // and emit no envelope at all for a run that had in fact produced nothing.
        guarded.write(new byte[0], 0, 0);
        assertFalse(guarded.dirty(), "a zero-length write puts no bytes on the stream");

        guarded.write('R');
        assertTrue(guarded.dirty(), "one byte on the stream is enough to make appending unsafe");
        assertEquals(1, sink.size());
    }

    /**
     * Occurrences of the envelope magic anywhere in {@code bytes} -- <b>payload included</b>.
     *
     * <p>That equals the number of envelopes written only because every stream this suite counts is
     * a refusal, whose payload is an exception class name and a message and cannot contain the
     * marker. It is not a general envelope count and must not be reused as one: a successful
     * envelope carrying a value with {@code RRP1} in a string would inflate it. Stated here rather
     * than left for a future reader to discover from a puzzling failure.
     */
    private static int envelopeCount(byte[] bytes) {
        int count = 0;
        for (int index = 0; index + MAGIC.length <= bytes.length; index++) {
            boolean match = true;
            for (int offset = 0; offset < MAGIC.length && match; offset++) {
                match = bytes[index + offset] == MAGIC[offset];
            }
            if (match) count++;
        }
        return count;
    }

    /** Runs the real worker and returns exactly what it wrote to stdout. */
    private static byte[] capture(String language, String source) throws Exception {
        return RealWorkerRun.capture(ProgramWireProtocol.Mode.EXECUTE, artifact(language, source), request());
    }

    private static ProgramRequest request() {
        return new ProgramRequest(UUID.randomUUID(), "node-1", Map.of("name", "Ravenroot"), Map.of());
    }

    private static SandboxPolicy policy() {
        return new SandboxPolicy(Duration.ofSeconds(60), 60_000, 64, 32, 256, 64,
                2 * 1024 * 1024, Path.of(System.getProperty("java.class.path")), "worker-test-id",
                JAVA, "jre-test-id");
    }

    private static GeneratedArtifact artifact(String language, String source) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
            Instant now = Instant.now();
            return new GeneratedArtifact("envelope-artifact", language, hash, source,
                    ArtifactState.ACTIVE, 1, now, now, Map.of());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
