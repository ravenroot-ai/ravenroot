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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;

/**
 * QA-07: {@link SandboxSupervisorProtocol}, the second GraalVM
 * wire-protocol fuzz target. See {@link ProgramWireProtocolFuzzTest}'s own class Javadoc for the
 * shared conventions (tag, budget philosophy, both-directions proof) — not repeated here.
 *
 * <p>This class's own Javadoc frames the trust direction explicitly: "Control envelope produced
 * only by the trusted supervisor; worker bytes are its payload." {@code readWorkerResponse} is the
 * one place that framing is actually load-bearing for fuzzing, because it is the supervisor
 * (trusted) parsing bytes a worker process (sandboxed, but SEC-11's own stated position is that the
 * sandbox boundary is "useful but not sufficient") produced. Every property below targets that
 * method.
 */
class SandboxSupervisorProtocolFuzzTest {

    private static final int MAGIC = 0x52525331;
    private static final int PROTOCOL_VERSION = SandboxPolicy.PROTOCOL_VERSION;
    private static final int COMPLETED_ORDINAL =
            SandboxSupervisorLauncher.SandboxOutcome.COMPLETED.ordinal();
    private static final int OUTCOME_COUNT = SandboxSupervisorLauncher.SandboxOutcome.values().length;

    // ------------------------------------------------------------------------------------------
    // Property 1 — a declared response length over maxBytes is rejected before readNBytes is ever
    // called, not merely by the post-read length mismatch a missing bound would still trip.
    //
    // Why the assertion targets the exact message: readWorkerResponse has TWO separate guards
    // against a bad length -- the pre-check (`length > maxBytes`, message "SANDBOX_OUTPUT_LIMIT")
    // and the post-check (`response.length != length`, message "SANDBOX_PROTOCOL_FAILURE", which
    // also catches a worker that sent fewer bytes than it claimed). readNBytes never blocks waiting
    // for bytes that do not exist -- it returns short -- so a stream with only the header and no
    // body still produces SOME IOException even with the pre-check deleted; only the message tells
    // the two apart, exactly the same reasoning as ProgramWireProtocolFuzzTest's collection-size
    // property.
    //
    // Red control (synthesized): temporarily deleted the `if (length < 0 || length > maxBytes)`
    // line. Why a plausible refactor could introduce this: the method already has a second bound
    // immediately after the read (`response.length != length`), so the pre-check looks redundant to
    // someone reasoning only from "what does the post-check already catch" -- it is not redundant,
    // because the post-check runs AFTER readNBytes has already been asked to allocate and fill a
    // buffer sized to the attacker-declared length, which is exactly the resource-exhaustion vector
    // maxBytes exists to bound. Same asymmetry as SEC-11's own framing: reasoning that holds for a
    // well-behaved worker (the two checks look equivalent) does not hold for a hostile or corrupted
    // one. With the mutation in place, this property failed on try 1 with message
    // "SANDBOX_PROTOCOL_FAILURE" instead of the expected "SANDBOX_OUTPUT_LIMIT" -- confirming the
    // assertion discriminates rather than accepting any IOException. Reverted immediately after
    // confirming the failure; passes again against the restored source.
    // ------------------------------------------------------------------------------------------

    @Tag("fuzz")
    @Property(tries = 100)
    void oversizedDeclaredResponseLengthsAreRejectedBeforeReading(@ForAll("oversizedLengths") int declaredLength) {
        byte[] wire = completedResponseBytesWithRawLength(declaredLength);
        var rejection = assertThrows(IOException.class,
                () -> SandboxSupervisorProtocol.readWorkerResponse(new ByteArrayInputStream(wire), MAX_BYTES));
        assertEquals("SANDBOX_OUTPUT_LIMIT", rejection.getMessage());
    }

    private static final int MAX_BYTES = 4096;

    @Provide
    Arbitrary<Integer> oversizedLengths() {
        return Arbitraries.integers().between(MAX_BYTES + 1, Integer.MAX_VALUE);
    }

