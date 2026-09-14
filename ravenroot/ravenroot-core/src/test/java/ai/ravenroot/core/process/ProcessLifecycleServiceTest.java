package ai.ravenroot.core.process;

import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.RavenrootApplication;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ProcessLifecycleServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir Path directory;

    @Test
    void tenantJournalPagingUsesOffsetsNotPerProcessSequences() {
        try (var store = new ai.ravenroot.core.persistence.InMemoryExecutionStore(CLOCK)) {
            var application = (RavenrootApplication) Proxy.newProxyInstance(RavenrootApplication.class.getClassLoader(),
                    new Class<?>[]{RavenrootApplication.class}, (proxy, method, arguments) -> false);
            for (int process = 0; process < 2; process++) {
                var key = new ExecutionKey("tenant-a", UUID.randomUUID());
                var batch = ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                        .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED, Map.of()),
                                new GraphVersionPin("graph-v1")));
                for (int event = 0; event < 600; event++) batch.publish(ai.ravenroot.api.persistence.EventEnvelope.of(
                        UUID.randomUUID(), "tenant-a", "PROCESS_PAUSE", key.processInstanceId(), key.processInstanceId(), null, null,
                        null, "fixture", "graph-v1", CLOCK.instant(), ai.ravenroot.api.persistence.OpaquePayload.empty("application/json")));
                store.apply(batch.build()).toCompletableFuture().join();
            }
            var key = new ExecutionKey("tenant-a", UUID.randomUUID());
            store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                    .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED, Map.of()),
                            new GraphVersionPin("graph-v1"))).build()).toCompletableFuture().join();
            var lifecycle = new ProcessLifecycleService(store, application, null, CLOCK);
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
                assertEquals(ProcessLifecycleService.Code.APPLIED, lifecycle.command("tenant-a", key.processInstanceId(),
                        ProcessLifecycleService.Command.PAUSE, store.load(key).toCompletableFuture().join().revision(), "target-pause", "").code());
                assertEquals(ProcessLifecycleService.State.PAUSED, lifecycle.currentState(key));
                assertFalse(lifecycle.admitsReentry("tenant-a", key.processInstanceId()));
            });
        }
    }

    @Test
    void commandIsDurableReplaySafeGenerationFencedAndCoversEveryTraversal() {
        Path database = directory.resolve("process-lifecycle.db");
        var calls = new AtomicInteger();
        RavenrootApplication application = (RavenrootApplication) Proxy.newProxyInstance(
                RavenrootApplication.class.getClassLoader(), new Class<?>[]{RavenrootApplication.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "pauseTraversal", "resumeTraversal", "cancelTraversal" -> {
                        calls.incrementAndGet();
                        yield true;
                    }
                    case "executionPaused" -> true;
                    case "stopProcessInvocations" -> 2;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        UUID processId = UUID.randomUUID();
        long generation;
        try (var store = new SqliteExecutionStore(database, CLOCK)) {
            create(store, processId);
            generation = store.findProcessInstance(new ExecutionKey("tenant-a", processId))
                    .toCompletableFuture().join().orElseThrow().revision();
            var service = new ProcessLifecycleService(store, application, null, CLOCK);
            var paused = service.command("tenant-a", processId, ProcessLifecycleService.Command.PAUSE,
                    generation, "pause-command", "investigate");
            assertEquals(ProcessLifecycleService.Code.APPLIED, paused.code());
            assertEquals(2, paused.traversals().size());
            assertEquals(ProcessLifecycleService.State.PAUSED, service.currentState(
                    new ExecutionKey("tenant-a", processId)));
            assertFalse(service.admitsReentry("tenant-a", processId));
            assertEquals(ProcessLifecycleService.Code.STALE_GENERATION,
                    service.command("tenant-a", processId, ProcessLifecycleService.Command.RESUME,
                            generation, "different-command", "").code());
        }
        try (var reopened = new SqliteExecutionStore(database, CLOCK)) {
            var service = new ProcessLifecycleService(reopened, application, null, CLOCK);
            var replay = service.command("tenant-a", processId, ProcessLifecycleService.Command.PAUSE,
                    generation, "pause-command", "investigate");
            assertEquals(ProcessLifecycleService.Code.REPLAYED, replay.code());
            assertEquals(4, calls.get(), "a replay re-converges both traversal effects after restart");
            long next = reopened.findProcessInstance(new ExecutionKey("tenant-a", processId))
                    .toCompletableFuture().join().orElseThrow().revision();
            assertEquals(ProcessLifecycleService.Code.APPLIED,
                    service.command("tenant-a", processId, ProcessLifecycleService.Command.DRAIN,
                            next, "drain-command", "finish accepted work").code());
            assertTrue(service.admitsReentry("tenant-a", processId));
        }
    }

    private static void create(SqliteExecutionStore store, UUID processId) {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var process = new ProcessInstance(processId, ProcessInstanceStatus.ACCEPTED, Map.of());
        store.apply(ExecutionBatch.to(new ExecutionKey("tenant-a", processId))
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(process, new GraphVersionPin("graph-v1")))
                .apply(new ExecutionTransition.TraversalAdded(
                        new Traversal(first, "start", TraversalStatus.ACCEPTED, Map.of())))
                .apply(new ExecutionTransition.TraversalTransitioned(first, TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalAdded(
                        new Traversal(second, "reentry", TraversalStatus.ACCEPTED, Map.of())))
                .apply(new ExecutionTransition.TraversalTransitioned(second, TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build()).toCompletableFuture().join();
    }
}
