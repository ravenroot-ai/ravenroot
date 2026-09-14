package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.programming.ProgramRuntimeUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SandboxCachePlacementTest {
    @TempDir Path directory;

    @Test void strictV1KeepsExactLegacyArgumentsAndRefusesOverrideWithoutLaunching() throws Exception {
        Path launched = directory.resolve("launched");
        Path script = script("strict.sh", """
                if [ "$#" -eq 1 ] && [ "$1" = '--ravenroot-sandbox-supervisor-capabilities=v1' ]; then
                    printf 'ravenroot-sandbox-supervisor/1'; exit 0
                fi
                case "$1" in --ravenroot-sandbox-supervisor-capabilities=*) exit 78 ;; esac
                printf '%%s\\n' "$@" > %s
                """.formatted(quote(launched.toString())));
        var launcher = new SandboxSupervisorProcessLauncher(script);
        var policy = policy(Path.of(System.getProperty("java.home"), "bin/java"));
        launcher.verifyCapability();
        try (var session = launcher.launch(policy, SandboxLaunchPlacement.LEGACY)) {
            assertEquals(SandboxSupervisorLauncher.SandboxOutcome.COMPLETED, session.await(Duration.ofSeconds(2)));
        }
        assertEquals(policy.arguments(), Files.readAllLines(launched));
        Files.delete(launched);
        assertEquals("SANDBOX_RESOURCE_CACHE_UNSUPPORTED", assertThrows(IOException.class,
                () -> launcher.launch(policy, new SandboxLaunchPlacement("/cache"))).getMessage());
        assertFalse(Files.exists(launched));
        var admission = TestAdmission.of(null);
        var runtime = new GraalVmProgramRuntime(launcher, policy, new SandboxLaunchPlacement("/cache"));
        var failure = assertThrows(ExecutionException.class, () -> runtime.execute(admission, null).toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertInstanceOf(ProgramRuntimeUnavailableException.class, failure.getCause());
        assertEquals(0, admission.redemptions.get());
        assertFalse(Files.exists(launched));
    }

    @Test void legacyInterfaceImplementationCannotIgnoreAnOverride() throws Exception {
        var legacy = new FakeSupervisor();
        assertThrows(IOException.class, () -> legacy.launch(policy(Path.of("/java")), new SandboxLaunchPlacement("")));
        assertEquals(0, legacy.launches);
        try (var ignored = legacy.launch(policy(Path.of("/java")), SandboxLaunchPlacement.LEGACY)) {
            assertEquals(1, legacy.launches);
        }
    }

    @Test void overridingOnlyVerificationCannotMakeInheritedLaunchIgnorePlacement() {
        var launches = new java.util.concurrent.atomic.AtomicInteger();
        SandboxSupervisorLauncher customVerifier = new SandboxSupervisorLauncher() {
            @Override public void verifyCapability() { }
            @Override public void verifyPlacement(SandboxLaunchPlacement placement) { }
            @Override public SandboxSupervisorSession launch(SandboxPolicy policy) {
                launches.incrementAndGet();
                throw new AssertionError("Legacy launch must not receive an ignored placement override");
            }
        };
        assertEquals("SANDBOX_RESOURCE_CACHE_UNSUPPORTED", assertThrows(IOException.class,
                () -> customVerifier.launch(policy(Path.of("/java")), new SandboxLaunchPlacement("/cache"))).getMessage());
        assertEquals(0, launches.get());
    }

    @Test void malformedNonzeroOversizedAndTimedOutCapabilityNeverLaunches() throws Exception {
        for (String body : List.of("exit 0", "printf 'wrong'", "printf 'ravenroot-sandbox-supervisor-resource-cache/1 extra'",
                "printf 'ravenroot-sandbox-supervisor-resource-cache/1'; exit 1",
                "i=0; while [ \"$i\" -lt 300 ]; do printf x; i=$((i+1)); done",
                "while :; do :; done")) {
            var launcher = new SandboxSupervisorProcessLauncher(script("failure.sh", body));
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(IOException.class,
                    () -> launcher.launch(policy(Path.of("/java")), new SandboxLaunchPlacement("/cache"))));
        }
    }

    @Test void shippedSupervisorSetsTheActualWorkerJvmPropertyAsOneArgument() throws Exception {
        Path marker = directory.resolve("injected");
        String hostile = directory + "/cache space ' $(touch " + marker + ") ; value";
        // The trusted JRE wrapper substitutes only the worker entry point, retaining the script's
        // actual JVM property argument. A real child JVM observes that value and its scrubbed env.
        Path jre = script("java-wrapper.sh", """
                [ "$#" -eq 4 ] && [ "$2" = '-cp' ] && [ "$4" = 'ai.ravenroot.programming.graalvm.GraalVmWorkerMain' ] || exit 79
                exec %s "$1" -cp %s 'ai.ravenroot.programming.graalvm.SandboxCachePlacementTest$PropertyProbe'
                """.formatted(quote(Path.of(System.getProperty("java.home"), "bin/java").toString()), quote(System.getProperty("java.class.path"))));
        var launcher = new SandboxSupervisorProcessLauncher(shippedScript());
        launcher.verifyCapability();
        for (var placement : List.of(SandboxLaunchPlacement.LEGACY, new SandboxLaunchPlacement(hostile),
                new SandboxLaunchPlacement(""), new SandboxLaunchPlacement("   "))) {
            try (var session = launcher.launch(policy(jre), placement)) {
                session.workerInput().close();
                var payload = SandboxSupervisorProtocol.readWorkerResponse(session.supervisorControl(), 4096);
                String actual = new String(payload, StandardCharsets.UTF_8);
                assertEquals((placement.hasOverride() ? placement.resourceCachePropertyValue() : "/opt/ravenroot/data/cache") + "\nHOME=null\nJAVA_TOOL_OPTIONS=null", actual);
                assertEquals(SandboxSupervisorLauncher.SandboxOutcome.COMPLETED, session.await(Duration.ofSeconds(5)));
            }
        }
        assertFalse(Files.exists(marker));
    }

    @Test void shippedSupervisorRejectsMalformedExtensionBeforeWorkerCreation() throws Exception {
        Path launched = directory.resolve("must-not-launch");
        var base = policy(script("must-not-launch.sh", "touch " + quote(launched.toString()))).arguments();
        for (List<String> extras : List.of(List.of("--resource-cache-property=/cache"), List.of("--resource-cache-property"),
                List.of("--ravenroot-sandbox-supervisor-extension=resource-cache-v1"),
                List.of("--ravenroot-sandbox-supervisor-extension=resource-cache-v1", "--resource-cache-property=a", "--resource-cache-property=b"),
                List.of("--ravenroot-sandbox-supervisor-extension=resource-cache-v1", "--ravenroot-sandbox-supervisor-extension=resource-cache-v1", "--resource-cache-property=a"),
                List.of("--ravenroot-sandbox-supervisor-extension=unknown", "--resource-cache-property=a"))) {
            var command = new ArrayList<String>(); command.add(shippedScript().toString()); command.addAll(base); command.addAll(extras);
            Process process = new ProcessBuilder(command).start();
            assertTrue(process.waitFor(2, TimeUnit.SECONDS));
            assertEquals(78, process.exitValue());
            assertFalse(Files.exists(launched));
        }
    }

    @Test void shippedSupervisorRunsTheRealWorkerWithTheSelectedCache() throws Exception {
        Path cache = directory.resolve("actual worker cache");
        Path observedPolicy = directory.resolve("policy.arguments");
        Path recordingSupervisor = script("recording-supervisor.sh", """
                case "$1" in --ravenroot-sandbox-supervisor=v1) printf '%%s\\n' "$@" > %s ;; esac
                exec %s "$@"
                """.formatted(quote(observedPolicy.toString()), quote(shippedScript().toString())));
        var config = GraalVmRuntimeConfiguration.resolve(new java.util.Properties(), java.util.Map.of(
                "RAVENROOT_GRAAL_SANDBOX_SUPERVISOR", recordingSupervisor.toString(),
                "RAVENROOT_GRAAL_RESOURCE_CACHE_DIR", cache.toString(),
                "RAVENROOT_PROGRAM_TIMEOUT_MS", "60000", "RAVENROOT_PROGRAM_MAX_HEAP_MB", "96"));
        var runtime = GraalVmProgramRuntime.fromConfiguration(config);
        String source = "import json\ndef handler(request):\n    return json.loads('1')\nhandler";
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(StandardCharsets.UTF_8)));
        var now = java.time.Instant.now();
        var artifact = new ai.ravenroot.api.programming.GeneratedArtifact("cache-test", "python", hash, source,
                ai.ravenroot.api.programming.ArtifactState.GENERATED, 1, now, now, java.util.Map.of());
        assertNull(runtime.validate(artifact).toCompletableFuture().get(90, TimeUnit.SECONDS));
        var arguments = Files.readAllLines(observedPolicy);
        assertTrue(arguments.containsAll(List.of("--deadline-ms=60000", "--cpu-ms=60000", "--memory-mib=96",
                "--tmpfs-mib=96", "--max-pids=32", "--max-files=256", "--max-output-bytes=2097152")), arguments.toString());
        assertTrue(arguments.contains("--trusted-jre=" + config.javaExecutable()));
        try (var paths = Files.walk(cache)) {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().equals("json") && Files.isDirectory(path)),
                    "The real GraalPy worker must extract its standard library in the configured cache");
        }
    }

    @Test void interruptedProbePreservesInterruptAndReapsItsProcess() throws Exception {
        Path pid = directory.resolve("probe.pid");
        var launcher = new SandboxSupervisorProcessLauncher(script("interrupt.sh",
                "printf '%s' \"$$\" > " + quote(pid.toString()) + "\nwhile :; do :; done"));
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Thread thread = Thread.ofPlatform().start(() -> {
            try { launcher.verifyPlacement(new SandboxLaunchPlacement("/cache")); }
            catch (Throwable error) { failure.set(error); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((!Files.exists(pid) || Files.size(pid) == 0) && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(Files.exists(pid));
        long processId = Long.parseLong(Files.readString(pid));
        thread.interrupt(); thread.join(5000);
        assertFalse(thread.isAlive());
        assertInstanceOf(IOException.class, failure.get());
        assertTrue(interrupted.get());
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(5);
        assertFalse(ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false));
    }

    public static class PropertyProbe {
        public static void main(String[] args) {
            System.out.print(System.getProperty("polyglot.engine.userResourceCache") + "\nHOME=" + System.getenv("HOME")
                    + "\nJAVA_TOOL_OPTIONS=" + System.getenv("JAVA_TOOL_OPTIONS"));
        }
    }

    private SandboxPolicy policy(Path jre) {
        return new SandboxPolicy(Duration.ofSeconds(5), 5000, 64, 32, 256, 64, 4096, directory, "worker", jre, "jre");
    }
    private Path script(String name, String body) throws Exception {
        Path script = directory.resolve(name); Files.writeString(script, "#!/bin/sh\nset -eu\n" + body + "\n");
        assertTrue(script.toFile().setExecutable(true)); return script;
    }
    private static Path shippedScript() {
        return Path.of("../../deploy/dev/sandbox-supervisor.sh").toAbsolutePath().normalize();
    }
    private static String quote(String text) { return "'" + text.replace("'", "'\\''") + "'"; }
}
