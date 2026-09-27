package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramArtifactIdentity;
import ai.ravenroot.api.programming.ProgramRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes the public evidence source unchanged through real child JVMs, not recorded responses. */
class FiniteAutomataProgramEvidenceTest {
    @Test
    @Timeout(180)
    @SuppressWarnings("unchecked")
    void publicExamplesExecuteInTheProgramAdapter() throws Exception {
        Path directory = repositoryRoot().resolve("docs/examples/finite-automata");
        String source = Files.readString(directory.resolve("program.js"));
        var cases = (List<Map<String, Object>>) (Object) PayloadJson.read(
                Files.readAllBytes(directory.resolve("fixtures.json")), PayloadLimits.DEFAULTS).toJava();
        var supervisor = new FakeSupervisor();
        var runtime = new GraalVmProgramRuntime(supervisor, new SandboxPolicy(
                Duration.ofSeconds(30), 30_000, 128, 32, 256, 64, 2 * 1024 * 1024,
                Path.of(System.getProperty("java.class.path")), "automata-evidence-worker",
                Path.of(System.getProperty("java.home"), "bin", "java"), "automata-evidence-jre"));
        assertNull(runtime.validate(artifact(source, ArtifactState.GENERATED)).toCompletableFuture().get());
        for (var fixture : cases) {
            Map<String, Object> input = Map.of("definition", PayloadJson.write(value(
                    fixture.get("definition"))), "input", fixture.get("input"), "traceMode", "full");
            var request = new ProgramRequest(UUID.randomUUID(), "finite-automata-evidence", input, Map.of());
            var actual = (Map<String, Object>) runtime.execute(TestAdmission.of(
                    artifact(source, ArtifactState.ACTIVE)), request).toCompletableFuture().get();
            var expected = (Map<String, Object>) fixture.get("expected");
            expected.forEach((key, value) -> assertEquals(value(value),
                    value(actual.get(key)), fixture.get("name") + ": " + key));
            PayloadLimits.DEFAULTS.enforce(value(actual));
        }
        var first = cases.getFirst();
        var invalidInput = Map.of("definition", PayloadJson.write(value(first.get("definition"))),
                "input", List.of("outside-alphabet"));
        var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> runtime.execute(
                TestAdmission.of(artifact(source, ArtifactState.ACTIVE)),
                new ProgramRequest(UUID.randomUUID(), "invalid-token", invalidInput, Map.of()))
                .toCompletableFuture().get());
        assertTrue(failure.getCause().getMessage().contains("FA_SYMBOL"), failure.toString());
        assertEquals(cases.size() + 2, supervisor.realSubprocessSpawns);
    }

    private static PayloadValue value(Object input) {
        return PayloadValue.fromJava(input, PayloadLimits.DEFAULTS);
    }

    private static Path repositoryRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isRegularFile(path.resolve("docs/examples/finite-automata/program.js"))) {
            path = path.getParent();
        }
        if (path == null) throw new AssertionError("public automata evidence not found");
        return path;
    }

    private static GeneratedArtifact artifact(String source, ArtifactState state) {
        Instant now = Instant.now();
        return new GeneratedArtifact("automata-evidence", "javascript",
                ProgramArtifactIdentity.sha256("javascript", source), source, state, 1, now, now, Map.of());
    }
}
