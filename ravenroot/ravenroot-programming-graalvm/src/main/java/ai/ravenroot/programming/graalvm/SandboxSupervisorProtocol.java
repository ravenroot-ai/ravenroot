package ai.ravenroot.programming.graalvm;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Control envelope produced only by the trusted supervisor; worker bytes are its payload. */
final class SandboxSupervisorProtocol {
    private static final int MAGIC = 0x52525331;
    private SandboxSupervisorProtocol() { }

    static byte[] readWorkerResponse(InputStream input, int maxBytes) throws IOException {
        var data = new DataInputStream(input);
        if (data.readInt() != MAGIC || data.readInt() != SandboxPolicy.PROTOCOL_VERSION) {
            throw new IOException("SANDBOX_PROTOCOL_FAILURE");
        }
        int reason = data.readUnsignedByte();
        SandboxSupervisorLauncher.SandboxOutcome[] outcomes = SandboxSupervisorLauncher.SandboxOutcome.values();
        if (reason >= outcomes.length) throw new IOException("SANDBOX_PROTOCOL_FAILURE");
        if (reason != SandboxSupervisorLauncher.SandboxOutcome.COMPLETED.ordinal()) {
            throw new SandboxFailure(outcomes[reason]);
        }
        int length = data.readInt();
        if (length < 0 || length > maxBytes) throw new IOException("SANDBOX_OUTPUT_LIMIT");
        byte[] response = data.readNBytes(length);
        if (response.length != length || data.read() != -1) throw new IOException("SANDBOX_PROTOCOL_FAILURE");
        return response;
    }

    static final class SandboxFailure extends IOException {
        SandboxFailure(SandboxSupervisorLauncher.SandboxOutcome outcome) { super("SANDBOX_" + outcome.name()); }
    }
}
