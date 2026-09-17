package ai.ravenroot.api.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static ai.ravenroot.api.runner.RunnerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceResourceTest {
    static WorkspaceProfile profile(WorkspaceProfile.RuntimeLifecycle lifecycle) {
        return new WorkspaceProfile(new AgentDefinition.Reference(TENANT, "development", 3),
                WorkspaceProfile.Scope.PROCESS_INSTANCE, lifecycle, "development", "runtime", policy(),
                new WorkspaceProfile.Capacity(1, 5, 19, 50_000_000, 37, 1024, WorkspaceProfile.Admission.QUEUE),
                Duration.ofDays(2), WorkspaceProfile.CompletionPolicy.REQUIRE_CLOSED, Set.of("specialist"));
    }
    static WorkspaceResource resource(WorkspaceProfile.RuntimeLifecycle lifecycle) {
        return new WorkspaceResource("source-tree", UUID.randomUUID(), profile(lifecycle), RUNNER,
                WorkspaceResource.State.UNMATERIALIZED, null, null, false, NOW);
    }
    @ParameterizedTest @EnumSource(WorkspaceProfile.RuntimeLifecycle.class)
    void lifecycleIsIndependentOfRuntimeReuseAndAbortIsSticky(WorkspaceProfile.RuntimeLifecycle lifecycle) {
        var fresh = resource(lifecycle);
        assertFalse(fresh.admitsAgent());
        var opening = fresh.request("open", false, NOW);
        assertEquals(WorkspaceResource.State.OPENING, opening.state());
        var ready = opening.observed("open", "runtime-a", null, NOW);
        assertTrue(ready.admitsAgent());
        assertThrows(IllegalStateException.class, () -> ready.request("close", true, NOW));
        assertThrows(IllegalStateException.class, () -> ready.request("checkpoint", true, NOW));
        var aborting = ready.request("abort", true, NOW);
        assertTrue(aborting.stopRequested());
        assertFalse(aborting.admitsAgent());
        assertFalse(aborting.observed("open", ready.runtimeId(), null, NOW).admitsAgent(), "late open cannot undo stop");
        var aborted = aborting.observed("abort", ready.runtimeId(), null, NOW);
        assertEquals(WorkspaceResource.State.ABORTED, aborted.state());
        assertThrows(IllegalStateException.class, () -> aborted.request("open", false, NOW));
        assertEquals(WorkspaceResource.State.CLOSED, ready.request("close", false, NOW).observed("close", ready.runtimeId(), null, NOW).state());
    }
    @Test void persistentRuntimeIdentityCannotBeSilentlyReplaced() {
        var ready = resource(WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE).request("open", false, NOW)
                .observed("open", "runtime-a", null, NOW);
        assertThrows(IllegalStateException.class, () -> ready.observed("inspect", "runtime-b", null, NOW));
    }
    @ParameterizedTest @EnumSource(WorkspaceProfile.RuntimeLifecycle.class)
    void ephemeralFilesystemIdentityChangesPerInvocationForBothRuntimePolicies(WorkspaceProfile.RuntimeLifecycle lifecycle) {
        var original = profile(lifecycle);
        var ephemeral = new WorkspaceProfile(original.reference(), WorkspaceProfile.Scope.EPHEMERAL, lifecycle, original.runnerPool(), original.runtimeProfile(),
                original.policy(), original.capacity(), original.retention(), original.completionPolicy(), original.allowedAgents());
        var resource = new WorkspaceResource("temporary", UUID.randomUUID(), ephemeral, RUNNER, WorkspaceResource.State.READY, null, null, false, NOW);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertNotEquals(resource.workspaceId(), resource.invocationWorkspaceId(first, null));
        assertNotEquals(resource.invocationWorkspaceId(first, null), resource.invocationWorkspaceId(second, null));
        assertEquals(resource.invocationWorkspaceId(first, null), resource.invocationWorkspaceId(first, null));
        assertEquals(resource.workspaceId(), resource.invocationWorkspaceId(first, "inspect"));
        assertNotEquals(WorkspaceResource.namedWorkspaceId("tenant-a", "repository"), WorkspaceResource.namedWorkspaceId("tenant-b", "repository"));
    }
    @Test void multipleResourcesAndProfileAuthorityRoundTripWithoutConflatingIdentity() {
        var first = resource(WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE);
        var second = new WorkspaceResource("documentation", UUID.randomUUID(), profile(WorkspaceProfile.RuntimeLifecycle.PER_INVOCATION),
                "runner-b", WorkspaceResource.State.READY, "runtime-b", "sha256:" + "a".repeat(64), false, NOW);
        var job = job();
        var state = new RunnerWorkspaceState(job.identity().execution(), UUID.randomUUID(), RUNNER, Map.of(), null,
                Map.of(first.nodeId(), first, second.nodeId(), second));
        assertEquals(state, RunnerCodec.workspace(RunnerCodec.workspace(state)));
        assertEquals(first.profile(), RunnerCodec.workspaceProfile(RunnerCodec.workspaceProfile(first.profile())));
        var assignment = new RunnerAssignment(1, first.workspaceId(), job, first, null);
        var restored = RunnerCodec.assignment(RunnerCodec.assignment(assignment));
        assertEquals(assignment.workspace(), restored.workspace());
        assertEquals(assignment.job().identity(), restored.job().identity());
        assertEquals(assignment.job().definition(), restored.job().definition());
        assertArrayEquals(RunnerCodec.assignment(assignment), RunnerCodec.assignment(restored));
    }
    @Test void stoppingOneWorkspaceDoesNotCancelAnotherWorkspaceInTheSameProcess() {
        var firstJob = job();
        var secondJob = job(identity(firstJob.identity().execution()));
        var first = resource(WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE).request("open", false, NOW)
                .observed("open", "runtime-a", null, NOW);
        var second = new WorkspaceResource("documentation", UUID.randomUUID(), first.profile(), RUNNER,
                WorkspaceResource.State.READY, "runtime-b", null, false, NOW);
        var state = new RunnerWorkspaceState(firstJob.identity().execution(), first.workspaceId(), RUNNER,
                Map.of(firstJob.identity().runnerJobId(), new RunnerWorkspaceState.Entry(firstJob, EMPTY, false, first.nodeId(), null),
                       secondJob.identity().runnerJobId(), new RunnerWorkspaceState.Entry(secondJob, EMPTY, false, second.nodeId(), null)),
                null, Map.of(first.nodeId(), first, second.nodeId(), second));
        var graph = new ai.ravenroot.api.application.ProcessInstance(state.execution().processInstanceId(),
                ai.ravenroot.api.application.ProcessInstanceStatus.RUNNING, Map.of());
        var stopped = RunnerWorkspaceState.apply(state.execution(), state,
                new RunnerJobOperation.WorkspaceStop(UUID.randomUUID(), first.nodeId()), graph, NOW);
        assertEquals(RunnerJob.State.CANCELLED, stopped.jobs().get(firstJob.identity().runnerJobId()).job().state());
        assertEquals(RunnerJob.State.QUEUED, stopped.jobs().get(secondJob.identity().runnerJobId()).job().state());
        assertTrue(stopped.workspaces().get(first.nodeId()).stopRequested());
        assertTrue(stopped.workspaces().get(second.nodeId()).admitsAgent());
        assertThrows(IllegalStateException.class, () -> RunnerWorkspaceState.apply(state.execution(), stopped,
                new RunnerJobOperation.WorkspaceStopped(UUID.randomUUID(), first.nodeId(), second.workspaceId(), RUNNER), graph, NOW));
        var confirmed = RunnerWorkspaceState.apply(state.execution(), stopped,
                new RunnerJobOperation.WorkspaceStopped(UUID.randomUUID(), first.nodeId(), first.workspaceId(), RUNNER), graph, NOW);
        assertEquals(WorkspaceResource.State.ABORTED, confirmed.workspaces().get(first.nodeId()).state());
        assertEquals(second, confirmed.workspaces().get(second.nodeId()));
    }
    @Test void configuredReaderCapacityAndWriterHandoffAreClaimTimeFences() {
        var key = identity().execution();
        var resource = resource(WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE).request("open", false, NOW)
                .observed("open", "runtime", null, NOW);
        var entries = new java.util.LinkedHashMap<UUID, RunnerWorkspaceState.Entry>();
        for (int i = 0; i < 6; i++) {
            var reader = RunnerJob.accept(identity(key), definition(TENANT, "read", true), "read", policy(), runner(), EMPTY, NOW, NOW.plusSeconds(600));
            entries.put(reader.identity().runnerJobId(), new RunnerWorkspaceState.Entry(reader, EMPTY, false, resource.nodeId(), null));
        }
        var writer = job(identity(key));
        entries.put(writer.identity().runnerJobId(), new RunnerWorkspaceState.Entry(writer, EMPTY, false, resource.nodeId(), null));
        var state = new RunnerWorkspaceState(key, resource.workspaceId(), RUNNER, entries, null, Map.of(resource.nodeId(), resource));
        var graph = new ai.ravenroot.api.application.ProcessInstance(key.processInstanceId(), ai.ravenroot.api.application.ProcessInstanceStatus.RUNNING, Map.of());
        var ids = new java.util.ArrayList<>(entries.keySet());
        for (int i = 0; i < 5; i++) state = RunnerWorkspaceState.apply(key, state,
                new RunnerJobOperation.Claim(ids.get(i), RUNNER, TTL), graph, NOW);
        var full = state;
        assertThrows(IllegalStateException.class, () -> RunnerWorkspaceState.apply(key, full,
                new RunnerJobOperation.Claim(ids.get(5), RUNNER, TTL), graph, NOW));
        assertThrows(IllegalStateException.class, () -> RunnerWorkspaceState.apply(key, full,
                new RunnerJobOperation.Claim(writer.identity().runnerJobId(), RUNNER, TTL), graph, NOW));
        assertEquals(5, full.jobs().values().stream().filter(value -> value.job().state() == RunnerJob.State.CLAIMED).count());
    }
    @Test void cleanupReservationRetainsCapacityUntilExactWorkerAcknowledgement() {
        var job = job().cancel(NOW);
        var key = job.identity().execution();
        var resource = resource(WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE).request("abort", false, NOW).observed("abort", null, null, NOW);
        var state = new RunnerWorkspaceState(key, resource.workspaceId(), RUNNER,
                Map.of(job.identity().runnerJobId(), new RunnerWorkspaceState.Entry(job, EMPTY, false, resource.nodeId(), null)),
                NOW, Map.of(resource.nodeId(), resource));
        var graph = new ai.ravenroot.api.application.ProcessInstance(key.processInstanceId(), ai.ravenroot.api.application.ProcessInstanceStatus.COMPLETED, Map.of());
        var request = new RunnerJobOperation.WorkspaceRelease(UUID.randomUUID(), resource.nodeId(), resource.workspaceId(), RUNNER);
        assertThrows(IllegalStateException.class, () -> RunnerWorkspaceState.apply(key, state, request, graph, NOW));
        var later = NOW.plus(Duration.ofDays(8));
        var reserved = RunnerWorkspaceState.apply(key, state, request, graph, later);
        assertEquals(WorkspaceResource.State.RELEASING, reserved.workspaces().get(resource.nodeId()).state());
        assertArrayEquals(RunnerCodec.workspace(reserved), RunnerCodec.workspace(RunnerCodec.workspace(RunnerCodec.workspace(reserved))));
        assertThrows(IllegalStateException.class, () -> RunnerWorkspaceState.apply(key, reserved,
                new RunnerJobOperation.WorkspaceReleased(UUID.randomUUID(), resource.nodeId(), resource.workspaceId(), "different-worker"), graph, later));
        var released = RunnerWorkspaceState.apply(key, reserved,
                new RunnerJobOperation.WorkspaceReleased(UUID.randomUUID(), resource.nodeId(), resource.workspaceId(), RUNNER), graph, later);
        assertEquals(WorkspaceResource.State.RELEASED, released.workspaces().get(resource.nodeId()).state());
    }
}
