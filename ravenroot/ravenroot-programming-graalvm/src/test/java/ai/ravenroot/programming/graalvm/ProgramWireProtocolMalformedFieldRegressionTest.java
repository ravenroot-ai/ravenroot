package ai.ravenroot.programming.graalvm;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression control for a QA-07 fuzz-discovered defect, promoted to a permanent, deterministic,
 * always-run test the way the GraphML fuzz target promoted its own fuzz-adjacent finding
 * to {@code GraphMlDocumentQuadraticScanRegressionTest}: {@link ProgramWireProtocol#readRequest}
 * called {@code Mode.valueOf} and {@code UUID.fromString} directly on wire-supplied strings, both
 * of which throw the unchecked {@code IllegalArgumentException}, uncovered by the method's own
 * {@code throws IOException}. Reachable by ordinary stream corruption -- a partial write, a
 * supervisor/worker version skew -- not only a hostile actor; see the fix in
 * {@link ProgramWireProtocol#readRequest} for the full account.
 *
 * <p>{@code ProgramWireProtocolFuzzTest} (tagged {@code fuzz}, excluded from the default run)
 * keeps its own generative version of this check for ongoing exploration around the same
 * invariant. This class is the fixed, minimal, always-on pin: it must never depend on
 * {@code -Pfuzz} to catch a regression here, because a malformed-frame IOException-boundary defect
 * is exactly the kind of thing that should fail every default {@code mvn test} run, not only a
 * periodic fuzz pass.
 */
class ProgramWireProtocolMalformedFieldRegressionTest {

    private static final int MAGIC = 0x52525031;

    @Test
    void aGarbageModeStringIsRejectedAsIOExceptionNotIllegalArgumentException() throws IOException {
        byte[] wire = requestBytes("NOT_A_MODE");
        assertThrows(IOException.class, () -> ProgramWireProtocol.readRequest(new ByteArrayInputStream(wire)));
    }

    @Test
    void aGarbageExecutionIdStringIsRejectedAsIOExceptionNotIllegalArgumentException() throws IOException {
        var out = new ByteArrayOutputStream();
        var data = new DataOutputStream(out);
        data.writeInt(MAGIC);
        writeString(data, "EXECUTE");
        writeString(data, "artifact-1");
        writeString(data, "javascript");
        writeString(data, "0".repeat(64));
        writeString(data, "");
        data.writeBoolean(true);
        writeString(data, "not-a-uuid");
        data.flush();
        byte[] wire = out.toByteArray();
        assertThrows(IOException.class, () -> ProgramWireProtocol.readRequest(new ByteArrayInputStream(wire)));
    }

    private static byte[] requestBytes(String mode) throws IOException {
        var out = new ByteArrayOutputStream();
        var data = new DataOutputStream(out);
        data.writeInt(MAGIC);
        writeString(data, mode);
        data.flush();
        return out.toByteArray();
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
