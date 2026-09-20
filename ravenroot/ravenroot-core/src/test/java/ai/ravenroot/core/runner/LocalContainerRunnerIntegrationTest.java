package ai.ravenroot.core.runner;

import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.runner.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Explicit daemon contract. The operator supplies an already installed immutable Linux image. */
class LocalContainerRunnerIntegrationTest {
    @Test void actualReadOnlyContainerCannotMutateAndReconcilesWithoutRedispatch(@TempDir Path directory) throws Exception {
        String base = System.getProperty("ravenroot.runner.testBaseImage", "");
        assumeTrue(base.matches("[a-zA-Z0-9./:_-]+@sha256:[0-9a-f]{64}"), "set ravenroot.runner.testBaseImage to a pinned Linux repository digest");
        String docker = System.getProperty("ravenroot.runner.testDocker", "/usr/local/bin/docker");
        String image = command(docker, "build", "--quiet", "--pull=false", "--build-arg", "BASE_IMAGE=" + base,
                "src/test/resources/runner-container").trim();
        assertTrue(image.matches("sha256:[0-9a-f]{64}"), image);
        var clock = Clock.systemUTC();
        var policy = new RunnerPolicy(Set.of(RunnerPolicy.Capability.WORKSPACE_READ), Set.of(), Set.of(), Set.of(), Set.of(),
                new RunnerPolicy.Limits(Duration.ofMinutes(2), 64_000_000, 4, 16_000_000, 4096, 1024, 4096));
        var definition = new AgentDefinition(new AgentDefinition.Reference("tenant", "reader", 1), "Read the workspace",
                "probe", "none", Map.of("read", new AgentCommand("read", true, policy, Set.of("answered", "blocked"))),
                Set.of(), Set.of(), policy, Duration.ZERO, "object");
        var registration = new RunnerRegistration(1, "tenant", "local", "local-container-v1", Set.of(), policy);
        var identity = new RunnerJobIdentity(new ExecutionKey("tenant", UUID.randomUUID()), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        var job = RunnerJob.accept(identity, definition, "read", policy, registration,
                OpaquePayload.of("{\"command\":\"implement\",\"path\":\"/workspace/escape\"}".getBytes(), "application/json"),
                clock.instant(), clock.instant().plusSeconds(120)).claim("local", clock.instant(), Duration.ofSeconds(90));
        var assignment = new RunnerAssignment(1, UUID.randomUUID(), job);
        Throwable primary = null;
        try (var driver = new LocalContainerRunner(registration, Path.of(docker), directory.toRealPath(), Map.of("probe", image), clock,
                (owned, kind, bytes) -> new RunnerArtifact(owned.job().identity(), UUID.randomUUID(), kind,
                        HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.length))) {
            var result = driver.execute(assignment).toCompletableFuture().get(45, TimeUnit.SECONDS);
            assertEquals("answered", result.outcome());
            assertTrue(new String(result.payload().bytes()).contains("true"));
            assertThrows(Exception.class, () -> driver.execute(assignment).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(result, driver.reconcile(assignment).toCompletableFuture().get(15, TimeUnit.SECONDS));
            driver.cancel(assignment).toCompletableFuture().get(15, TimeUnit.SECONDS);
            driver.release(new RunnerWorkspaceRelease(1, identity.execution(), assignment.workspaceId(), "local",
                    Set.of(identity.runnerJobId()), clock.instant())).toCompletableFuture().get(45, TimeUnit.SECONDS);
            assertFalse(java.nio.file.Files.exists(directory.resolve(assignment.workspaceId().toString()).resolve("snapshot.json")));
        } catch (Exception | AssertionError failure) { primary = failure; throw failure; }
        finally {
            // Only task-created, exact resources. Never prune shared images or volumes.
            if (primary != null) try { command(docker, "rm", "--force", "--volumes", "ravenroot-job-" + identity.runnerJobId()); }
            catch (Exception | AssertionError cleanup) { primary.addSuppressed(cleanup); }
            try { command(docker, "image", "rm", "--no-prune", image); }
            catch (Exception | AssertionError cleanup) { if (primary != null) primary.addSuppressed(cleanup); else throw cleanup; }
        }
    }
    private static String command(String... args) throws Exception {
        var process = new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            byte[] output = process.getInputStream().readNBytes(8193);
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "bounded Docker operation");
            assertEquals(0, process.exitValue(), "Docker operation refused");
            assertTrue(output.length <= 8192);
            return new String(output, java.nio.charset.StandardCharsets.UTF_8);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
