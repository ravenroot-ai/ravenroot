package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startup probe {@link GraalVmProgramRuntime#fromEnvironment()}
 * runs. Before this class existed, nothing called {@code fromEnvironment()} at all -- the whole probe
 * (the {@code instanceof SandboxSupervisorProcessLauncher} guard, the {@code verifyCapability()} call,
 * the {@code "startup"} stage on the log line) could be deleted and the suite would stay green. This
 * is what makes deleting it red.
 *
 * <p>Real {@link SandboxSupervisorProcessLauncher} instances only, spawning real (trivial) shell
 * scripts -- {@link FakeSupervisor} cannot stand in here because {@code describe()} on the real
 * launcher, returning the actual configured path, is exactly the fact under test. The scripts answer
 * only {@code --ravenroot-sandbox-supervisor-capabilities=v1}, which is all {@code verifyCapability()}
 * ever sends; none of these tests calls {@code launch()}.
 */
class GraalVmProgramRuntimeFromEnvironmentTest {

    @TempDir
    Path directory;

    /**
     * The regression this class exists to block: a supervisor an operator pointed at, that turns out
     * not to be executable, must be visible in the startup log (condition and path) AND must not stop
     * the server from starting. Asserting only one of the two would permit a diagnostic that silently
     * becomes an outage, or a log line that silently
     * disappears.
     */
    @Test
    void aConfiguredButUnexecutableSupervisorIsFlaggedAtStartupAndTheRuntimeStillConstructs() throws Exception {
        Path script = writeScript("supervisor.sh", HEALTHY_BODY, false);

        var log = new java.util.concurrent.atomic.AtomicReference<String>();
        var runtime = assertDoesNotThrow(
                () -> captureStderr(log,
                        () -> GraalVmProgramRuntime.fromEnvironment(
                                Map.of("RAVENROOT_GRAAL_SANDBOX_SUPERVISOR", script.toString()))),
                "the startup probe must never turn a diagnostic into a refusal to start");
        assertNotNull(runtime, "fromEnvironment() must still hand back a usable runtime object");

        assertTrue(log.get().contains("\"event\":\"program_sandbox_unavailable\""), log.get());
        assertTrue(log.get().contains("\"stage\":\"startup\""), log.get());
        assertTrue(log.get().contains("\"reason\":\"SANDBOX_LAUNCHER_MISSING\""), log.get());
        assertTrue(log.get().contains(script.toString()),
                "the operator reading the boot log needs the path, not just the token, was: " + log.get());
    }

    /** A supervisor with the wrong banner is SANDBOX_CAPABILITY_UNSUPPORTED, not SANDBOX_LAUNCHER_MISSING. */
    @Test
    void aConfiguredSupervisorWithTheWrongBannerIsFlaggedAtStartupWithTheOtherCondition() throws Exception {
        Path script = writeScript("supervisor.sh", "#!/bin/sh\nprintf 'not-the-right-banner'\nexit 0\n", true);

        var log = new java.util.concurrent.atomic.AtomicReference<String>();
        var runtime = assertDoesNotThrow(() -> captureStderr(log,
                () -> GraalVmProgramRuntime.fromEnvironment(
                        Map.of("RAVENROOT_GRAAL_SANDBOX_SUPERVISOR", script.toString()))));
        assertNotNull(runtime);

        assertTrue(log.get().contains("\"stage\":\"startup\""), log.get());
        assertTrue(log.get().contains("\"reason\":\"SANDBOX_CAPABILITY_UNSUPPORTED\""), log.get());
        assertTrue(log.get().contains(script.toString()), log.get());
    }

    /**
     * "Not configured" is a legitimate, silent state -- it must not be flagged as if an operator's
     * configuration were broken. MissingLauncher's own verifyCapability() is deliberately never probed
     * at startup for this reason (see the {@code instanceof SandboxSupervisorProcessLauncher} guard).
     */
    @Test
    void anAbsentSupervisorIsSilentAtStartup() throws Exception {
        var log = new java.util.concurrent.atomic.AtomicReference<String>();
        var runtime = captureStderr(log, () -> GraalVmProgramRuntime.fromEnvironment(Map.of()));

        assertNotNull(runtime);
        assertEquals("", log.get(), "an unconfigured deployment is not a broken one; nothing to flag");
    }

    /**
     * A healthy, correctly-answering supervisor produces no noise at boot -- including the
     * resource-cache check, which is why {@code RAVENROOT_GRAAL_RESOURCE_CACHE_DIR} is pointed at
     * a directory this test JVM can actually write to. Without that override the check would try
     * {@link GraalVmWorkerMain#DEFAULT_RESOURCE_CACHE_DIR} ({@code /opt/ravenroot/data/cache}),
     * which does not exist and is not writable on a development machine or a CI runner outside the
     * shipped image -- exactly what {@link #theResourceCacheDirectoryIsFlaggedAtStartupWhenItCannotBeMadeWritable}
     * below exercises on purpose.
     */
    @Test
    void aHealthySupervisorIsSilentAtStartup() throws Exception {
        Path script = writeScript("supervisor.sh", HEALTHY_BODY, true);
        Path cache = directory.resolve("cache");

        var log = new java.util.concurrent.atomic.AtomicReference<String>();
        var runtime = captureStderr(log, () -> GraalVmProgramRuntime.fromEnvironment(
                Map.of("RAVENROOT_GRAAL_SANDBOX_SUPERVISOR", script.toString(),
                        "RAVENROOT_GRAAL_RESOURCE_CACHE_DIR", cache.toString())));

        assertNotNull(runtime);
        assertEquals("", log.get(), "a working supervisor must not appear in the log at all");
        assertTrue(Files.isDirectory(cache), "the checked directory must be created if it is missing");
    }

    /**
     * The counterpart to the sandbox-capability tests above, for the resource-cache
     * directory {@link GraalVmWorkerMain}'s worker process relies on to make GraalPy's standard
     * library reachable under a read-only root filesystem. A file sitting where the directory
     * needs to be created is a portable way to force {@code Files.createDirectories} to fail
     * without depending on this test JVM's ability to strip its own write permission (which
     * differs across platforms and, running as root in some CI containers, may not even be
     * enforced). Never fatal: the same boundary applies here as it does to a broken sandbox
     * supervisor -- an optional component's misconfiguration must be a boot-log diagnostic, never
     * a refusal to start.
     */
    @Test
    void theResourceCacheDirectoryIsFlaggedAtStartupWhenItCannotBeMadeWritable() throws Exception {
        Path script = writeScript("supervisor.sh", HEALTHY_BODY, true);
        Path blocked = directory.resolve("blocks-the-cache-directory");
        Files.writeString(blocked, "not a directory");

        var log = new java.util.concurrent.atomic.AtomicReference<String>();
        var runtime = assertDoesNotThrow(() -> captureStderr(log, () -> GraalVmProgramRuntime.fromEnvironment(
                Map.of("RAVENROOT_GRAAL_SANDBOX_SUPERVISOR", script.toString(),
                        "RAVENROOT_GRAAL_RESOURCE_CACHE_DIR", blocked.toString()))),
                "an unwritable resource-cache directory must never stop the server from starting");

        assertNotNull(runtime, "fromEnvironment() must still hand back a usable runtime object");
        assertTrue(log.get().contains("\"event\":\"program_resource_cache_unavailable\""), log.get());
        assertTrue(log.get().contains("\"stage\":\"startup\""), log.get());
        assertTrue(log.get().contains(blocked.toString()),
                "the operator reading the boot log needs the directory, not just the token, was: " + log.get());
    }

    /**
     * The same condition as above, but on a directory that already EXISTS and is not
     * writable -- distinct from the file-blocking-creation case, which only exercises
     * {@code Files.createDirectories} failing, never {@code Files.isWritable} on a real,
     * pre-existing directory. This directly models a volume whose
     * {@code data/cache} subdirectory survives from a previous deployment but is owned by a
     * different user. Skipped when the test JVM cannot strip its own write permission (some CI
     * containers run as root, where POSIX permission bits do not gate the owner) -- consistent with
     * the same caveat this class's Javadoc already documents for the file-blocking test.
     */
    @Test
    void theResourceCacheDirectoryIsFlaggedAtStartupWhenItExistsAndIsNotWritable() throws Exception {
        Path script = writeScript("supervisor.sh", HEALTHY_BODY, true);
        Path existingButReadOnly = directory.resolve("read-only-cache");
        Files.createDirectory(existingButReadOnly);
        Files.setPosixFilePermissions(existingButReadOnly, PosixFilePermissions.fromString("r-xr-xr-x"));
        org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(existingButReadOnly),
                "this test JVM can still write to a directory with its write bit stripped (likely "
                        + "running as root); the condition under test cannot be reproduced here");

        var log = new java.util.concurrent.atomic.AtomicReference<String>();
        try {
            var runtime = assertDoesNotThrow(() -> captureStderr(log, () -> GraalVmProgramRuntime.fromEnvironment(
                    Map.of("RAVENROOT_GRAAL_SANDBOX_SUPERVISOR", script.toString(),
                            "RAVENROOT_GRAAL_RESOURCE_CACHE_DIR", existingButReadOnly.toString()))));
            assertNotNull(runtime);
        } finally {
            // Restore write access so @TempDir's own cleanup (JUnit deletes the directory after the
            // test) does not fail on a read-only directory it cannot remove entries from.
            Files.setPosixFilePermissions(existingButReadOnly, PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        assertTrue(log.get().contains("\"event\":\"program_resource_cache_unavailable\""), log.get());
        assertTrue(log.get().contains(existingButReadOnly.toString()), log.get());
    }

    /**
     * An operator sets
     * {@code RAVENROOT_GRAAL_RESOURCE_CACHE_DIR} to a directory this server can write to, but the
     * DEFAULT directory the worker actually falls back to (the override never reaches it in the
     * shipped stack -- see {@link GraalVmProgramRuntime#checkResourceCacheStartup}'s Javadoc) is
     * not writable. Before this fix, the override being fine was enough to keep the startup log
     * silent; the default was never checked once an override was present. That silence is exactly
     * backwards: the override is the path least likely to matter in the shipped stack, and the
     * default is the one that decides whether the first Validate works.
     */
    @Test
    void aWritableOverrideDoesNotHideAnUnwritableDefault(@TempDir Path anotherDirectory) throws Exception {
        Path writableOverride = directory.resolve("writable-override");
        Path unwritableDefault = anotherDirectory.resolve("unwritable-default");
        Files.writeString(unwritableDefault, "blocks the default directory from being created")
                .toFile().deleteOnExit();

        var log = new java.util.concurrent.atomic.AtomicReference<String>();
        captureStderr(log, () -> {
            GraalVmProgramRuntime.checkResourceCacheStartup(
                    directory, unwritableDefault.toString(), writableOverride.toString());
            return null;
        });

        assertTrue(Files.isDirectory(writableOverride),
                "the writable override must still have been created -- it IS checked, just not "
                        + "instead of the default");
        assertTrue(log.get().contains("\"event\":\"program_resource_cache_unavailable\""),
                "the unwritable DEFAULT must be flagged even though the override is fine, was: " + log.get());
        assertTrue(log.get().contains(unwritableDefault.toString()), log.get());
    }

    /**
     * The request-stage check uses a REAL path rather than {@link FakeSupervisor}'s
     * bare class name: the request-time log call ({@code stage="request"}) is the same
     * {@code logSandboxUnavailable} call as the startup one, reached from
     * {@code GraalVmProgramRuntime#invokeSupervisor} instead of {@code #fromEnvironment}, and it must
     * carry {@link SandboxSupervisorLauncher#describe()}'s real value there too, not only at startup.
     */
    @Test
    void theRequestStageAlsoNamesTheConditionAndThePath() throws Exception {
        Path script = writeScript("supervisor.sh", HEALTHY_BODY, false);
        // Constructing already runs the startup probe against this same unexecutable script and logs
        // its own "startup" line -- captured and discarded here so it does not leak to the real
        // console; it is not what this test is about, and it is already asserted on elsewhere by
        // aConfiguredButUnexecutableSupervisorIsFlaggedAtStartupAndTheRuntimeStillConstructs.
        var startupLog = new java.util.concurrent.atomic.AtomicReference<String>();
        var runtime = captureStderr(startupLog, () -> GraalVmProgramRuntime.fromEnvironment(
                Map.of("RAVENROOT_GRAAL_SANDBOX_SUPERVISOR", script.toString())));

        var log = new java.util.concurrent.atomic.AtomicReference<String>();
        captureStderr(log, () -> {
            var error = assertThrows(ExecutionException.class,
                    () -> runtime.validate(artifact("() => 1")).toCompletableFuture().get());
            assertTrue(error.getCause().getMessage().contains("SANDBOX_LAUNCHER_MISSING"));
            return null;
        });

        assertTrue(log.get().contains("\"stage\":\"request\""), log.get());
        assertTrue(log.get().contains("\"reason\":\"SANDBOX_LAUNCHER_MISSING\""), log.get());
        assertTrue(log.get().contains(script.toString()), log.get());
    }

    private static final String HEALTHY_BODY = "#!/bin/sh\nprintf 'ravenroot-sandbox-supervisor/1'\nexit 0\n";

    private Path writeScript(String name, String body, boolean executable) throws Exception {
        Path script = directory.resolve(name);
        Files.writeString(script, body);
        Files.setPosixFilePermissions(script,
                PosixFilePermissions.fromString(executable ? "rwxr-xr-x" : "rw-r--r--"));
        return script;
    }

    private static GeneratedArtifact artifact(String source) {
        try {
            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
            Instant now = Instant.now();
            return new GeneratedArtifact("test-artifact", "javascript", hash, source,
                    ArtifactState.GENERATED, 1, now, now, Map.of());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    /**
     * Same capture shape as {@code GraalVmProgramRuntimeTest} and {@code RavenrootServerMainLifecycleTest};
     * kept local because this module has no shared test-support dependency on either. Stores the
     * captured text into {@code sink} as a side effect so callers can both return a value from
     * {@code action} and inspect the log, which a plain {@code Runnable}-based capture cannot do.
     */
    private static <T> T captureStderr(java.util.concurrent.atomic.AtomicReference<String> sink,
                                        java.util.concurrent.Callable<T> action) throws Exception {
        var output = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (var captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setErr(captured);
            return action.call();
        } finally {
            System.setErr(previous);
            sink.set(output.toString(StandardCharsets.UTF_8));
        }
    }
}
