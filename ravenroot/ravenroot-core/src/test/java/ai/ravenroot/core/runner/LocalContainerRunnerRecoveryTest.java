package ai.ravenroot.core.runner;

import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.runner.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Durable driver receipts; the local CLI fixture is not native containment evidence. */
class LocalContainerRunnerRecoveryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String IMAGE = "sha256:" + "a".repeat(64);
    private static final String RUNTIME = "b".repeat(64);
    private static final RunnerPolicy POLICY = new RunnerPolicy(Set.of(RunnerPolicy.Capability.WORKSPACE_READ),
            Set.of(), Set.of(), Set.of(), Set.of(),
            new RunnerPolicy.Limits(Duration.ofMinutes(1), 64_000_000, 16, 1_000_000, 4096, 4096, 4096));
    private static final RunnerRegistration REGISTRATION = new RunnerRegistration(1, "tenant", "runner",
            "local-container-v1", Set.of(), POLICY);

    private static RunnerAssignment open(WorkspaceProfile.RuntimeLifecycle lifecycle) {
        var command = new AgentCommand("open", false, POLICY, Set.of("ready"));
        var definition = new AgentDefinition(new AgentDefinition.Reference("tenant", "workspace", 1), "Open Workspace",
                "fixture", "fixture", Map.of("open", command), Set.of(), Set.of(), POLICY, Duration.ZERO, "object");
        var job = RunnerJob.accept(new RunnerJobIdentity(new ExecutionKey("tenant", UUID.randomUUID()), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), definition, "open", POLICY, REGISTRATION,
                OpaquePayload.of("{}".getBytes(), "application/json"), CLOCK.instant(), CLOCK.instant().plusSeconds(60))
                .claim("runner", CLOCK.instant(), Duration.ofSeconds(30));
        var profile = new WorkspaceProfile(new AgentDefinition.Reference("tenant", "workspace", 1),
                WorkspaceProfile.Scope.PROCESS_INSTANCE, lifecycle, "pool", "fixture", POLICY,
                new WorkspaceProfile.Capacity(1, 2, 1, 1_000_000, 4, 8, WorkspaceProfile.Admission.QUEUE),
                Duration.ZERO, WorkspaceProfile.CompletionPolicy.REQUIRE_CLOSED, Set.of("workspace"));
        var resource = new WorkspaceResource("workspace", UUID.randomUUID(), profile, "runner",
                WorkspaceResource.State.OPENING, null, null, false, CLOCK.instant());
        return new RunnerAssignment(1, resource.workspaceId(), job, resource, "open");
    }

    private static Path docker(Path directory) throws Exception {
        Path script = directory.resolve("docker");
        // No daemon: implement only the lifecycle protocol and record every side effect.
        Files.writeString(script, """
                #!/bin/sh
                set -eu
                cd "$(dirname "$0")"
                printf '%s\n' "$*" >> calls
                case "$1" in
                  image) printf 'null\n' ;;
                  run) printf 'true' > running; printf '@RUNTIME@\n' ;;
                  inspect)
                    case "$2" in
                      '--format={{.Id}} {{.State.Running}}') printf '@RUNTIME@ %s\n' "$(cat running)" ;;
                      '--format={{.Id}}') printf '@RUNTIME@\n' ;;
                      '--format={{.State.Running}}') cat running ;;
                      *) exit 41 ;;
                    esac ;;
                  kill) printf 'false' > running ;;
                  *) exit 42 ;;
                esac
                """.replace("@RUNTIME@", RUNTIME));
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }

    private static LocalContainerRunner driver(Path directory, Path docker) throws Exception {
        return new LocalContainerRunner(REGISTRATION, docker, directory.toRealPath().resolve("state"), Map.of("fixture", IMAGE), CLOCK);
    }

    @Test void openReceiptReconcilesAfterRestartWithoutNativeDaemon(@TempDir Path directory) throws Exception {
        var assignment = open(WorkspaceProfile.RuntimeLifecycle.PER_INVOCATION);
        Path docker = docker(directory);
        RunnerResult result;
        try (var driver = driver(directory, docker)) {
            assertThrows(java.nio.channels.OverlappingFileLockException.class, () -> driver(directory, docker),
                    "restart cannot overlap the original supervisor's worker-state ownership");
            result = driver.execute(assignment).toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        try (var restarted = driver(directory, docker)) {
            assertEquals(result, restarted.reconcile(assignment).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertThrows(Exception.class, () -> restarted.execute(assignment).toCompletableFuture().get(5, TimeUnit.SECONDS));
        }
        assertFalse(Files.exists(directory.resolve("calls")), "receipt-only open/recovery needs no container");
    }

    @Test void refusedReplayCannotStopRetainedContainerOrChangeItsReceipt(@TempDir Path directory) throws Exception {
        var assignment = open(WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE);
        Path docker = docker(directory);
        RunnerResult result;
        try (var driver = driver(directory, docker)) {
            result = driver.execute(assignment).toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        String calls = Files.readString(directory.resolve("calls"));
        try (var restarted = driver(directory, docker)) {
            assertThrows(Exception.class, () -> restarted.execute(assignment).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(calls, Files.readString(directory.resolve("calls")), "refusing dispatch is not stop authority");
            assertEquals(result, restarted.reconcile(assignment).toCompletableFuture().get(5, TimeUnit.SECONDS));
        }
        assertEquals("true", Files.readString(directory.resolve("running")));
    }

    @Test void symlinkLockIsRefusedBeforeDispatchWithoutTouchingTarget(@TempDir Path directory) throws Exception {
        var assignment = open(WorkspaceProfile.RuntimeLifecycle.PER_INVOCATION);
        Path docker = docker(directory);
        Path workspace = Files.createDirectories(directory.resolve("state").resolve(assignment.workspaceId().toString()));
        Path target = directory.resolve("unrelated"); Files.writeString(target, "preserve");
        Files.createSymbolicLink(workspace.resolve("workspace.lock"), target);
        try (var driver = driver(directory, docker)) {
            assertThrows(Exception.class, () -> driver.execute(assignment).toCompletableFuture().get(5, TimeUnit.SECONDS));
        }
        assertEquals("preserve", Files.readString(target));
        assertFalse(Files.exists(workspace.resolve(assignment.job().identity().runnerJobId() + ".started")));
        assertFalse(Files.exists(directory.resolve("calls")));
    }
}
