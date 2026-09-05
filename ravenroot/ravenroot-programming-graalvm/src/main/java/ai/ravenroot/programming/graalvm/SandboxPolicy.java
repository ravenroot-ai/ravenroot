package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.node.service.ExternalIoLimits;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Immutable, versioned limits handed to the trusted sandbox supervisor. */
public record SandboxPolicy(Duration deadline, int cpuMillis, int memoryMiB, int maxPids, int maxFiles,
                            int tmpfsMiB, int maxOutputBytes, Path trustedWorker, String workerIdentity,
                            Path trustedJre, String jreIdentity) {
    public static final int PROTOCOL_VERSION = 1;

    public SandboxPolicy {
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(trustedWorker, "trustedWorker");
        Objects.requireNonNull(workerIdentity, "workerIdentity");
        Objects.requireNonNull(trustedJre, "trustedJre");
        Objects.requireNonNull(jreIdentity, "jreIdentity");
        if (deadline.isNegative() || deadline.isZero() || cpuMillis < 1 || memoryMiB < 32 || maxPids < 1
                || maxFiles < 1 || tmpfsMiB < 1 || maxOutputBytes < 1 || workerIdentity.isBlank()
                || jreIdentity.isBlank()) throw new IllegalArgumentException("Invalid sandbox policy");
        trustedWorker = trustedWorker.toAbsolutePath().normalize();
        trustedJre = trustedJre.toAbsolutePath().normalize();
    }

    List<String> arguments() {
        return List.of("--ravenroot-sandbox-supervisor=v" + PROTOCOL_VERSION,
                "--deadline-ms=" + deadline.toMillis(), "--cpu-ms=" + cpuMillis,
                "--memory-mib=" + memoryMiB, "--max-pids=" + maxPids, "--max-files=" + maxFiles,
                "--tmpfs-mib=" + tmpfsMiB, "--max-output-bytes=" + maxOutputBytes,
                "--trusted-worker=" + trustedWorker, "--trusted-worker-sha256=" + workerIdentity,
                "--trusted-jre=" + trustedJre, "--trusted-jre-sha256=" + jreIdentity);
    }

    /**
     * Maps this supervisor policy to the common finite external-I/O contract.
     *
     * <p>The supervisor protocol is binary and never accepts compressed data or media-type
     * negotiation. Its process, CPU, file, and memory restrictions remain additional narrower
     * controls.</p>
     *
     * @return finite request, response, deadline, and teardown limits for one supervisor session
     */
    public ExternalIoLimits externalIoLimits() {
        int response = Math.min(maxOutputBytes, ProgramWireProtocol.MAX_RESPONSE_BYTES);
        return new ExternalIoLimits(GraalVmProgramRuntime.MAX_REQUEST_BYTES, response, response,
                response, 1, deadline, GraalVmProgramRuntime.REAP_TIMEOUT,
                java.util.Set.of(), java.util.Set.of("identity"));
    }
}
