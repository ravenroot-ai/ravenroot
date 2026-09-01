package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramLanguageDescriptor;
import ai.ravenroot.api.programming.ProgramRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Where {@link PythonProgramExecutionTest} proves the adapter CAN run Python, this class
 * proves the specific string the editor's workbench would offer a first-time author actually does
 * -- read through {@link GraalVmProgramRuntime#supportedLanguages()} exactly as the UI reads it,
 * never retyped by hand here, so a future edit to {@code ProgramLanguage.PYTHON}'s starter cannot
 * silently drift from what this test executes.
 *
 * <p><b>Live, not captured-and-replayed.</b> {@link PythonProgramExecutionTest} deliberately
 * separates a real worker's cold start from the assertion it feeds (see {@code RealWorkerRun}'s
 * Javadoc) because {@code GraalVmProgramRuntimeTest}'s sandbox deadline is tight. This class does
 * not share that constraint -- it is not proving deadline behaviour -- so it drives
 * {@link GraalVmProgramRuntime#validate}, {@code #test} and {@code #execute} against a genuinely
 * live child JVM ({@link FakeSupervisor} with no {@code response} pre-set spawns one for real; see
 * its own Javadoc), through the runtime's real async API, exactly as
 * {@code DefaultRavenrootApplication}'s five-step lifecycle and {@code ProgramNodeBehaviorFactory}
 * call it.
 *
 * <p><b>On timing.</b> The first Python evaluation on a given machine materialises the component's
 * standard library into the polyglot resource cache
 * ({@code ~/Library/Caches/org.graalvm.polyglot} on macOS) -- documented in
 * {@code docs/architecture/python-programmable-nodes.md} at ~2.3 s idle, up to ~8 s under load, for
 * an already-warm cache. A cold cache on a machine that has never run this component's Python
 * before can cost substantially more the very first time. The deadline and the JUnit timeout below
 * are set generously for that reason; a slow first run is not this test's failure mode, a wrong
 * answer or a timeout on every later run would be.
 */
class PythonExampleSourceLiveExecutionTest {
    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");
    private static final Duration DEADLINE = Duration.ofSeconds(150);

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void theShippedPythonStarterValidatesTestsAndExecutesAgainstALiveWorker() throws Exception {
        String source = shippedPythonExampleSource();

        // Asserted structurally, not just by omission. "Live" here means FakeSupervisor
        // took its no-response branch and spawned a real child JVM per call (see its own Javadoc on
        // `realSubprocessSpawns`) -- pre-setting `.response` is exactly how the OTHER Python tests in
        // this module stay deterministic, so the absence of that field is easy to get quietly wrong
        // in a future edit. This counter is what would catch that.
        var supervisor = new FakeSupervisor();
        GraalVmProgramRuntime runtime = new GraalVmProgramRuntime(supervisor, policy());

        GeneratedArtifact generated = artifact(source, ArtifactState.GENERATED);
        assertNull(runtime.validate(generated).toCompletableFuture().get(),
                "a validate response is null on success; a genuine syntax or callability refusal "
                        + "would surface as an ExecutionException here instead");

        GeneratedArtifact validated = artifact(source, ArtifactState.VALIDATED);
        ProgramRequest request = new ProgramRequest(UUID.randomUUID(), "node-under-test",
                "hello ravenroot", Map.of());
        Object tested = runtime.test(validated, request).toCompletableFuture().get();
        assertEquals(Map.of("value", "hello ravenroot"), tested,
                "the shipped starter is `def handler(request): return {'value': str(request.payload)}`"
                        + "; a real interpreter must therefore echo the payload back under 'value'");

        // A program node carrying that artifactId must execute it. This is the exact call
        // ProgramNodeBehaviorFactory's handler makes -- runtime.execute(admission, request) -- using
        // TestAdmission, the same admission double GraalVmProgramRuntimeContractTest uses to stand
        // in for ArtifactRegistry#admitForExecution's real handle.
        GeneratedArtifact active = artifact(source, ArtifactState.ACTIVE);
        Object executed = runtime.execute(TestAdmission.of(active), request).toCompletableFuture().get();
        assertEquals(Map.of("value", "hello ravenroot"), executed);

        org.junit.jupiter.api.Assertions.assertEquals(3, supervisor.realSubprocessSpawns,
                "one real child JVM per call (validate, test, execute) -- a count of 0 would mean "
                        + "this test silently took FakeSupervisor's pre-seeded-response branch instead "
                        + "of the live one, which is the property \"live, not captured-and-replayed\" "
                        + "actually depends on");
    }

    /**
     * Reads the starter the same way {@code programWorkspaceContentHtml} in {@code app.js} will:
     * off {@link ProgramLanguageDescriptor#exampleSource()}, found by id in the list
     * {@link GraalVmProgramRuntime#supportedLanguages()} declares -- never a literal copied into this
     * test by hand.
     */
    private static String shippedPythonExampleSource() {
        List<ProgramLanguageDescriptor> languages =
                new GraalVmProgramRuntime(new FakeSupervisor(), policy()).supportedLanguages();
        return languages.stream().filter(language -> "python".equals(language.id())).findFirst()
                .orElseThrow(() -> new AssertionError("supportedLanguages() no longer declares python: "
                        + languages))
                .exampleSource();
    }

    private static SandboxPolicy policy() {
        return new SandboxPolicy(DEADLINE, Math.toIntExact(DEADLINE.toMillis()), 128, 32, 256, 64,
                2 * 1024 * 1024, Path.of(System.getProperty("java.class.path")), "worker-live-test-id",
                JAVA, "jre-live-test-id");
    }

    private static GeneratedArtifact artifact(String source, ArtifactState state) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
            Instant now = Instant.now();
            return new GeneratedArtifact("python-example-source-artifact", "python", hash, source, state, 1,
                    now, now, Map.of());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
