package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.runner.*;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.*;
import ai.ravenroot.core.runner.RunnerJobService;
import ai.ravenroot.core.runner.RunnerJobSuspension;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceAgentRuntimeTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static final SecurityContext SECURITY = new SecurityContext("request", "tenant", "operator", PrincipalType.USER, "test");

    @Test void partialSuccessorFanOutIsDurablyParkedAndNeverReplayed(@TempDir Path directory) throws Exception {
        String xml = """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k" for="node" attr.name="kind" attr.type="string"/>
                  <key id="b" for="node" attr.name="behavior" attr.type="string"/>
                  <key id="d" for="node" attr.name="agentDefinition" attr.type="string"/>
                  <key id="r" for="node" attr.name="runner" attr.type="string"/>
                  <key id="c" for="edge" attr.name="command" attr.type="string"/>
                  <key id="o" for="edge" attr.name="outcome" attr.type="string"/>
                  <graph edgedefault="directed">
                    <node id="start"><data key="k">START</data></node>
                    <node id="error"><data key="k">ERROR</data></node>
                    <node id="end"><data key="k">END</data></node>
                    <node id="planner"><data key="k">BEHAVIOR</data><data key="b">workspace-agent</data><data key="d">specialist</data><data key="r">local</data></node>
                    <node id="a"><data key="k">BEHAVIOR</data><data key="b">log</data></node>
                    <node id="b"><data key="k">BEHAVIOR</data><data key="b">log</data></node>
                    <edge source="start" target="planner"><data key="c">plan</data></edge>
                    <edge source="planner" target="a"><data key="o">answered</data><data key="c">process</data></edge>
                    <edge source="planner" target="b"><data key="o">answered</data><data key="c">process</data></edge>
                    <edge source="a" target="end"/><edge source="b" target="end"/>
                  </graph>
                </graphml>
                """;
        var canonical = CanonicalGraphMl.of(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var key = new ExecutionKey("tenant", UUID.randomUUID()); UUID traversal = UUID.randomUUID();
        try (var store = new SqliteExecutionStore(directory.resolve("partial.db"), CLOCK);
             var graphs = new ai.ravenroot.core.persistence.InMemoryGraphDefinitionStore(CLOCK);
             var engine = new JoinTestEngine()) {
            graphs.put("tenant", GraphDefinitionIdentity.forSubmission(canonical.contentId()), canonical).toCompletableFuture().join();
            var service = service(store); var behaviors = BehaviorRegistry.standard().withRunnerJobs(service);
            long revision = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent()).apply(
                    new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.RUNNING,
                            Map.of(traversal, new Traversal(traversal, "start", TraversalStatus.RUNNING, Map.of()))),
                            new GraphVersionPin(canonical.contentId().value()))).build()).toCompletableFuture().join().revision();
            try (var manager = GraphManager.readGraphMl(new java.io.ByteArrayInputStream(canonical.bytes()));
                 var runner = new GraphRunner(manager, engine, behaviors, new ExecutionMonitor());
                 var recorder = ExecutionRecorder.open(store, key, "initial", Duration.ofSeconds(30), revision)) {
                assertInstanceOf(RunnerJobSuspension.class, assertThrows(CompletionException.class, () -> runner.execute(SECURITY,
                        key.processInstanceId(), traversal, Map.of(), canonical.contentId().value(), null, null, recorder)
                        .toCompletableFuture().join()).getCause());
            }
            var identity = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow().jobs().values().iterator().next().job().identity();
            finish(service, identity, "answered");
            // Crash window: the original result is completed and only the first successor started.
            store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(store.load(key).toCompletableFuture().join().revision()))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                    .apply(new ExecutionTransition.TraversalTransitioned(traversal, TraversalStatus.RUNNING))
                    .apply(new ExecutionTransition.InvocationTransitioned(traversal, identity.invocationId(), NodeInvocationStatus.RUNNING))
                    .apply(new ExecutionTransition.AttemptTransitioned(traversal, identity.invocationId(), identity.attemptId(), NodeAttemptStatus.RUNNING))
                    .apply(new ExecutionTransition.AttemptTransitioned(traversal, identity.invocationId(), identity.attemptId(), NodeAttemptStatus.COMPLETED))
                    .apply(new ExecutionTransition.InvocationTransitioned(traversal, identity.invocationId(), NodeInvocationStatus.COMPLETED))
                    .apply(new ExecutionTransition.InvocationAdded(traversal, new NodeInvocation(UUID.randomUUID(), "a",
                            Set.of(identity.invocationId()), NodeInvocationStatus.RUNNING, List.of(
                                    new NodeAttempt(UUID.randomUUID(), 1, NodeAttemptStatus.RUNNING)))))
                    .build()).toCompletableFuture().join();
            int spawned = engine.spawnCount();
            try (var executor = new ai.ravenroot.core.runner.PinnedRunnerContinuationExecutor(service, graphs, engine, behaviors,
                    new ExecutionMonitor(), GraphExecutionLimits.DEFAULTS, null)) {
                assertThrows(CompletionException.class, () -> executor.resume(key, identity.runnerJobId()).toCompletableFuture().join());
                assertTrue(store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow().jobs().get(identity.runnerJobId()).continuationUncertain());
                long marked = store.load(key).toCompletableFuture().join().revision();
                assertThrows(CompletionException.class, () -> executor.resume(key, identity.runnerJobId()).toCompletableFuture().join());
                assertEquals(marked, store.load(key).toCompletableFuture().join().revision());
                assertEquals(spawned, engine.spawnCount());
                assertTrue(store.readJournal("tenant", 0, 64).toCompletableFuture().join().stream()
                        .anyMatch(record -> record.envelope().eventType().equals("RUNNER_JOB_CONTINUATION_UNCERTAIN")));
            }
        }
    }

    @Test void documentedDevelopmentCycleUsesProductionPinnedRecoveryAcrossEveryRestart(@TempDir Path directory) throws Exception {
        runDevelopmentCycle(directory, null);
    }

    @Test void writableContainerDevelopmentCycleUsesRealWorkspaceAcrossEveryRestart(@TempDir Path directory) throws Exception {
        String image = System.getProperty("ravenroot.runner.testWritableImage", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(image.matches("sha256:[0-9a-f]{64}"),
                "set ravenroot.runner.testWritableImage to the built example image on a quota-enforcing daemon");
        runDevelopmentCycle(directory, image);
    }

    private void runDevelopmentCycle(Path directory, String containerImage) throws Exception {
        Path database = directory.resolve("sample.db");
        Path samples = Path.of("../../docs/examples/governed-runner");
        var config = ai.ravenroot.core.runner.RunnerJson.read(java.nio.file.Files.readAllBytes(samples.resolve("control-plane.json")));
        var tenant = ai.ravenroot.core.runner.RunnerJson.map(ai.ravenroot.core.runner.RunnerJson.map(config.get("tenants")).get("example-tenant"));
        var policy = ai.ravenroot.core.runner.RunnerJson.policy(ai.ravenroot.core.runner.RunnerJson.map(tenant.get("policy")));
        var definitions = ((List<?>) tenant.get("definitions")).stream().map(value -> ai.ravenroot.core.runner.RunnerJson.definition(
                "example-tenant", ai.ravenroot.core.runner.RunnerJson.map(value))).toList();
        var registrations = ((List<?>) tenant.get("runners")).stream().map(value -> ai.ravenroot.core.runner.RunnerJson.registration(
                "example-tenant", ai.ravenroot.core.runner.RunnerJson.map(value))).toList();
        if (containerImage != null) try (var driver = sampleDriver(directory, registrations.getFirst(), containerImage)) {
            // Refuse the entire fixture before dispatch if the daemon ignores writable storage quotas.
            driver.verifyWorkspaceQuota(containerImage);
        }
        var canonical = CanonicalGraphMl.of(java.nio.file.Files.readAllBytes(samples.resolve("development-cycle.graphml")));
        String pin = canonical.contentId().value();
        var key = new ExecutionKey("example-tenant", UUID.randomUUID());
        UUID traversal = UUID.randomUUID();
        var security = new SecurityContext("sample", key.tenantId(), "operator", PrincipalType.USER, "test");
        try (var store = new SqliteExecutionStore(database, CLOCK);
             var graphStore = new ai.ravenroot.persistence.sqlite.SqliteGraphDefinitionStore(database, CLOCK, GraphDefinitionReferences.NONE);
             var engine = new JoinTestEngine();
             var manager = GraphManager.readGraphMl(new java.io.ByteArrayInputStream(canonical.bytes()))) {
            graphStore.put(key.tenantId(), GraphDefinitionIdentity.forSubmission(canonical.contentId()), canonical).toCompletableFuture().join();
            var service = new RunnerJobService(store, CLOCK, definitions, registrations, Map.of(key.tenantId(), policy));
            long revision = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent()).apply(
                    new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.RUNNING,
                            Map.of(traversal, new Traversal(traversal, "start", TraversalStatus.RUNNING, Map.of()))),
                            new GraphVersionPin(pin))).build()).toCompletableFuture().join().revision();
            try (var runner = new GraphRunner(manager, engine, BehaviorRegistry.standard().withRunnerJobs(service), new ExecutionMonitor());
                 var recorder = ExecutionRecorder.open(store, key, "sample-start", Duration.ofSeconds(30), revision)) {
                var result = runner.execute(security, key.processInstanceId(), traversal,
                        ai.ravenroot.core.runner.RunnerJson.read(java.nio.file.Files.readAllBytes(samples.resolve("input.json"))),
                        pin, null, null, recorder).toCompletableFuture();
                assertInstanceOf(RunnerJobSuspension.class, assertThrows(CompletionException.class, result::join).getCause());
                assertEquals(0, runner.admissionGateCount());
            }
        }
        UUID workspace = null;
        var commands = List.of("plan", "read", "resume", "implement", "test", "remediate", "test", "review", "handoff");
        var outcomes = List.of("answered", "answered", "completed", "completed", "failed", "fixed", "passed", "approved", "completed");
        RunnerJobIdentity caller = null;
        for (int index = 0; index < commands.size(); index++) {
            try (var store = new SqliteExecutionStore(database, CLOCK);
                 var graphStore = new ai.ravenroot.persistence.sqlite.SqliteGraphDefinitionStore(database, CLOCK, GraphDefinitionReferences.NONE);
                 var engine = new JoinTestEngine()) {
                var service = new RunnerJobService(store, CLOCK, definitions, registrations, Map.of(key.tenantId(), policy));
                var state = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow();
                if (workspace == null) workspace = state.workspaceId();
                assertEquals(workspace, state.workspaceId());
                assertEquals(1, state.jobs().values().stream().filter(entry -> entry.job().retainsWorkspace()).count());
                var job = state.jobs().values().stream().map(RunnerWorkspaceState.Entry::job).filter(RunnerJob::retainsWorkspace).findFirst().orElseThrow();
                assertEquals(commands.get(index), job.command().name());
                if (index == 0) caller = job.identity();
                if (index == 2) {
                    assertEquals(caller.traversalId(), job.identity().traversalId());
                    assertNotEquals(caller.invocationId(), job.identity().invocationId(), "explicit return is a new caller visit, not a technical retry");
                    var invocations = store.load(key).toCompletableFuture().join().state().traversals().get(traversal).invocations();
                    assertEquals(invocations.get(caller.invocationId()).nodeId(), invocations.get(job.identity().invocationId()).nodeId());
                    assertEquals(NodeInvocationStatus.COMPLETED, invocations.get(caller.invocationId()).status(), "caller holds no running worker while the researcher executes");
                }
                var claimed = service.mutate(security, key, new RunnerJobOperation.Claim(job.identity().runnerJobId(), "workspace-runner", Duration.ofSeconds(120)))
                        .jobs().get(job.identity().runnerJobId()).job();
                var report = new RunnerResult(outcomes.get(index), OpaquePayload.of("{}".getBytes(), "application/json"), List.of(), UUID.randomUUID());
                if (containerImage != null) {
                    var assignment = new RunnerAssignment(1, workspace, claimed);
                    try (var driver = sampleDriver(directory, registrations.getFirst(), containerImage)) {
                        report = driver.execute(assignment).toCompletableFuture().get(120, java.util.concurrent.TimeUnit.SECONDS);
                        assertEquals(outcomes.get(index), report.outcome());
                    }
                    // Worker restart after effects but before reporting must recover the same result,
                    // not launch a second process or overwrite the workspace snapshot.
                    try (var restarted = sampleDriver(directory, registrations.getFirst(), containerImage)) {
                        assertEquals(report, restarted.reconcile(assignment).toCompletableFuture().get(30, java.util.concurrent.TimeUnit.SECONDS));
                        assertThrows(Exception.class, () -> restarted.execute(assignment).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS));
                    }
                }
                service.mutate(security, key, new RunnerJobOperation.Complete(job.identity().runnerJobId(), "workspace-runner", 1, report));
                try (var executor = new ai.ravenroot.core.runner.PinnedRunnerContinuationExecutor(service, graphStore, engine,
                        BehaviorRegistry.standard().withRunnerJobs(service), new ExecutionMonitor(), GraphExecutionLimits.DEFAULTS, null)) {
                    executor.resume(key, job.identity().runnerJobId()).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
                    long revision = store.load(key).toCompletableFuture().join().revision();
                    executor.resume(key, job.identity().runnerJobId()).toCompletableFuture().join();
                    assertEquals(revision, store.load(key).toCompletableFuture().join().revision(), "duplicate delivery is a no-op");
                }
                var lifecycle = store.load(key).toCompletableFuture().join().state();
                var original = lifecycle.traversals().get(traversal).invocations().get(job.identity().invocationId());
                assertEquals(1, original.attempts().size());
                assertEquals(job.identity().attemptId(), original.attempts().getFirst().attemptId());
                if (index == commands.size() - 1) assertEquals(ProcessInstanceStatus.COMPLETED, lifecycle.status());
                if (containerImage != null && index == commands.size() - 1) {
                    try (var driver = sampleDriver(directory, registrations.getFirst(), containerImage)) {
                        driver.release(new RunnerWorkspaceRelease(1, key, workspace, "workspace-runner",
                                store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow().jobs().keySet(), CLOCK.instant()))
                                .toCompletableFuture().get(60, java.util.concurrent.TimeUnit.SECONDS);
                    }
                }
            }
        }
    }

    private ai.ravenroot.core.runner.LocalContainerRunner sampleDriver(Path directory, RunnerRegistration registration,
                                                                     String image) throws Exception {
        var artifacts = new ai.ravenroot.core.runner.RunnerArtifactStore(directory.toRealPath().resolve("artifacts"));
        return new ai.ravenroot.core.runner.LocalContainerRunner(registration,
                Path.of(System.getProperty("ravenroot.runner.testDocker", "/usr/local/bin/docker")),
                directory.toRealPath().resolve("worker-state"), Map.of("reference", image), CLOCK,
                (assignment, kind, bytes) -> artifacts.put(assignment.job(), kind, new java.io.ByteArrayInputStream(bytes)));
    }

    @Test void sequentialRunnerResultsResumeExactAttemptsAndExplicitCommandsAfterReopen(@TempDir Path directory) {
        Path database = directory.resolve("runner.db");
        var key = new ExecutionKey("tenant", UUID.randomUUID());
        UUID traversal = UUID.randomUUID();
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), agent("planner"), agent("developer"), GraphNode.end("end")),
                List.of(new GraphEdge("start", "planner", "continue", Map.of("command", "plan")),
                        new GraphEdge("planner", "developer", "answered", Map.of("command", "validate-change")),
                        new GraphEdge("developer", "end", "completed")));
        RunnerJobIdentity planned;
        UUID workspaceId;
        try (var store = new SqliteExecutionStore(database, CLOCK); var engine = new JoinTestEngine()) {
            var service = service(store);
            var behaviors = BehaviorRegistry.standard().withRunnerJobs(service);
            var process = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.RUNNING,
                    Map.of(traversal, new Traversal(traversal, "start", TraversalStatus.RUNNING, Map.of())));
            long revision = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                    .apply(new ExecutionTransition.ProcessCreated(process, new GraphVersionPin("graph-v1"))).build())
                    .toCompletableFuture().join().revision();
            try (var manager = GraphManager.from(graph);
                 var runner = new GraphRunner(manager, engine, behaviors, new ExecutionMonitor());
                 var recorder = ExecutionRecorder.open(store, key, "graph-owner", Duration.ofSeconds(30), revision)) {
                var failure = assertThrows(CompletionException.class, () -> runner.execute(SECURITY,
                        key.processInstanceId(), traversal, Map.of("intent", "build"), "graph-v1", null, null, recorder)
                        .toCompletableFuture().join());
                assertInstanceOf(RunnerJobSuspension.class, failure.getCause());
                assertEquals(0, runner.admissionGateCount());
            }
            var workspace = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow();
            workspaceId = workspace.workspaceId();
            planned = workspace.jobs().values().iterator().next().job().identity();
            assertEquals(traversal, planned.traversalId());
            assertEquals("plan", workspace.jobs().get(planned.runnerJobId()).job().command().name());
            assertEquals(Set.of(RunnerPolicy.Capability.WORKSPACE_READ), workspace.jobs().get(planned.runnerJobId()).job().authority().capabilities());
        }
        try (var store = new SqliteExecutionStore(database, CLOCK); var engine = new JoinTestEngine()) {
            var service = service(store); var behaviors = BehaviorRegistry.standard().withRunnerJobs(service);
            var first = finish(service, planned, "answered");
            resume(store, engine, behaviors, graph, key, first, true);
            var workspace = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow();
            assertEquals(workspaceId, workspace.workspaceId());
            assertEquals(2, workspace.jobs().size());
            var second = workspace.jobs().values().stream().map(RunnerWorkspaceState.Entry::job)
                    .filter(job -> !job.state().terminal()).findFirst().orElseThrow();
            assertEquals("validate-change", second.command().name(), "approved custom vocabulary is not limited to standard suggestions");
            assertEquals(traversal, second.identity().traversalId());
            assertNotEquals(planned.invocationId(), second.identity().invocationId());
            var last = finish(service, second.identity(), "completed");
            resume(store, engine, behaviors, graph, key, last, false);
            var completed = store.load(key).toCompletableFuture().join().state();
            assertEquals(ProcessInstanceStatus.COMPLETED, completed.status());
            var original = completed.traversals().get(traversal).invocations().get(planned.invocationId());
            assertEquals(1, original.attempts().size());
            assertEquals(planned.attemptId(), original.attempts().getFirst().attemptId());
            assertEquals(NodeAttemptStatus.COMPLETED, original.attempts().getFirst().status());
        }
    }

    private static void resume(ExecutionStore store, JoinTestEngine engine, BehaviorRegistry behaviors,
                               GraphDefinition graph, ExecutionKey key, RunnerJob job, boolean parks) {
        var workspace = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow();
        var checkpoint = GraphExecutionContinuationCheckpoint.read(GraphExecutionContinuationCheckpoint.VERSION,
                workspace.jobs().get(job.identity().runnerJobId()).continuation().bytes());
        var stored = store.load(key).toCompletableFuture().join();
        String node = stored.state().traversals().get(job.identity().traversalId()).invocations().get(job.identity().invocationId()).nodeId();
        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, behaviors, new ExecutionMonitor());
             var recorder = ExecutionRecorder.open(store, key, "resume-owner", Duration.ofSeconds(30), stored.revision())) {
            var result = runner.executeAfterRunnerJob(SECURITY, node, "graph-v1", recorder, job,
                    new NodeResult(job.result().outcome(), Map.of(), Map.of()), checkpoint, UUID.randomUUID()).toCompletableFuture();
            if (parks) assertInstanceOf(RunnerJobSuspension.class, assertThrows(CompletionException.class, result::join).getCause());
            else result.join();
        }
    }

    private static RunnerJob finish(RunnerJobService service, RunnerJobIdentity id, String outcome) {
        service.mutate(SECURITY, id.execution(), new RunnerJobOperation.Claim(id.runnerJobId(), "local", Duration.ofSeconds(30)));
        return service.mutate(SECURITY, id.execution(), new RunnerJobOperation.Complete(id.runnerJobId(), "local", 1,
                new RunnerResult(outcome, OpaquePayload.of("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json"),
                        List.of(), UUID.randomUUID()))).jobs().get(id.runnerJobId()).job();
    }
    private static GraphNode agent(String id) {
        return new GraphNode(id, NodeKind.BEHAVIOR, "workspace-agent",
                Map.of("agentDefinition", "specialist", "agentVersion", "1", "runner", "local"));
    }
    private static RunnerJobService service(ExecutionStore store) {
        var policy = new RunnerPolicy(Set.of(RunnerPolicy.Capability.WORKSPACE_READ, RunnerPolicy.Capability.WORKSPACE_WRITE),
                Set.of(), Set.of(), Set.of(), Set.of(), new RunnerPolicy.Limits(Duration.ofMinutes(10),
                1_000_000, 1, 1_000_000, 10_000, 1_000, 4096));
        var definition = new AgentDefinition(new AgentDefinition.Reference("tenant", "specialist", 1), "Approved instructions",
                "reference", "reference", Map.of("plan", new AgentCommand("plan", true, policy, AgentCommand.STANDARD_OUTCOMES),
                "implement", new AgentCommand("implement", false, policy, AgentCommand.STANDARD_OUTCOMES),
                "validate-change", new AgentCommand("validate-change", true, policy, AgentCommand.STANDARD_OUTCOMES)), Set.of(), Set.of(),
                policy, Duration.ofDays(7), "development-result");
        var registration = new RunnerRegistration(1, "tenant", "local", "sandboxed", Set.of(), policy);
        return new RunnerJobService(store, CLOCK, List.of(definition), List.of(registration), Map.of("tenant", policy));
    }
}
