package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramAdmission;
import ai.ravenroot.api.programming.ProgramRuntime;
import ai.ravenroot.testkit.api.ProgramRuntimeContract;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * {@code GraalVmProgramRuntime} against the {@link ProgramRuntimeContract} conformance
 * suite — the shipped adapter is the first thing held to the obligation third-party adapters inherit.
 *
 * <p>{@link #executeSubject} answers from the bytes the runtime actually wrote to the worker's stdin,
 * decoded with the product's own wire protocol, rather than from the admission. That distinction is
 * the point: answering from the admission would make the assertion agree with the runtime because
 * both read the same object, which proves nothing about what reached the sandbox.
 *
 * <p>No child JVM runs here. The supervisor answers from a pre-seeded envelope, so the suite is
 * deterministic and carries no clock — the same treatment FIX-27/29/30 established in this module.
 */
class GraalVmProgramRuntimeContractTest extends ProgramRuntimeContract {
    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

    private FakeSupervisor supervisor;

    @Override
    protected ProgramRuntime runtime() {
        supervisor = new FakeSupervisor();
        supervisor.response = FakeSupervisor.wellFormedValidateResponse();
        return new GraalVmProgramRuntime(supervisor, policy(Duration.ofSeconds(5)));
    }

    @Override
    protected GeneratedArtifact executeSubject(ProgramRuntime runtime, ProgramAdmission admission)
            throws Exception {
        try {
            runtime.execute(admission, request("payload")).toCompletableFuture().get();
        } finally {
            // Recorded even when execution failed, so a refusal that nevertheless leaked a request to
            // the worker would still be visible rather than hidden behind the exception.
            if (supervisor.writtenRequest != null && supervisor.writtenRequest.length > 0) {
                var written = ProgramWireProtocol.readRequest(
                        new ByteArrayInputStream(supervisor.writtenRequest));
                executed = new GeneratedArtifact(written.artifactId(), written.language(), written.sha256(),
                        written.source(), ArtifactState.ACTIVE, 1, Instant.now(), Instant.now(), Map.of());
            }
        }
        return executed;
    }

    private GeneratedArtifact executed;

    @Override
    protected GeneratedArtifact executableArtifact(String marker) {
        String source = "() => '" + marker + "'";
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
            Instant now = Instant.now();
            return new GeneratedArtifact("artifact-" + marker, "javascript", hash, source,
                    ArtifactState.ACTIVE, 1, now, now, Map.of());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static SandboxPolicy policy(Duration timeout) {
        return new SandboxPolicy(timeout, Math.toIntExact(timeout.toMillis()), 64, 32, 256, 64,
                2 * 1024 * 1024, Path.of(System.getProperty("java.class.path")), "worker-test-id",
                JAVA, "jre-test-id");
    }
}
