package ai.ravenroot.api.runner;

import ai.ravenroot.api.persistence.ExecutionKey;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.Executors;

import static ai.ravenroot.api.runner.RunnerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcessWorkspaceTest {
    @Test
    void sequentialSpecialistsAndLaterTraversalShareOneWorkspaceOnlyAfterQuiescence() {
        var execution = new ExecutionKey(TENANT, UUID.randomUUID());
        var workspaceId = UUID.randomUUID();
        var workspace = ProcessWorkspace.create(execution, workspaceId, RUNNER);
        var traversal = UUID.randomUUID();
        var commands = new String[] {"plan", "read", "implement", "test", "review", "remediate"};
        for (String command : commands) {
            boolean readOnly = command.equals("plan") || command.equals("read") || command.equals("review");
            var definition = definition(TENANT, command, readOnly);
            var identity = new RunnerJobIdentity(execution, traversal, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            var job = RunnerJob.accept(identity, definition, command, policy(), runner(), EMPTY,
                    NOW, NOW.plusSeconds(600));
            var active = workspace.admit(job).claim(RUNNER, NOW, TTL);
            assertThrows(IllegalStateException.class, () -> active.admit(job(identity(execution))));
            workspace = active.complete(RUNNER, 1, result(), NOW);
            assertEquals(workspaceId, workspace.workspaceId());
        }
        var nextTraversal = identity(execution);
        assertNotEquals(traversal, nextTraversal.traversalId());
        var resumed = workspace.admit(job(nextTraversal));
        assertEquals(workspaceId, resumed.workspaceId());
        assertEquals(nextTraversal, resumed.currentJob().identity());
    }

    @Test
    void unknownAndCancellingJobsKeepTheirOwnershipBarrier() {
        var job = job();
        var workspace = ProcessWorkspace.create(job.identity().execution(), UUID.randomUUID(), RUNNER)
                .admit(job).claim(RUNNER, NOW, TTL);
        var next = job(identity(job.identity().execution()));
        var cancelling = workspace.cancel(NOW.plusSeconds(1));
        assertThrows(IllegalStateException.class, () -> cancelling.admit(next));
        var unknown = cancelling.reconcileLiveness(NOW.plusSeconds(30));
        assertThrows(IllegalStateException.class, () -> unknown.admit(next));
        var reconciling = unknown.beginReconciliation(RUNNER, NOW.plusSeconds(31), TTL);
        assertThrows(IllegalStateException.class, () -> reconciling.admit(next));
        var terminal = reconciling.complete(RUNNER, 2, result(), NOW.plusSeconds(32));
        assertEquals(next, terminal.admit(next).currentJob());
    }

    @Test
    void tenantProcessAndPlacementCannotBeChangedByTheNextJob() {
        var job = job();
        var workspace = ProcessWorkspace.create(job.identity().execution(), UUID.randomUUID(), RUNNER);
        assertThrows(IllegalArgumentException.class, () -> workspace.admit(job()));
        var otherTenant = ProcessWorkspace.create(new ExecutionKey("tenant-b", job.identity().execution().processInstanceId()),
                UUID.randomUUID(), RUNNER);
        assertThrows(IllegalArgumentException.class, () -> otherTenant.admit(job));
        var otherPlacement = ProcessWorkspace.create(job.identity().execution(), UUID.randomUUID(), "runner-b");
        assertThrows(IllegalArgumentException.class, () -> otherPlacement.admit(job));
        assertThrows(IllegalArgumentException.class, () -> workspace.admit(job.claim(RUNNER, NOW, TTL)));
    }

    @Test
    void oneDefinitionCanServeIndependentProcessStateConcurrently() throws Exception {
        var definition = definition();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 32).mapToObj(index -> workers.submit(() -> {
                var identity = identity();
                var job = RunnerJob.accept(identity, definition, "implement", policy(), runner(), EMPTY,
                        NOW, NOW.plusSeconds(600));
                return ProcessWorkspace.create(identity.execution(), UUID.randomUUID(), RUNNER)
                        .admit(job).claim(RUNNER, NOW, TTL).complete(RUNNER, 1, result(), NOW.plusSeconds(1));
            })).toList();
            var workspaces = new java.util.HashSet<UUID>();
            var processes = new java.util.HashSet<ExecutionKey>();
            for (var future : futures) {
                var workspace = future.get();
                assertTrue(workspaces.add(workspace.workspaceId()));
                assertTrue(processes.add(workspace.execution()));
                assertEquals(definition.reference(), workspace.currentJob().definition().reference());
                assertEquals(RunnerJob.State.COMPLETED, workspace.currentJob().state());
            }
        }
    }

    @Test
    void noOpReplaysDoNotAdvanceWorkspaceRevision() {
        var job = job();
        var report = result();
        var workspace = ProcessWorkspace.create(job.identity().execution(), UUID.randomUUID(), RUNNER)
                .admit(job).claim(RUNNER, NOW, TTL).complete(RUNNER, 1, report, NOW.plusSeconds(1));
        assertSame(workspace, workspace.complete(RUNNER, 1, report, NOW.plusSeconds(2)));
        assertSame(workspace, workspace.cancel(NOW.plusSeconds(2)));
        assertThrows(IllegalStateException.class, () -> workspace.admit(job));
    }
}