    // ------------------------------------------------------------------------------------------
    // Property 2 — an out-of-range outcome ordinal is rejected as a typed protocol failure, never
    // indexed into SandboxOutcome.values() as an ArrayIndexOutOfBoundsException.
    //
    // Red control (synthesized): temporarily deleted the `if (reason >= outcomes.length)` guard.
    // Why a plausible refactor could introduce this: SandboxOutcome is a closed, small enum; a
    // future addition of a new outcome (there is a real candidate already visible in the enum's own
    // shape -- nine variants, none obviously final) changes outcomes.length, and a maintainer adding
    // the new constant might reasonably believe the array-index form is now "self-describing" and
    // drop the explicit bound as redundant ceremony -- true only for a reason byte this process
    // itself wrote, false for one a corrupted or protocol-mismatched worker sent. With the mutation
    // in place, this property failed on try 1 with an ArrayIndexOutOfBoundsException escaping
    // uncaught (not the declared IOException) -- an unclassified crash, exactly what the typed-failure
    // property forbids. Reverted immediately after confirming the failure; passes again against
    // the restored source.
    // ------------------------------------------------------------------------------------------

    @Tag("fuzz")
    @Property(tries = 100)
    void unknownOutcomeOrdinalsAreRejectedNotIndexedPastTheDeclaredOutcomes(@ForAll("outOfRangeReasons") int reason) {
        byte[] wire = responseBytesWithRawReason(reason);
        var rejection = assertThrows(IOException.class,
                () -> SandboxSupervisorProtocol.readWorkerResponse(new ByteArrayInputStream(wire), MAX_BYTES));
        assertEquals("SANDBOX_PROTOCOL_FAILURE", rejection.getMessage());
    }

    @Provide
    Arbitrary<Integer> outOfRangeReasons() {
        return Arbitraries.integers().between(OUTCOME_COUNT, 255);
    }

    // ------------------------------------------------------------------------------------------
    // Property 3 — arbitrary bytes never escape readWorkerResponse as an undeclared crash and
    // never hang. No specific defect targets this property; it is the unstructured-input safety
    // property, with the same role as ProgramWireProtocolFuzzTest's own arbitrary-bytes property.
    // ------------------------------------------------------------------------------------------

    @Tag("fuzz")
    @Property(tries = 200)
    void arbitraryByteSequencesNeverEscapeAsUnclassifiedFailuresOrHangs(@ForAll("arbitraryPayloads") byte[] payload) {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            try {
                byte[] response = SandboxSupervisorProtocol.readWorkerResponse(
                        new ByteArrayInputStream(payload), MAX_BYTES);
                assertTrue(response.length <= MAX_BYTES);
            } catch (IOException expected) {
                // Not asserting a non-null message: readWorkerResponse's leading data.readInt()
                // calls are unguarded, so a too-short stream surfaces DataInputStream's own
                // EOFException() with a null message by JDK convention, which is not a defect here.
            } catch (RuntimeException leaked) {
                fail("readWorkerResponse leaked " + leaked.getClass().getName() + " for " + payload.length
                        + " arbitrary bytes: " + leaked.getMessage());
            }
        });
    }

    @Provide
    Arbitrary<byte[]> arbitraryPayloads() {
        return Arbitraries.bytes().array(byte[].class).ofMaxSize(512);
    }

    // ------------------------------------------------------------------------------------------ wire builders

    private static byte[] completedResponseBytesWithRawLength(int declaredLength) {
        try {
            var out = new ByteArrayOutputStream();
            var data = new DataOutputStream(out);
            data.writeInt(MAGIC);
            data.writeInt(PROTOCOL_VERSION);
            data.writeByte(COMPLETED_ORDINAL);
            data.writeInt(declaredLength);
            data.flush();
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static byte[] responseBytesWithRawReason(int reason) {
        try {
            var out = new ByteArrayOutputStream();
            var data = new DataOutputStream(out);
            data.writeInt(MAGIC);
            data.writeInt(PROTOCOL_VERSION);
            data.writeByte(reason);
            data.flush();
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
