package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On the real worker, in a real child JVM: a Python resource-cache directory that
 * cannot be made writable must surface as a declared failure naming that directory, never as a
 * {@code ModuleNotFoundError} pointing at whichever import the author happened to write first.
 *
 * <p>Drives {@link GraalVmWorkerMain} with {@code -Dpolyglot.engine.userResourceCache=<blocked
 * path>} on its own command line -- the same mechanism {@code deploy/dev/sandbox-supervisor.sh}
 * uses -- so this test does not depend on {@code /opt/ravenroot} existing on the machine running
 * the suite. The blocked path is a plain file sitting where the directory would need to be
 * created, the same portable technique {@code GraalVmProgramRuntimeFromEnvironmentTest} already
 * uses for the identical reason: it fails {@code Files.createDirectories} regardless of which user
 * or CI container runs this suite, unlike stripping a POSIX write bit, which a root-run container
 * can ignore entirely.
 *
 * <p><b>Measured failure before the writable-directory check.</b> Run against {@link GraalVmWorkerMain} before
 * {@code verifyResourceCacheWritable} existed, this exact scenario produced {@code
 * PolyglotException: ModuleNotFoundError: No module named 'json'} -- naming the first import in a
 * twenty-line source that did nothing wrong, exactly the misleading symptom this check prevents, just
 * reached through a different unwritable directory than the original report.
 */
class PythonResourceCacheUnwritableWorkerTest {

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void anUnwritableResourceCacheDirectoryIsReportedByNameNotAsAModuleImportFailure(
            @TempDir Path directory) throws Exception {
        Path blocked = directory.resolve("blocks-the-cache-directory");
        Files.writeString(blocked, "not a directory");

        // The import must be at MODULE level, not inside the handler body.
        // VALIDATE only evaluates the source and checks it is callable -- it never CALLS the
        // handler (see GraalVmWorkerMain#evaluate: the VALIDATE-mode return happens after eval, and
        // an import inside a `def` body only runs when that function is later invoked). A first
        // draft of this test put `import json` inside the handler and could not tell red from
        // green: with the writable-directory check disabled, the response was a plain success, because nothing
        // in a VALIDATE ever executed the import at all. A module-level import runs during
        // `context.eval(source)` itself, exactly like the shipped Python starter's own first line
        // and exactly the failure mode under test.
        byte[] bytes = RealWorkerRun.capture(ProgramWireProtocol.Mode.VALIDATE,
                artifact("import json\ndef handler(request):\n    return {'value': "
                        + "json.dumps(request.payload)}\nhandler"),
                request(),
                List.of("-Dpolyglot.engine.userResourceCache=" + blocked));

        var rejected = assertThrows(ProgramWireProtocol.ProgramWorkerException.class,
                () -> ProgramWireProtocol.readResponse(new ByteArrayInputStream(bytes)));

        assertFalse(rejected.sourceRejected(),
                "an unwritable cache directory is an infrastructure fault, not a defect in the "
                        + "artifact's own source -- it must not be classified the way a genuine "
                        + "syntax error is");
        assertTrue(rejected.detail().contains(blocked.toString()),
                "the operator (and, through the startup diagnostic, the boot log) needs the "
                        + "directory named, not just a generic refusal, was: " + rejected.detail());
        assertFalse(rejected.detail().contains("ModuleNotFoundError"),
                "the whole point of this fix is that the real cause -- an unwritable cache "
                        + "directory -- replaces the misleading module-not-found symptom, was: "
                        + rejected.detail());
    }

    private static ProgramRequest request() {
        return new ProgramRequest(UUID.randomUUID(), "node-1", Map.of("name", "Ravenroot"), Map.of());
    }

    private static GeneratedArtifact artifact(String source) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
            Instant now = Instant.now();
            return new GeneratedArtifact("resource-cache-artifact", "python", hash, source,
                    ArtifactState.GENERATED, 1, now, now, Map.of());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
