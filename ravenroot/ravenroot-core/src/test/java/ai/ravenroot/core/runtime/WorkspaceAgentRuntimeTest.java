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

/** Restart/routing conformance uses labelled reports; the opt-in native path uses the real model broker. */
class WorkspaceAgentRuntimeTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static final SecurityContext SECURITY = new SecurityContext("request", "tenant", "operator", PrincipalType.USER, "test");
    private static final UUID SESSION = UUID.randomUUID();

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = RunnerJobOperation.ContinuationResolution.class, names = {"ACKNOWLEDGE", "ABANDON"})
    void partialSuccessorFanOutIsDurablyParkedAndNeverReplayed(RunnerJobOperation.ContinuationResolution disposition,
                                                            @TempDir Path directory) throws Exception {
        String xml = """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k" for="node" attr.name="kind" attr.type="string"/>
                  <key id="b" for="node" attr.name="behavior" attr.type="string"/>
                  <key id="d" for="node" attr.name="agentDefinition" attr.type="string"/>
                  <key id="r" for="node" attr.name="workspaceRef" attr.type="string"/>
                  <key id="p" for="node" attr.name="workspaceProfile" attr.type="string"/>
                  <key id="c" for="edge" attr.name="command" attr.type="string"/>
                  <key id="o" for="edge" attr.name="outcome" attr.type="string"/>
                  <graph edgedefault="directed">
                    <node id="start"><data key="k">START</data></node>
                    <node id="error"><data key="k">ERROR</data></node>
                    <node id="end"><data key="k">END</data></node>
                    <node id="repository"><data key="k">BEHAVIOR</data><data key="b">workspace</data><data key="p">development</data></node>
                    <node id="planner"><data key="k">BEHAVIOR</data><data key="b">agent</data><data key="d">specialist</data><data key="r">repository</data></node>
                    <node id="a"><data key="k">BEHAVIOR</data><data key="b">log</data></node>
                    <node id="b"><data key="k">BEHAVIOR</data><data key="b">log</data></node>
                    <edge source="start" target="repository"><data key="c">open</data></edge>
                    <edge source="repository" target="planner"><data key="o">ready</data><data key="c">plan</data></edge>
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
            var opening = queued(store, key).identity();
            finish(service, opening, "ready");
            try (var executor = new ai.ravenroot.core.runner.PinnedRunnerContinuationExecutor(service, graphs, engine, behaviors,
                    new ExecutionMonitor(), GraphExecutionLimits.DEFAULTS, null)) {
                executor.resume(key, opening.runnerJobId()).toCompletableFuture().join();
            }
            var identity = queued(store, key).identity();
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
                assertThrows(RuntimeException.class, () -> executor.resolve(SECURITY, key, identity.runnerJobId(), marked,
                        RunnerJobOperation.ContinuationResolution.RESUME));
                assertThrows(RuntimeException.class, () -> executor.resolve(SECURITY, key, identity.runnerJobId(), marked,
                        RunnerJobOperation.ContinuationResolution.ACKNOWLEDGE));
                if (disposition == RunnerJobOperation.ContinuationResolution.ACKNOWLEDGE) {
                    // Operator graph reconciliation records the missing dispatch; resolution observes it,
                    // never recreates either effect or invents an invocation identity.
                    store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(marked))
                            .apply(new ExecutionTransition.InvocationAdded(traversal, new NodeInvocation(UUID.randomUUID(), "b",
                                    Set.of(identity.invocationId()), NodeInvocationStatus.RUNNING,
                                    List.of(new NodeAttempt(UUID.randomUUID(), 1, NodeAttemptStatus.RUNNING)))))
                            .build()).toCompletableFuture().join();
                }
                long expected = store.load(key).toCompletableFuture().join().revision();
                long resolved = executor.resolve(SECURITY, key, identity.runnerJobId(), expected, disposition);
                assertTrue(resolved > expected);
                assertFalse(store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow().jobs().get(identity.runnerJobId()).continuationUncertain());
                assertThrows(RuntimeException.class, () -> executor.resolve(SECURITY, key, identity.runnerJobId(), expected, disposition));
                assertThrows(RuntimeException.class, () -> executor.resolve(SECURITY, key, identity.runnerJobId(), resolved, disposition));
                assertEquals(spawned, engine.spawnCount());
                var resolutionEvents = store.readJournal("tenant", 0, 64).toCompletableFuture().join().stream()
                        .filter(record -> record.envelope().eventType().equals("RUNNER_JOB_CONTINUATION_RESOLVED")).toList();
                assertEquals(1, resolutionEvents.size());
                assertTrue(new String(resolutionEvents.getFirst().envelope().payload().bytes()).contains(SECURITY.qualifiedIdentity()));
            }
        }
    }

    @Test void documentedDevelopmentCycleUsesProductionPinnedRecoveryAcrossEveryRestart(@TempDir Path directory) throws Exception {
        runDevelopmentCycle(directory, null);
    }

    @Test void documentedMinimalTeamExecutesItsLiteralNamedAgentOrder(@TempDir Path directory) throws Exception {
        runDevelopmentCycle(directory, null, WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE, true);
    }

    @Test void writableContainerDevelopmentCycleUsesRealWorkspaceAcrossEveryRestart(@TempDir Path directory) throws Exception {
        String image = System.getProperty("ravenroot.runner.testWritableImage", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(image.matches("sha256:[0-9a-f]{64}"),
                "set ravenroot.runner.testWritableImage to the built example image on a quota-enforcing daemon");
        runDevelopmentCycle(java.nio.file.Files.createDirectory(directory.resolve("minimal")), image,
                WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE, true);
        for (var lifecycle : WorkspaceProfile.RuntimeLifecycle.values()) {
            Path isolated = java.nio.file.Files.createDirectory(directory.resolve(lifecycle.name()));
            runDevelopmentCycle(isolated, image, lifecycle);
        }
    }

    private void runDevelopmentCycle(Path directory, String containerImage) throws Exception {
        runDevelopmentCycle(directory, containerImage, WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE);
    }

    private void runDevelopmentCycle(Path directory, String containerImage, WorkspaceProfile.RuntimeLifecycle lifecycle) throws Exception {
        runDevelopmentCycle(directory, containerImage, lifecycle, false);
    }

    private void runDevelopmentCycle(Path directory, String containerImage, WorkspaceProfile.RuntimeLifecycle lifecycle, boolean minimal) throws Exception {
        Path database = directory.resolve("sample.db");
        Path samples = Path.of("../../docs/examples/governed-runner");
        var config = ai.ravenroot.core.runner.RunnerJson.read(java.nio.file.Files.readAllBytes(samples.resolve("control-plane.json")));
        var tenant = ai.ravenroot.core.runner.RunnerJson.map(ai.ravenroot.core.runner.RunnerJson.map(config.get("tenants")).get("example-tenant"));
        var policy = ai.ravenroot.core.runner.RunnerJson.policy(ai.ravenroot.core.runner.RunnerJson.map(tenant.get("policy")));
        var definitions = ((List<?>) tenant.get("definitions")).stream().map(value -> ai.ravenroot.core.runner.RunnerJson.definition(
                "example-tenant", ai.ravenroot.core.runner.RunnerJson.map(value))).toList();
        var registrations = ((List<?>) tenant.get("runners")).stream().map(value -> ai.ravenroot.core.runner.RunnerJson.registration(
                "example-tenant", ai.ravenroot.core.runner.RunnerJson.map(value))).toList();
        var profiles = ((List<?>) tenant.get("workspaceProfiles")).stream().map(value -> ai.ravenroot.core.runner.RunnerJson.workspaceProfile(
                "example-tenant", ai.ravenroot.core.runner.RunnerJson.map(value)))
                .map(profile -> new WorkspaceProfile(profile.reference(), profile.workspaceScope(), lifecycle,
                        profile.runnerPool(), profile.runtimeProfile(), profile.policy(), profile.capacity(), profile.retention(),
                        profile.completionPolicy(), profile.allowedAgents(), profile.fleetLimits(), profile.cpuMillicores())).toList();
        if (containerImage != null) try (var driver = sampleDriver(directory, registrations.getFirst(), containerImage)) {
            // Refuse the entire fixture before dispatch if the daemon ignores writable storage quotas.
            driver.verifyWorkspaceQuota(containerImage);
        }
        var canonical = CanonicalGraphMl.of(java.nio.file.Files.readAllBytes(samples.resolve(
                minimal ? "three-agents.graphml" : "development-cycle.graphml")));
        String pin = canonical.contentId().value();
        var key = new ExecutionKey("example-tenant", UUID.randomUUID());
        UUID traversal = UUID.randomUUID();
        var security = new SecurityContext("sample", key.tenantId(), "operator", PrincipalType.USER, "test");
        try (var store = new SqliteExecutionStore(database, CLOCK);
             var graphStore = new ai.ravenroot.persistence.sqlite.SqliteGraphDefinitionStore(database, CLOCK, GraphDefinitionReferences.NONE);
             var engine = new JoinTestEngine();
             var manager = GraphManager.readGraphMl(new java.io.ByteArrayInputStream(canonical.bytes()))) {
            graphStore.put(key.tenantId(), GraphDefinitionIdentity.forSubmission(canonical.contentId()), canonical).toCompletableFuture().join();
            advertise(store, "example-tenant", "workspace-runner", "agent");
            var service = new RunnerJobService(store, CLOCK, definitions, registrations, Map.of(key.tenantId(), policy), profiles);
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
        var commands = minimal ? List.of("open", "implement", "read", "review", "close")
                : List.of("open", "plan", "read", "resume", "implement", "test", "remediate", "test", "review", "handoff", "close");
        var outcomes = minimal ? List.of("ready", "completed", "answered", "changes-requested", "closed")
                : List.of("ready", "answered", "answered", "completed", "completed", "failed", "fixed", "passed", "approved", "completed", "closed");
        String runtimeIdentity = null;
        var invocationRuntimes = new HashSet<String>();
        var physicalExtraJobs = new HashSet<UUID>();
        byte[] gitHead = null;
        byte[] planSentinel = null;
        RunnerJobIdentity caller = null;
        for (int index = 0; index < commands.size(); index++) {
            try (var store = new SqliteExecutionStore(database, CLOCK);
                 var graphStore = new ai.ravenroot.persistence.sqlite.SqliteGraphDefinitionStore(database, CLOCK, GraphDefinitionReferences.NONE);
                 var engine = new JoinTestEngine()) {
                advertise(store, "example-tenant", "workspace-runner", "agent");
                var service = new RunnerJobService(store, CLOCK, definitions, registrations, Map.of(key.tenantId(), policy), profiles);
                var state = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow();
                var resource = state.workspaces().get("repository");
                if (workspace == null) workspace = resource.workspaceId();
                assertEquals(workspace, resource.workspaceId());
                assertEquals(1, state.jobs().values().stream().filter(entry -> entry.job().retainsWorkspace()).count());
                var job = state.jobs().values().stream().map(RunnerWorkspaceState.Entry::job).filter(RunnerJob::retainsWorkspace).findFirst().orElseThrow();
                assertEquals(commands.get(index), job.command().name());
                if (index == 1) caller = job.identity();
                if (index == 3 && !minimal) {
                    assertEquals(caller.traversalId(), job.identity().traversalId());
                    assertNotEquals(caller.invocationId(), job.identity().invocationId(), "explicit return is a new caller visit, not a technical retry");
                    var invocations = store.load(key).toCompletableFuture().join().state().traversals().get(traversal).invocations();
                    assertEquals(invocations.get(caller.invocationId()).nodeId(), invocations.get(job.identity().invocationId()).nodeId());
                    assertEquals(NodeInvocationStatus.COMPLETED, invocations.get(caller.invocationId()).status(), "caller holds no running worker while the researcher executes");
                }
                var claimed = service.mutate(security, key, new RunnerJobOperation.Claim(job.identity().runnerJobId(), "workspace-runner", Duration.ofSeconds(120), SESSION))
                        .jobs().get(job.identity().runnerJobId()).job();
                var report = new RunnerResult(outcomes.get(index), OpaquePayload.of("{}".getBytes(), "application/json"), List.of(), UUID.randomUUID(),
                        new RunnerResult.WorkspaceObservation(workspace, "conformance-runtime", null));
                if (containerImage != null) {
                    var assignment = new RunnerAssignment(1, workspace, claimed, resource, state.jobs().get(claimed.identity().runnerJobId()).lifecycleCommand());
                    try (var driver = sampleDriver(directory, registrations.getFirst(), containerImage)) {
                        report = driver.execute(assignment).toCompletableFuture().get(120, java.util.concurrent.TimeUnit.SECONDS);
                        assertEquals(outcomes.get(index), report.outcome());
                    }
                    if (lifecycle == WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE) {
                        if (runtimeIdentity == null) runtimeIdentity = report.workspace().runtimeId();
                        assertEquals(runtimeIdentity, report.workspace().runtimeId(), "every Agent and loop uses the same runtime");
                    } else if (assignment.lifecycleCommand() == null) {
                        assertTrue(invocationRuntimes.add(report.workspace().runtimeId()), "each invocation has a distinct physical container");
                        assertNotNull(report.workspace().checkpoint(), "the same filesystem is checkpointed before handoff");
                    }
                    if (report.workspace().runtimeId() != null) {
                        byte[] head = nativeWorkspaceFile(directory, report.workspace().runtimeId(), ".git/HEAD", true);
                        if (gitHead == null) gitHead = head;
                        assertArrayEquals(gitHead, head, "Git identity remains intact without repository reinitialization");
                        int sentinelStart = minimal ? 1 : 4;
                        byte[] plan = nativeWorkspaceFile(directory, report.workspace().runtimeId(), "PLAN.md", index >= sentinelStart);
                        if (index < sentinelStart) assertNull(plan, "another process's uncommitted plan must not leak into this Workspace");
                        else {
                            if (planSentinel == null) { planSentinel = plan; assertTrue(plan.length > 0); }
                            assertArrayEquals(planSentinel, plan, "uncommitted Agent-written sentinel survives every successor and remediation loop");
                        }
                    }
                    // Worker restart after effects but before reporting must recover the same result,
                    // not launch a second process or overwrite the workspace snapshot.
                    try (var restarted = sampleDriver(directory, registrations.getFirst(), containerImage)) {
                        assertEquals(report, restarted.reconcile(assignment).toCompletableFuture().get(30, java.util.concurrent.TimeUnit.SECONDS));
                        assertThrows(Exception.class, () -> restarted.execute(assignment).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS));
                    }
                    if (!minimal && index == 4 && lifecycle == WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE) {
                        physicalExtraJobs.add(nativeLaterTraversalIsolation(directory, containerImage, registrations.getFirst(),
                                definitions.stream().filter(value -> value.reference().name().equals("polaris")).findFirst().orElseThrow(),
                                definitions.stream().filter(value -> value.reference().name().equals("tester")).findFirst().orElseThrow(),
                                resource.observed("inspect", report.workspace().runtimeId(), report.workspace().checkpoint(), CLOCK.instant()),
                                key, traversal, planSentinel));
                    }
                }
                service.mutate(security, key, new RunnerJobOperation.Complete(job.identity().runnerJobId(), "workspace-runner", 1, report));
                try (var executor = new ai.ravenroot.core.runner.PinnedRunnerContinuationExecutor(service, graphStore, engine,
                        BehaviorRegistry.standard().withRunnerJobs(service), new ExecutionMonitor(), GraphExecutionLimits.DEFAULTS, null)) {
                    if (index == 1) {
                        service.mutate(security, key, new RunnerJobOperation.ContinuationUncertain(job.identity().runnerJobId()));
                        long expected = store.load(key).toCompletableFuture().join().revision();
                        executor.resolve(security, key, job.identity().runnerJobId(), expected, RunnerJobOperation.ContinuationResolution.RESUME);
                        assertThrows(RuntimeException.class, () -> executor.resolve(security, key, job.identity().runnerJobId(),
                                expected, RunnerJobOperation.ContinuationResolution.RESUME));
                    }
                    executor.resume(key, job.identity().runnerJobId()).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
                    long revision = store.load(key).toCompletableFuture().join().revision();
                    executor.resume(key, job.identity().runnerJobId()).toCompletableFuture().join();
                    assertEquals(revision, store.load(key).toCompletableFuture().join().revision(), "duplicate delivery is a no-op");
                }
                var processState = store.load(key).toCompletableFuture().join().state();
                var original = processState.traversals().get(traversal).invocations().get(job.identity().invocationId());
                assertEquals(1, original.attempts().size());
                assertEquals(job.identity().attemptId(), original.attempts().getFirst().attemptId());
                if (index == commands.size() - 1) assertEquals(ProcessInstanceStatus.COMPLETED, processState.status());
                if (containerImage != null && index == commands.size() - 1) {
                    try (var driver = sampleDriver(directory, registrations.getFirst(), containerImage)) {
                        var receipts = new HashSet<>(store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow().jobs().keySet());
                        receipts.addAll(physicalExtraJobs);
                        driver.release(new RunnerWorkspaceRelease(1, key, workspace, "workspace-runner",
                                receipts, CLOCK.instant()))
                                .toCompletableFuture().get(60, java.util.concurrent.TimeUnit.SECONDS);
                    }
                }
            }
        }
    }

    /** Physical driver acceptance complements the persisted traversal/session and fleet contracts. */
    private UUID nativeLaterTraversalIsolation(Path directory, String image, RunnerRegistration registration,
            AgentDefinition definition, AgentDefinition tester, WorkspaceResource original, ExecutionKey firstProcess,
            UUID firstTraversal, byte[] sentinel) throws Exception {
        var otherProcess = new ExecutionKey(firstProcess.tenantId(), UUID.randomUUID());
        var other = new WorkspaceResource(original.nodeId(), UUID.randomUUID(), original.profile(), registration.runnerId(),
                WorkspaceResource.State.OPENING, null, null, false, CLOCK.instant());
        var lifecycleCommands = new LinkedHashMap<String, AgentCommand>();
        Map.of("open", "ready", "close", "closed").forEach((command, outcome) -> lifecycleCommands.put(command,
                new AgentCommand(command, false, original.profile().policy(), Set.of(outcome))));
        var lifecycle = new AgentDefinition(new AgentDefinition.Reference(firstProcess.tenantId(), "workspace-lifecycle", 1),
                "Native fixture lifecycle", "agent", "none", lifecycleCommands, Set.of(), Set.of(), original.profile().policy(),
                Duration.ZERO, "workspace-state");
        var foreignJobs = new HashSet<UUID>();
        try (var driver = sampleDriver(directory, registration, image)) {
            var open = nativeAssignment(otherProcess, UUID.randomUUID(), lifecycle, "open", registration, other, "open");
            foreignJobs.add(open.job().identity().runnerJobId());
            var opened = driver.execute(open).toCompletableFuture().get(120, java.util.concurrent.TimeUnit.SECONDS);
            other = other.observed("open", opened.workspace().runtimeId(), opened.workspace().checkpoint(), CLOCK.instant());
            assertNotEquals(original.runtimeId(), other.runtimeId());
            assertNull(nativeWorkspaceFile(directory, other.runtimeId(), "PLAN.md", false), "another intent cannot observe uncommitted files");
            var later = nativeAssignment(firstProcess, UUID.randomUUID(), definition, "read", registration, original, null);
            var foreign = nativeAssignment(otherProcess, UUID.randomUUID(), definition, "read", registration, other, null);
            foreignJobs.add(foreign.job().identity().runnerJobId());
            var earlier = nativeAssignment(firstProcess, firstTraversal, definition, "read", registration, original, null);
            assertEquals(earlier.agentSessionId(), later.agentSessionId());
            assertNotEquals(earlier.job().identity().traversalId(), later.job().identity().traversalId());
            assertNotEquals(later.agentSessionId(), foreign.agentSessionId());
            var first = driver.execute(later).toCompletableFuture();
            var second = driver.execute(foreign).toCompletableFuture();
            var firstResult = first.get(120, java.util.concurrent.TimeUnit.SECONDS);
            var secondResult = second.get(120, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("answered", firstResult.outcome()); assertEquals("answered", secondResult.outcome());
            assertEquals(original.runtimeId(), firstResult.workspace().runtimeId(), "later traversal keeps the still-open container");
            assertEquals(other.runtimeId(), secondResult.workspace().runtimeId());
            assertArrayEquals(sentinel, nativeWorkspaceFile(directory, original.runtimeId(), "PLAN.md", true));
            assertNull(nativeWorkspaceFile(directory, other.runtimeId(), "PLAN.md", false));
            // Trusted fixture setup creates a bounded long-running test in the other disposable
            // repository. Only the unchanged model gateway/Agent test tool may actually run it.
            nativeDocker(directory, "exec", other.runtimeId(), "python3", "-c",
                    "from pathlib import Path; Path('/workspace/test_hello.py').write_text('import unittest, time\\nclass StopProbe(unittest.TestCase):\\n    def test_wait(self): time.sleep(90)\\n')");
            var testing = nativeAssignment(otherProcess, UUID.randomUUID(), tester, "test", registration, other, null);
            foreignJobs.add(testing.job().identity().runnerJobId());
            var activeTest = driver.execute(testing).toCompletableFuture();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!nativeDocker(directory, "top", other.runtimeId(), "-eo", "args").contains("-m unittest discover -v")) {
                assertFalse(activeTest.isDone(), "the real Agent must launch its governed test child before cancellation");
                assertTrue(System.nanoTime() < deadline, "the governed model/tool loop did not launch the native test child");
                Thread.sleep(50);
            }
            var stopped = new RunnerAssignment(testing.protocolVersion(), testing.workspaceId(), testing.job(),
                    other.request("abort", true, CLOCK.instant()), null);
            driver.stopWorkspace(stopped).toCompletableFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThrows(Exception.class, () -> activeTest.get(30, java.util.concurrent.TimeUnit.SECONDS),
                    "a stopped native tool must not return a successful Agent result");
            assertEquals("false", nativeDocker(directory, "inspect", "--format={{.State.Running}}", other.runtimeId()));
            assertEquals("true", nativeDocker(directory, "inspect", "--format={{.State.Running}}", original.runtimeId()),
                    "Workspace stop must not terminate the independent retained runtime");
            assertArrayEquals(sentinel, nativeWorkspaceFile(directory, original.runtimeId(), "PLAN.md", true));
            try (var restarted = sampleDriver(directory, registration, image)) {
                restarted.stopWorkspace(stopped).toCompletableFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);
                var forbidden = nativeAssignment(otherProcess, UUID.randomUUID(), definition, "read", registration, other, null);
                assertThrows(Exception.class, () -> restarted.execute(forbidden).toCompletableFuture().get(30, java.util.concurrent.TimeUnit.SECONDS),
                        "durable physical stop fences later dispatch even after worker restart");
            }
            driver.release(new RunnerWorkspaceRelease(1, otherProcess, other.workspaceId(), registration.runnerId(),
                    foreignJobs, CLOCK.instant())).toCompletableFuture().get(60, java.util.concurrent.TimeUnit.SECONDS);
            return later.job().identity().runnerJobId();
        }
    }

    /** Bounded, exact-container physical evidence; no model assertion substitutes for Docker state. */
    private static String nativeDocker(Path directory, String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add(System.getProperty("ravenroot.runner.testDocker", "/usr/local/bin/docker"));
        command.addAll(List.of(arguments));
        Path output = directory.resolve("native-probe-" + UUID.randomUUID());
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(output.toFile()).start();
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly(); throw new AssertionError("bounded native evidence probe timed out");
        }
        assertEquals(0, process.exitValue(), "native evidence probe failed");
        assertTrue(java.nio.file.Files.size(output) <= 16_384, "native evidence output exceeded its fixture bound");
        return java.nio.file.Files.readString(output).trim();
    }

    private static RunnerAssignment nativeAssignment(ExecutionKey key, UUID traversal, AgentDefinition definition,
            String command, RunnerRegistration worker, WorkspaceResource resource, String lifecycle) {
        var id = new RunnerJobIdentity(key, traversal, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var accepted = RunnerJob.accept(id, definition, command, resource.profile().policy(), worker,
                OpaquePayload.of(ai.ravenroot.core.runner.RunnerJson.write(Map.of("intent", command.equals("test")
                        ? "Run the operator-configured test tool on this disposable repository and report only its observed result."
                        : "Read the disposable repository without modifying any file.")), "application/json"),
                CLOCK.instant(), CLOCK.instant().plusSeconds(120)).claim(worker.runnerId(), CLOCK.instant(), Duration.ofSeconds(120));
        return new RunnerAssignment(1, resource.workspaceId(), accepted, resource, lifecycle);
    }

    /** Reads the physical container, including a stopped per-invocation container; never trusts model claims. */
    private static byte[] nativeWorkspaceFile(Path directory, String runtime, String relative, boolean required) throws Exception {
        Path evidence = directory.resolve("filesystem-evidence-" + UUID.randomUUID());
        var process = new ProcessBuilder(System.getProperty("ravenroot.runner.testDocker", "/usr/local/bin/docker"),
                "cp", runtime + ":/workspace/" + relative, evidence.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly(); throw new AssertionError("bounded filesystem evidence read timed out");
        }
        if (process.exitValue() != 0) {
            assertFalse(required, "required filesystem evidence is absent: " + relative);
            return null;
        }
        assertTrue(java.nio.file.Files.size(evidence) <= 16_384, "evidence remains within the configured sample payload bound");
        return java.nio.file.Files.readAllBytes(evidence);
    }

    private ai.ravenroot.core.runner.LocalContainerRunner sampleDriver(Path directory, RunnerRegistration registration,
                                                                     String image) throws Exception {
        var artifacts = new ai.ravenroot.core.runner.RunnerArtifactStore(directory.toRealPath().resolve("artifacts"));
        String configured = System.getProperty("ravenroot.runner.testAgentConfiguration", "");
        if (configured.isBlank()) throw new IllegalArgumentException("real model acceptance requires ravenroot.runner.testAgentConfiguration (worker JSON)");
        var config = ai.ravenroot.core.runner.RunnerJson.read(java.nio.file.Files.readAllBytes(Path.of(configured)));
        return new ai.ravenroot.core.runner.LocalContainerRunner(registration,
                Path.of(System.getProperty("ravenroot.runner.testDocker", "/usr/local/bin/docker")),
                directory.toRealPath().resolve("worker-state"), Map.of("agent", image), CLOCK,
                (assignment, kind, bytes) -> artifacts.put(assignment.job(), kind, new java.io.ByteArrayInputStream(bytes)),
                new ai.ravenroot.core.runner.RunnerWorkerConfiguration(2, Duration.ofSeconds(1), Duration.ofSeconds(5),
                        Duration.ofSeconds(30), Duration.ofSeconds(30)))
                .withAgentRuntime(ai.ravenroot.core.runner.RunnerAgentRuntime.fromConfiguration(
                        ai.ravenroot.core.runner.RunnerJson.map(config.get("agentRuntime")),
                        Boolean.getBoolean("ravenroot.runner.testHermeticModel")
                                ? new ai.ravenroot.api.security.SecretProvider() {
                                    public String id() { return "hermetic-no-secrets"; }
                                    public Optional<ai.ravenroot.api.security.SecretValue> get(String reference) {
                                        throw new AssertionError("hermetic acceptance may never resolve credentials");
                                    }
                                }
                                : new ai.ravenroot.core.security.EnvironmentCredentialResolver()));
    }

    @Test void sequentialRunnerResultsResumeExactAttemptsAndExplicitCommandsAfterReopen(@TempDir Path directory) {
        Path database = directory.resolve("runner.db");
        var key = new ExecutionKey("tenant", UUID.randomUUID());
        UUID traversal = UUID.randomUUID();
        var graph = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("repository", NodeKind.BEHAVIOR, "workspace", Map.of("workspaceProfile", "development")),
                agent("planner"), agent("developer"), GraphNode.end("end")),
                List.of(new GraphEdge("start", "repository", "continue", Map.of("command", "open")),
                        new GraphEdge("repository", "planner", "ready", Map.of("command", "plan")),
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
            var opened = finish(service, queued(store, key).identity(), "ready");
            resume(store, engine, behaviors, graph, key, opened, true);
            var workspace = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow();
            workspaceId = workspace.workspaceId();
            planned = queued(store, key).identity();
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
            assertEquals(3, workspace.jobs().size());
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
        var state = service.mutate(SECURITY, id.execution(), new RunnerJobOperation.Claim(id.runnerJobId(), "local", Duration.ofSeconds(30), SESSION));
        return service.mutate(SECURITY, id.execution(), new RunnerJobOperation.Complete(id.runnerJobId(), "local", 1,
                new RunnerResult(outcome, OpaquePayload.of("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json"),
                        List.of(), UUID.randomUUID(), new RunnerResult.WorkspaceObservation(
                                state.workspaces().get("repository").workspaceId(), "conformance-runtime", null))))
                .jobs().get(id.runnerJobId()).job();
    }
    private static RunnerJob queued(ExecutionStore store, ExecutionKey key) {
        return store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow().jobs().values().stream()
                .map(RunnerWorkspaceState.Entry::job).filter(job -> !job.state().terminal()).findFirst().orElseThrow();
    }
    private static void advertise(ExecutionStore store, String tenant, String runner, String runtime) {
        store.renewRunnerAvailability(new RunnerAvailability(tenant, runner, SESSION, 8, 0, Set.of(runtime),
                CLOCK.instant(), CLOCK.instant().plusSeconds(120)), Duration.ofSeconds(120)).toCompletableFuture().join();
    }
    private static GraphNode agent(String id) {
        return new GraphNode(id, NodeKind.BEHAVIOR, "agent",
                Map.of("agentDefinition", "specialist", "agentVersion", "1", "workspaceRef", "repository"));
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
        var registration = new RunnerRegistration(1, "tenant", "local", "sandboxed", Set.of("development"), policy);
        store.renewRunnerAvailability(new RunnerAvailability("tenant", "local", SESSION, 7, 0, Set.of("reference"),
                CLOCK.instant(), CLOCK.instant().plusSeconds(30)), Duration.ofSeconds(30)).toCompletableFuture().join();
        var profile = new WorkspaceProfile(new AgentDefinition.Reference("tenant", "development", 1),
                WorkspaceProfile.Scope.PROCESS_INSTANCE, WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE,
                "development", "reference", policy, new WorkspaceProfile.Capacity(1, 7, 8, 8_000_000, 64, 1024,
                WorkspaceProfile.Admission.QUEUE), Duration.ofDays(7), WorkspaceProfile.CompletionPolicy.ABORT, Set.of("specialist"));
        return new RunnerJobService(store, CLOCK, List.of(definition), List.of(registration), Map.of("tenant", policy), List.of(profile));
    }
}
