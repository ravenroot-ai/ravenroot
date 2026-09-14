package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.runner.*;
import ai.ravenroot.api.security.*;
import ai.ravenroot.core.graph.*;
import ai.ravenroot.core.persistence.InMemoryGraphDefinitionStore;
import ai.ravenroot.core.process.ProcessLifecycleService;
import ai.ravenroot.core.runner.*;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class RunnerProcessLifecycleTest {
    @TempDir Path directory;
    static final SecurityContext ACTOR = new SecurityContext("request", "tenant", "operator", PrincipalType.USER, "test");
    static final RavenrootApplication APPLICATION = (RavenrootApplication) Proxy.newProxyInstance(
            RavenrootApplication.class.getClassLoader(), new Class<?>[]{RavenrootApplication.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "pauseTraversal", "resumeTraversal", "cancelTraversal", "executionPaused" -> false;
                case "stopProcessInvocations" -> 0;
                default -> throw new UnsupportedOperationException(method.getName());
            });
    static final String XML = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="k" for="node" attr.name="kind" attr.type="string"/>
              <key id="b" for="node" attr.name="behavior" attr.type="string"/>
              <key id="d" for="node" attr.name="agentDefinition" attr.type="string"/>
              <key id="r" for="node" attr.name="runner" attr.type="string"/>
              <key id="c" for="edge" attr.name="command" attr.type="string"/>
              <graph edgedefault="directed">
                <node id="start"><data key="k">START</data></node>
                <node id="error"><data key="k">ERROR</data></node>
                <node id="end"><data key="k">END</data></node>
                <node id="agent"><data key="k">BEHAVIOR</data><data key="b">workspace-agent</data><data key="d">specialist</data><data key="r">local</data></node>
                <edge source="start" target="agent"><data key="c">plan</data></edge>
                <edge source="agent" target="end"/>
              </graph>
            </graphml>
            """;

    @ParameterizedTest
    @EnumSource(value = ProcessLifecycleService.Command.class, names = {"PAUSE", "STOP"})
    void terminalReportAndOperatorResolutionStayParkedAcrossRestartUntilProcessResume(ProcessLifecycleService.Command hold) throws Exception {
        var clock = new TestClock();
        var key = new ExecutionKey("tenant", UUID.randomUUID());
        Path database = directory.resolve("hold.db");
        RunnerJobIdentity id;
        long heldRevision;
        var result = result();
        try (var graphs = new InMemoryGraphDefinitionStore(clock)) {
            try (var store = shortJournalStore(database, clock); var engine = new JoinTestEngine()) {
                var jobs = jobs(store, clock);
                id = park(store, graphs, engine, jobs, key);
                jobs.mutate(ACTOR, key, new RunnerJobOperation.Claim(id.runnerJobId(), "local", Duration.ofSeconds(30)));
                var lifecycle = new ProcessLifecycleService(store, APPLICATION, null, clock);
                assertEquals(ProcessLifecycleService.Code.APPLIED, command(lifecycle, store, key, hold, "hold").code());
                var journal = store.readJournal("tenant", 0, 100).toCompletableFuture().join();
                store.advanceOutboxCursor(store.outboxCursor("tenant", "test").toCompletableFuture().join(),
                        journal.getLast().journalOffset()).toCompletableFuture().join();
                clock.advance(2);
                assertEquals(journal.size(), store.compactJournal("tenant").toCompletableFuture().join());
                assertTrue(store.journalRetainedFrom("tenant").toCompletableFuture().join() > 1);
            }
            try (var store = shortJournalStore(database, clock); var engine = new JoinTestEngine()) {
                var jobs = jobs(store, clock);
                var freshKey = new ExecutionKey("tenant", UUID.randomUUID());
                var fresh = park(store, graphs, engine, jobs, freshKey);
                assertEquals(RunnerJob.State.CLAIMED, jobs.mutate(ACTOR, freshKey,
                        new RunnerJobOperation.Claim(fresh.runnerJobId(), "local", Duration.ofSeconds(30)))
                        .jobs().get(fresh.runnerJobId()).job().state());
                jobs.mutate(ACTOR, key, new RunnerJobOperation.Complete(id.runnerJobId(), "local", 1, result));
                jobs.mutate(ACTOR, key, new RunnerJobOperation.Complete(id.runnerJobId(), "local", 1, result));
                heldRevision = store.load(key).toCompletableFuture().join().revision();
                try (var executor = executor(jobs, graphs, engine)) {
                    executor.resume(key, id.runnerJobId()).toCompletableFuture().join();
                    assertEquals(heldRevision, store.load(key).toCompletableFuture().join().revision());
                    assertEquals(NodeAttemptStatus.WAITING, attempt(store, key, id).status());
                    jobs.mutate(ACTOR, key, new RunnerJobOperation.ContinuationUncertain(id.runnerJobId()));
                    long expected = store.load(key).toCompletableFuture().join().revision();
                    executor.resolve(ACTOR, key, id.runnerJobId(), expected, RunnerJobOperation.ContinuationResolution.RESUME);
                    executor.resume(key, id.runnerJobId()).toCompletableFuture().join();
                    assertEquals(NodeAttemptStatus.WAITING, attempt(store, key, id).status());
                }
            }
            try (var store = new SqliteExecutionStore(database, clock); var engine = new JoinTestEngine()) {
                var jobs = jobs(store, clock);
                var lifecycle = new ProcessLifecycleService(store, APPLICATION, null, clock);
                try (var executor = executor(jobs, graphs, engine);
                     var recovery = new RunnerRecoveryLoop(jobs, executor, Set.of("tenant"), clock)) {
                    executor.bindLifecycle(lifecycle);
                    executor.resume(key, id.runnerJobId()).toCompletableFuture().join();
                    assertEquals(0, engine.spawnCount());
                    assertFalse(lifecycle.admitsReentry("tenant", key.processInstanceId()));
                    long generation = store.load(key).toCompletableFuture().join().revision();
                    assertEquals(ProcessLifecycleService.Code.APPLIED,
                            lifecycle.command("tenant", key.processInstanceId(), ProcessLifecycleService.Command.RESUME,
                                    generation, "resume", "").code());
                    awaitTerminal(store, key);
                    assertEquals(ProcessInstanceStatus.COMPLETED, store.load(key).toCompletableFuture().join().state().status());
                    int spawned = engine.spawnCount();
                    executor.resume(key, id.runnerJobId()).toCompletableFuture().join();
                    recovery.sweep();
                    assertEquals(ProcessLifecycleService.Code.REPLAYED,
                            lifecycle.command("tenant", key.processInstanceId(), ProcessLifecycleService.Command.RESUME,
                                    generation, "resume", "").code());
                    assertEquals(ProcessLifecycleService.Code.REPLAYED,
                            lifecycle.command("tenant", key.processInstanceId(), hold, heldRevision - 2, "hold", "").code());
                    assertEquals(spawned, engine.spawnCount());
                    var restored = attempt(store, key, id);
                    assertEquals(id.attemptId(), restored.attemptId());
                    assertEquals(1, restored.ordinal());
                    assertEquals(NodeAttemptStatus.COMPLETED, restored.status());
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = RunnerJob.State.class, names = {"QUEUED", "CLAIMED", "UNKNOWN", "RECONCILING", "COMPLETED"})
    void cancellationConvergesDurableGraphAndJobsWithoutSuccessorsAcrossRestart(RunnerJob.State before) throws Exception {
        var clock = new TestClock();
        var key = new ExecutionKey("tenant", UUID.randomUUID());
        Path database = directory.resolve("cancel.db");
        RunnerJobIdentity id;
        Instant cancelledAt;
        long generation;
        var evidence = result();
        try (var graphs = new InMemoryGraphDefinitionStore(clock)) {
            try (var store = new SqliteExecutionStore(database, clock); var engine = new JoinTestEngine()) {
                var jobs = jobs(store, clock); id = park(store, graphs, engine, jobs, key);
                if (before != RunnerJob.State.QUEUED) jobs.mutate(ACTOR, key,
                        new RunnerJobOperation.Claim(id.runnerJobId(), "local", Duration.ofSeconds(10)));
                if (before == RunnerJob.State.UNKNOWN || before == RunnerJob.State.RECONCILING) {
                    clock.advance(11);
                    jobs.mutate(ACTOR, key, new RunnerJobOperation.Reconcile(id.runnerJobId()));
                }
                if (before == RunnerJob.State.RECONCILING) jobs.mutate(ACTOR, key,
                        new RunnerJobOperation.ReconcileReport(id.runnerJobId(), "local", Duration.ofSeconds(10)));
                if (before == RunnerJob.State.COMPLETED) {
                    jobs.mutate(ACTOR, key, new RunnerJobOperation.Complete(id.runnerJobId(), "local", 1, evidence));
                    jobs.mutate(ACTOR, key, new RunnerJobOperation.ContinuationUncertain(id.runnerJobId()));
                }
                var lifecycle = new ProcessLifecycleService(store, APPLICATION, null, clock);
                generation = store.load(key).toCompletableFuture().join().revision();
                assertEquals(ProcessLifecycleService.Code.APPLIED, command(lifecycle, store, key,
                        ProcessLifecycleService.Command.CANCEL, "cancel").code());
                cancelledAt = clock.instant();
                var process = store.load(key).toCompletableFuture().join().state();
                assertEquals(ProcessInstanceStatus.FAILED, process.status());
                assertEquals(ExecutionTerminationReason.CANCELLED, process.terminationReason());
                assertEquals(ExecutionTerminationReason.CANCELLED, process.traversals().get(id.traversalId()).terminationReason());
                var workspace = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow();
                assertEquals(cancelledAt, workspace.processTerminalAt());
                assertFalse(workspace.jobs().get(id.runnerJobId()).continuationUncertain());
                if (before == RunnerJob.State.COMPLETED) assertEquals(evidence, workspace.jobs().get(id.runnerJobId()).job().result());
                else assertEquals(RunnerJob.StopReason.CANCEL, workspace.jobs().get(id.runnerJobId()).job().stopReason());
            }
            try (var store = new SqliteExecutionStore(database, clock); var engine = new JoinTestEngine()) {
                var jobs = jobs(store, clock); var lifecycle = new ProcessLifecycleService(store, APPLICATION, null, clock);
                try (var executor = executor(jobs, graphs, engine);
                     var recovery = new RunnerRecoveryLoop(jobs, executor, Set.of("tenant"), clock)) {
                    executor.bindLifecycle(lifecycle);
                    assertEquals(ProcessLifecycleService.Code.REPLAYED, lifecycle.command("tenant", key.processInstanceId(),
                            ProcessLifecycleService.Command.CANCEL, generation, "cancel", "").code());
                    assertEquals(ProcessLifecycleService.Code.TERMINAL, command(lifecycle, store, key,
                            ProcessLifecycleService.Command.RESUME, "forbidden-resume").code());
                    var job = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow().jobs().get(id.runnerJobId()).job();
                    if (!job.state().terminal()) {
                        clock.advance(11);
                        recovery.sweep();
                        assertEquals(RunnerJob.State.UNKNOWN, store.loadRunnerWorkspace(key).toCompletableFuture().join()
                                .orElseThrow().jobs().get(id.runnerJobId()).job().state());
                        long oldFence = job.fence();
                        job = jobs.mutate(ACTOR, key, new RunnerJobOperation.ReconcileReport(id.runnerJobId(), "local", Duration.ofSeconds(30)))
                                .jobs().get(id.runnerJobId()).job();
                        assertThrows(RuntimeException.class, () -> jobs.mutate(ACTOR, key,
                                new RunnerJobOperation.Complete(id.runnerJobId(), "local", oldFence, evidence)));
                        var report = new RunnerJobOperation.Complete(id.runnerJobId(), "local", job.fence(), evidence);
                        jobs.mutate(ACTOR, key, report); jobs.mutate(ACTOR, key, report);
                        assertEquals(RunnerJob.State.CANCELLED, store.loadRunnerWorkspace(key).toCompletableFuture().join()
                                .orElseThrow().jobs().get(id.runnerJobId()).job().state());
                    }
                    executor.resume(key, id.runnerJobId()).toCompletableFuture().join();
                    recovery.sweep();
                    assertEquals(0, engine.spawnCount());
                    assertEquals(cancelledAt, store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow().processTerminalAt());
                    var journal = store.readJournal("tenant", 0, 500).toCompletableFuture().join();
                    assertEquals(1, journal.stream().filter(row -> row.envelope().eventType().equals("PROCESS_CANCEL")).count());
                    assertEquals(1, journal.stream().filter(row -> row.envelope().eventType().equals("RUNNER_JOB_PROCESS_CANCELLED")).count());
                    assertFalse(lifecycle.admitsReentry("tenant", key.processInstanceId()));
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProcessLifecycleService.Command.class, names = {"PAUSE", "STOP", "CANCEL"})
    void lifecycleWinningBetweenAdmissionAndFirstDeliveryWriteFencesAllSuccessors(ProcessLifecycleService.Command command) throws Exception {
        var clock = new TestClock(); var key = new ExecutionKey("tenant", UUID.randomUUID());
        try (var store = new SqliteExecutionStore(directory.resolve("race.db"), clock);
             var graphs = new InMemoryGraphDefinitionStore(clock); var engine = new JoinTestEngine()) {
            var beforeWrite = new java.util.concurrent.atomic.AtomicReference<Runnable>();
            ExecutionStore intercepted = intercept(store, batch -> batch.transitions().stream().anyMatch(transition ->
                    transition instanceof ExecutionTransition.ProcessTransitioned next && next.next() == ProcessInstanceStatus.RUNNING), beforeWrite);
            var jobs = jobs(intercepted, clock); var id = park(intercepted, graphs, engine, jobs, key);
            jobs.mutate(ACTOR, key, new RunnerJobOperation.Claim(id.runnerJobId(), "local", Duration.ofSeconds(30)));
            jobs.mutate(ACTOR, key, new RunnerJobOperation.Complete(id.runnerJobId(), "local", 1, result()));
            var lifecycle = new ProcessLifecycleService(store, APPLICATION, null, clock);
            beforeWrite.set(() -> assertEquals(ProcessLifecycleService.Code.APPLIED,
                    command(lifecycle, store, key, command, "race-winner").code()));
            try (var executor = executor(jobs, graphs, engine)) {
                assertThrows(CompletionException.class, () -> executor.resume(key, id.runnerJobId()).toCompletableFuture().join());
                assertNull(beforeWrite.get(), "the race must occur after admission and before its CAS write");
                var traversal = store.load(key).toCompletableFuture().join().state().traversals().get(id.traversalId());
                assertFalse(traversal.invocations().values().stream().anyMatch(invocation -> invocation.parentInvocationIds().contains(id.invocationId())));
                assertFalse(lifecycle.admitsReentry("tenant", key.processInstanceId()));
                executor.resume(key, id.runnerJobId()).toCompletableFuture().join();
                assertEquals(command == ProcessLifecycleService.Command.CANCEL ? NodeAttemptStatus.FAILED : NodeAttemptStatus.WAITING,
                        attempt(store, key, id).status());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProcessLifecycleService.Command.class, names = {"PAUSE", "STOP", "CANCEL"})
    void lifecycleWinningTerminalReportCasRequiresRereadWithoutLosingEvidence(ProcessLifecycleService.Command command) throws Exception {
        var clock = new TestClock(); var key = new ExecutionKey("tenant", UUID.randomUUID());
        try (var store = new SqliteExecutionStore(directory.resolve("report-race.db"), clock);
             var graphs = new InMemoryGraphDefinitionStore(clock); var engine = new JoinTestEngine()) {
            var beforeWrite = new java.util.concurrent.atomic.AtomicReference<Runnable>();
            ExecutionStore intercepted = intercept(store, batch -> batch.runnerOperations().stream()
                    .anyMatch(operation -> operation instanceof RunnerJobOperation.Complete), beforeWrite);
            var jobs = jobs(intercepted, clock); var id = park(intercepted, graphs, engine, jobs, key);
            jobs.mutate(ACTOR, key, new RunnerJobOperation.Claim(id.runnerJobId(), "local", Duration.ofSeconds(30)));
            var lifecycle = new ProcessLifecycleService(store, APPLICATION, null, clock);
            beforeWrite.set(() -> assertEquals(ProcessLifecycleService.Code.APPLIED,
                    command(lifecycle, store, key, command, "race-winner").code()));
            var evidence = result(); var report = new RunnerJobOperation.Complete(id.runnerJobId(), "local", 1, evidence);
            assertThrows(RuntimeException.class, () -> jobs.mutate(ACTOR, key, report));
            assertNull(beforeWrite.get());
            jobs.mutate(ACTOR, key, report); jobs.mutate(ACTOR, key, report);
            var job = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow().jobs().get(id.runnerJobId()).job();
            assertEquals(evidence, job.result());
            assertEquals(command == ProcessLifecycleService.Command.CANCEL ? RunnerJob.State.CANCELLED : RunnerJob.State.COMPLETED, job.state());
            assertEquals(1, store.readJournal("tenant", 0, 500).toCompletableFuture().join().stream()
                    .filter(row -> row.envelope().eventType().equals("RUNNER_JOB_TERMINAL_REPORTED")).count());
        }
    }

    static ExecutionStore intercept(ExecutionStore delegate, java.util.function.Predicate<ExecutionBatch> matches,
                                     java.util.concurrent.atomic.AtomicReference<Runnable> beforeWrite) {
        return (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(), new Class<?>[]{ExecutionStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("apply") && matches.test((ExecutionBatch) args[0])) {
                        var hook = beforeWrite.getAndSet(null); if (hook != null) hook.run();
                    }
                    try { return method.invoke(delegate, args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    static RunnerJobService jobs(ExecutionStore store, Clock clock) {
        var policy = new RunnerPolicy(Set.of(RunnerPolicy.Capability.WORKSPACE_READ), Set.of(), Set.of(), Set.of(), Set.of(),
                new RunnerPolicy.Limits(Duration.ofMinutes(10), 1_000_000, 1, 1_000_000, 10_000, 1_000, 4096));
        var definition = new AgentDefinition(new AgentDefinition.Reference("tenant", "specialist", 1), "Approved instructions",
                "reference", "reference", Map.of("plan", new AgentCommand("plan", true, policy, AgentCommand.STANDARD_OUTCOMES)),
                Set.of(), Set.of(), policy, Duration.ofDays(7), "result");
        return new RunnerJobService(store, clock, List.of(definition),
                List.of(new RunnerRegistration(1, "tenant", "local", "sandboxed", Set.of(), policy)), Map.of("tenant", policy));
    }

    static RunnerJobIdentity park(ExecutionStore store, InMemoryGraphDefinitionStore graphs, JoinTestEngine engine,
                                  RunnerJobService jobs, ExecutionKey key) throws Exception {
        var canonical = CanonicalGraphMl.of(XML.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        graphs.put("tenant", GraphDefinitionIdentity.forSubmission(canonical.contentId()), canonical).toCompletableFuture().join();
        UUID traversal = UUID.randomUUID();
        long revision = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.RUNNING,
                        Map.of(traversal, new Traversal(traversal, "start", TraversalStatus.RUNNING, Map.of()))),
                        new GraphVersionPin(canonical.contentId().value()))).build()).toCompletableFuture().join().revision();
        try (var manager = GraphManager.readGraphMl(new java.io.ByteArrayInputStream(canonical.bytes()));
             var runner = new GraphRunner(manager, engine, BehaviorRegistry.standard().withRunnerJobs(jobs), new ExecutionMonitor());
             var recorder = ExecutionRecorder.open(store, key, "initial", Duration.ofSeconds(30), revision)) {
            assertInstanceOf(RunnerJobSuspension.class, assertThrows(CompletionException.class, () -> runner.execute(ACTOR,
                    key.processInstanceId(), traversal, Map.of(), canonical.contentId().value(), null, null, recorder)
                    .toCompletableFuture().join()).getCause());
        }
        return store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow().jobs().values().iterator().next().job().identity();
    }
    static PinnedRunnerContinuationExecutor executor(RunnerJobService jobs, InMemoryGraphDefinitionStore graphs, JoinTestEngine engine) {
        return new PinnedRunnerContinuationExecutor(jobs, graphs, engine, BehaviorRegistry.standard().withRunnerJobs(jobs),
                new ExecutionMonitor(), GraphExecutionLimits.DEFAULTS, null);
    }
    static ProcessLifecycleService.Result command(ProcessLifecycleService lifecycle, ExecutionStore store, ExecutionKey key,
                                                  ProcessLifecycleService.Command command, String idempotency) {
        return lifecycle.command(key.tenantId(), key.processInstanceId(), command,
                store.load(key).toCompletableFuture().join().revision(), idempotency, "");
    }
    static RunnerResult result() { return new RunnerResult("answered", OpaquePayload.of("{}".getBytes(), "application/json"), List.of(), UUID.randomUUID()); }
    static NodeAttempt attempt(ExecutionStore store, ExecutionKey key, RunnerJobIdentity id) {
        return store.load(key).toCompletableFuture().join().state().traversals().get(id.traversalId()).invocations()
                .get(id.invocationId()).attempts().getFirst();
    }
    static void awaitTerminal(ExecutionStore store, ExecutionKey key) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!store.load(key).toCompletableFuture().join().state().status().terminal() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(store.load(key).toCompletableFuture().join().state().status().terminal());
    }
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    static SqliteExecutionStore shortJournalStore(Path database, Clock clock) {
        var base = ai.ravenroot.persistence.sqlite.SqliteStoreConfig.defaults();
        return new SqliteExecutionStore(database, clock, new ai.ravenroot.persistence.sqlite.SqliteStoreConfig(
                base.synchronousMode(), base.busyTimeout(), base.maxLeaseTtl(), base.maxPayloadBytes(), base.maxClockSkew(),
                Duration.ofSeconds(1), base.maxInventoryPageSize(), base.terminalRetention(), base.executionResultRetention()));
    }
}
