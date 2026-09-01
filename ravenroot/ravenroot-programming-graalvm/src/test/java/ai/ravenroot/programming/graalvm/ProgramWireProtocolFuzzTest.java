package ai.ravenroot.programming.graalvm;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * QA-07: {@link ProgramWireProtocol}, one of two GraalVM wire-protocol fuzz targets. Tagged
 * {@code fuzz}, with the same conventions as
 * {@code SecureGraphMlParserFuzzTest}: excluded from the default {@code mvn test} run, included
 * only under {@code -Pfuzz}.
 *
 * <h2>Targeted, not broad, from the start</h2>
 * <p>A broad whole-document mutation property is probabilistic at its shipped budget (green, green,
 * fail across three fresh runs, the one failure at try 432 of 500):
 * a whole-input mutation lands on a specific few-byte span too rarely for reliable detection at any
 * budget this suite is willing to pay every run. Every property below is therefore narrow by
 * design — it generates directly the field the invariant is about rather than mutating a whole valid
 * message and hoping.
 *
 * <h2>Two kinds of finding here, and they carry different weight</h2>
 * <p>{@link ProgramWireProtocol} and {@link SandboxSupervisorProtocol} were both correct from their
 * first commit — there is no historical defect to reintroduce, unlike GraphML's FIX-09 path.
 * Two of the properties below instead validate a <strong>genuinely discovered</strong> defect (the
 * {@code Mode.valueOf}/{@code UUID.fromString} unchecked-exception escape fixed
 * in {@code ProgramWireProtocol.readRequest} — see that method's own comment), which is stronger
 * evidence than a mutation could be, because it is real. The remaining targeted property validates
 * a <strong>synthesized</strong> mutation and says, for the record, why a plausible refactor could
 * introduce it — weaker evidence by construction, which is why the reasoning is written down rather
 * than only the pass/fail result.
 */
class ProgramWireProtocolFuzzTest {

    private static final int MAGIC = 0x52525031;

    // ------------------------------------------------------------------------------------------
    // Property A/B — a request whose mode or execution-id field is not the literal text this
    // protocol's own writer would have produced must still fail as a typed IOException, never as
    // the unchecked IllegalArgumentException Mode.valueOf/UUID.fromString throw natively.
    //
    // This is a real, fuzz-discovered defect fixed in
    // ProgramWireProtocol#readRequest (see that method's comment for why a plausible operational
    // event -- not a hostile actor -- can reach it: a partial write, a supervisor/worker version
    // skew). Red control against the pre-fix method: temporarily
    // reverted readRequest to call Mode.valueOf/UUID.fromString directly (no try/catch), reran both
    // properties, both failed immediately with IllegalArgumentException escaping uncaught. Restored
    // the fix; both pass again. Not a "try 1" artifact -- .jqwik-database was cleared before the
    // reversion run to exclude a persisted jqwik sample as the explanation.
    // ------------------------------------------------------------------------------------------

    @Tag("fuzz")
    @Property(tries = 100)
    void garbageModeStringsAreRejectedAsTypedFailuresNotUncheckedCrashes(@ForAll("garbageModeNames") String mode) {
        byte[] wire = requestBytesWithRawMode(mode);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            IOException rejection = assertThrows(IOException.class,
                    () -> ProgramWireProtocol.readRequest(new ByteArrayInputStream(wire)));
            assertNotNull(rejection.getMessage());
        });
    }

    @Tag("fuzz")
    @Property(tries = 100)
    void garbageExecutionIdStringsAreRejectedAsTypedFailuresNotUncheckedCrashes(
            @ForAll("garbageUuidStrings") String executionId) {
        byte[] wire = requestBytesWithRawExecutionId(executionId);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            IOException rejection = assertThrows(IOException.class,
                    () -> ProgramWireProtocol.readRequest(new ByteArrayInputStream(wire)));
            assertNotNull(rejection.getMessage());
        });
    }

    @Provide
    Arbitrary<String> garbageModeNames() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12)
                .filter(value -> !value.equals("validate") && !value.equals("test") && !value.equals("execute"));
    }

    @Provide
    Arbitrary<String> garbageUuidStrings() {
        // Deliberately NOT built from UUID.randomUUID() (which is always well-formed): garbage
        // characters in roughly the right shape, so most candidates get well past readString and
        // into UUID.fromString itself rather than being rejected earlier for a different reason.
        return Arbitraries.strings().withCharRange('g', 'z').withChars('-').ofMinLength(8).ofMaxLength(36);
    }

    // ------------------------------------------------------------------------------------------
    // Property C — a declared Map size over MAX_COLLECTION_SIZE is rejected by readCollectionSize
    // before a single entry is read, not merely eventually by running out of stream.
    //
    // Why the assertion targets the exact message rather than "some IOException": without this,
    // the property cannot tell "the bound fired" from "the stream happened to run out anyway",
    // because a declared-but-absent size, even with NO bound check at all, still eventually hits
    // DataInputStream.readInt() with zero bytes left and throws EOFException (itself an
    // IOException) once it tries to read the first entry's key. Only the message
    // ("Invalid collection size: ...") distinguishes the two.
    //
    // Red control (synthesized): temporarily made readMap ignore the bound by inlining
    // `int size = input.readInt();` in place of the `readCollectionSize(input)` call (so only the
    // negative-size guard was gone, not readList's). Why a plausible refactor could introduce this:
    // readMap and readList are two independent call sites sharing one bound function; a future
    // change that gives maps and lists different size ceilings (a real, plausible feature -- e.g.
    // objects bounded tighter than arrays) would naturally split readCollectionSize into two
    // call-site-specific checks, and dropping one while updating the other is exactly the kind of
    // sibling-drift risk already seen in the GraphML target. With the mutation in
    // place, this property failed on try 1 (size = 10001, the minimum out-of-bound value jqwik's
    // edge cases include) with an EOFException carrying no "Invalid collection size" text --
    // confirming the assertion actually discriminates, not just that something threw. Reverted
    // immediately after confirming the failure; passes again against the restored source.
    // ------------------------------------------------------------------------------------------

    @Tag("fuzz")
    @Property(tries = 100)
    void oversizedMapSizeDeclarationsAreRejectedBeforeAnyEntryIsRead(@ForAll("oversizedCollectionSizes") int size) {
        byte[] wire = successResponseBytesWithRawMapSize(size);
        var rejection = assertThrows(IOException.class,
                () -> ProgramWireProtocol.readResponse(new ByteArrayInputStream(wire)));
        assertTrue(rejection.getMessage() != null && rejection.getMessage().contains("Invalid collection size"),
                "expected the collection-size bound to reject this, got: " + rejection);
    }

    @Provide
    Arbitrary<Integer> oversizedCollectionSizes() {
        return Arbitraries.integers().between(10_001, Integer.MAX_VALUE);
    }

    // ------------------------------------------------------------------------------------------
    // Property D — arbitrary bytes fed to readResponse never escape as an undeclared crash and
    // never hang. No specific historical or synthesized defect targets this property: it is the
    // literal "no crash, hang, or OOM" safety property for unstructured input, the same role as the
    // broad safety property in the GraphML target.
    // ------------------------------------------------------------------------------------------

    @Tag("fuzz")
    @Property(tries = 200)
    void arbitraryByteSequencesNeverEscapeReadResponseAsUnclassifiedFailuresOrHangs(
            @ForAll("arbitraryPayloads") byte[] payload) {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            try {
                // Accepted: fine, as long as nothing crashed getting here. No assertion on the
                // value itself -- this property is about crash/hang classification, not semantics.
                ProgramWireProtocol.readResponse(new ByteArrayInputStream(payload));
            } catch (IOException expected) {
                // The only required outcome for garbage input: a typed, declared IOException.
                // Not asserting a non-null message -- DataInputStream's own EOFException() has a
                // null message by JDK convention for a stream that simply ran out, which is not a
                // defect in the code under test.
            } catch (RuntimeException leaked) {
                fail("readResponse leaked " + leaked.getClass().getName() + " for " + payload.length
                        + " arbitrary bytes: " + leaked.getMessage());
            }
        });
    }

    @Provide
    Arbitrary<byte[]> arbitraryPayloads() {
        return Arbitraries.bytes().array(byte[].class).ofMaxSize(1024);
    }

    // ------------------------------------------------------------------------------------------ wire builders

    private static byte[] requestBytesWithRawMode(String mode) {
        try {
            var out = new ByteArrayOutputStream();
            var data = new DataOutputStream(out);
            data.writeInt(MAGIC);
            writeString(data, mode);
            data.flush();
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static byte[] requestBytesWithRawExecutionId(String executionId) {
        try {
            var out = new ByteArrayOutputStream();
            var data = new DataOutputStream(out);
            data.writeInt(MAGIC);
            writeString(data, "EXECUTE");
            writeString(data, "artifact-1");
            writeString(data, "javascript");
            writeString(data, "0".repeat(64));
            writeString(data, "");
            data.writeBoolean(true);
            writeString(data, executionId);
            data.flush();
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** A writeSuccess()-shaped stream whose value is a Map tag with an attacker-declared size. */
    private static byte[] successResponseBytesWithRawMapSize(int declaredSize) {
        try {
            var out = new ByteArrayOutputStream();
            var data = new DataOutputStream(out);
            data.writeInt(MAGIC);
            data.writeBoolean(true);
            data.writeByte(6); // Map tag, see ProgramWireProtocol#writeValue
            data.writeInt(declaredSize);
            data.flush();
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
