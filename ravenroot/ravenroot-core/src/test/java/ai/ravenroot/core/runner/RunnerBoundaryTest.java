package ai.ravenroot.core.runner;

import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.runner.*;
import ai.ravenroot.api.security.*;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RunnerBoundaryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static RunnerPolicy policy() {
        return new RunnerPolicy(Set.of(RunnerPolicy.Capability.WORKSPACE_READ), Set.of(), Set.of(), Set.of(), Set.of(),
                new RunnerPolicy.Limits(Duration.ofMinutes(1), 64_000_000, 16, 1_000_000, 32, 16, 4096));
    }
    private static AgentDefinition definition() {
        var command = new AgentCommand("implement", false, policy(), Set.of("completed", "blocked"));
        return new AgentDefinition(new AgentDefinition.Reference("tenant", "developer", 1), "Review the input",
                "reference", "reference", Map.of("implement", command), Set.of(), Set.of(), policy(), Duration.ofDays(1), "object");
    }
    private static RunnerRegistration registration() {
        return new RunnerRegistration(1, "tenant", "runner", "local-container-v1", Set.of(), policy());
    }
    private static RunnerJob job() {
        return RunnerJob.accept(new RunnerJobIdentity(new ExecutionKey("tenant", UUID.randomUUID()), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), definition(), "implement", policy(), registration(),
                OpaquePayload.of("{}".getBytes(), "application/json"), CLOCK.instant(), CLOCK.instant().plusSeconds(60))
                .claim("runner", CLOCK.instant(), Duration.ofSeconds(30));
    }

    @Test void catalogJsonRoundTripsWithoutGrantingIngressTenantOrIdentity() {
        var json = RunnerJson.definition(definition());
        var decoded = RunnerJson.definition("tenant", RunnerJson.read(RunnerJson.write(json)));
        assertEquals(definition(), decoded);
        assertEquals(registration(), RunnerJson.registration("tenant", RunnerJson.read(RunnerJson.write(RunnerJson.registration(registration())))));
        assertThrows(IllegalArgumentException.class, () -> RunnerJson.policy(Map.of("capabilities", List.of("ALL"))));
    }

    @Test void artifactsAreBoundedScopedDigestVerifiedAndNeverPaths(@TempDir Path directory) throws Exception {
        var artifacts = new RunnerArtifactStore(directory.toRealPath()); var job = job();
        var artifact = artifacts.put(job, RunnerArtifact.Kind.LOG, new ByteArrayInputStream("hello".getBytes()));
        assertEquals("hello", new String(artifacts.open(job, artifact).readAllBytes()));
        assertThrows(IllegalArgumentException.class, () -> artifacts.verify(job(), artifact));
        assertThrows(IllegalArgumentException.class, () -> artifacts.put(job, RunnerArtifact.Kind.LOG, new ByteArrayInputStream(new byte[17])));
        try (var files = Files.walk(directory)) {
            Path blob = files.filter(value -> value.getFileName().toString().endsWith(".blob")).findFirst().orElseThrow();
            Files.writeString(blob, "wrong");
        }
        assertThrows(IllegalArgumentException.class, () -> artifacts.verify(job, artifact));
    }

    @Test void attenuatedWriteCommandStillGetsReadOnlyContainerAndNoHostMount(@TempDir Path directory) throws Exception {
        String image = "sha256:" + "a".repeat(64);
        try (var runner = new LocalContainerRunner(registration(), Path.of("/usr/bin/true"), directory.toRealPath(),
                Map.of("reference", image), CLOCK)) {
            var assignment = new RunnerAssignment(1, UUID.randomUUID(), job());
            var command = runner.containerCommand(assignment, image);
            assertTrue(command.contains("--read-only")); assertTrue(command.contains("--network=none"));
            assertTrue(command.contains("--cap-drop=ALL")); assertTrue(command.contains("--user=65532:65532"));
            assertFalse(command.stream().anyMatch(value -> value.contains("mount") || value.contains("volume") || value.equals("--privileged")));
            assertThrows(IllegalArgumentException.class, () -> runner.containerCommand(assignment, "untrusted:latest"));
            assertThrows(IllegalArgumentException.class, () -> new RunnerAssignment(2, assignment.workspaceId(), assignment.job()));
        }
    }

    @Test void workloadsCannotApproveAndIssuerIsPartOfRunnerIdentity(@TempDir Path directory) throws Exception {
        try (var store = new InMemoryExecutionStore(CLOCK)) {
            var service = new RunnerJobService(store, CLOCK, List.of(), List.of(), Map.of("tenant", policy()));
            var control = new AuthorizedRunnerControl(service, new DefaultAuthorizationService(ignored -> { }),
                    "trusted", new RunnerArtifactStore(directory.toRealPath()), CLOCK);
            var runner = new RequestContext("request", "runner", PrincipalType.WORKLOAD, "trusted", "tenant",
                    Set.of(Role.TENANT_ADMIN), Set.of("ravenroot.runner.dispatch", "ravenroot.runner.admin"));
            var registered = control.register(runner, registration());
            assertFalse(registered.approved());
            assertThrows(AuthorizationDeniedException.class, () -> control.save(runner, registered, 1));
            var wrongIssuer = new RequestContext("request", "runner", PrincipalType.WORKLOAD, "other", "tenant",
                    runner.roles(), runner.scopes());
            assertThrows(AuthorizationDeniedException.class, () -> control.register(wrongIssuer, registration()));
            var user = new RequestContext("request", "operator", PrincipalType.USER, "trusted", "tenant",
                    Set.of(Role.TENANT_ADMIN), Set.of("ravenroot.runner.admin"));
            var approved = new GovernedRunnerResource(registered.kind(), "tenant", registered.name(), 1, true,
                    registered.document(), 0, "caller-spoofed-actor", CLOCK.instant());
            assertEquals("trusted|USER|operator", control.save(user, approved, 1).actor());
        }
    }
}
