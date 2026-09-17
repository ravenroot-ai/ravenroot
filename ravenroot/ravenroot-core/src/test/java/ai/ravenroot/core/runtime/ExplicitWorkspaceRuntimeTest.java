package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.runner.*;
import ai.ravenroot.api.security.*;
import ai.ravenroot.core.graph.*;
import ai.ravenroot.core.runner.*;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

/** Control-plane conformance only: terminal reports here are fixtures, not model evidence. */
class ExplicitWorkspaceRuntimeTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC);
    private static final SecurityContext ACTOR = new SecurityContext("test", "tenant", "operator", PrincipalType.USER, "test");
    private static final UUID WORKER_SESSION = UUID.randomUUID();
    private static final String GRAPH = """
        <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
          <key id="k" for="node" attr.name="kind" attr.type="string"/>
          <key id="b" for="node" attr.name="behavior" attr.type="string"/>
          <key id="p" for="node" attr.name="workspaceProfile" attr.type="string"/>
          <key id="w" for="node" attr.name="workspaceRef" attr.type="string"/>
          <key id="d" for="node" attr.name="agentDefinition" attr.type="string"/>
          <key id="j" for="node" attr.name="joinPolicy" attr.type="string"/>
          <key id="c" for="edge" attr.name="command" attr.type="string"/>
          <key id="o" for="edge" attr.name="outcome" attr.type="string"/>
          <graph edgedefault="directed">
            <node id="start"><data key="k">START</data></node>
            <node id="error"><data key="k">ERROR</data></node>
            <node id="end"><data key="k">END</data></node>
            <node id="repository"><data key="k">BEHAVIOR</data><data key="b">workspace</data><data key="p">development</data><data key="j">any</data></node>
            <node id="planner"><data key="k">BEHAVIOR</data><data key="b">agent</data><data key="w">repository</data><data key="d">planner</data></node>
            <node id="coder"><data key="k">BEHAVIOR</data><data key="b">agent</data><data key="w">repository</data><data key="d">coder</data></node>
            <node id="reviewer"><data key="k">BEHAVIOR</data><data key="b">agent</data><data key="w">repository</data><data key="d">reviewer</data></node>
            <edge source="start" target="repository"><data key="c">open</data></edge>
            <edge source="repository" target="planner"><data key="o">ready</data><data key="c">plan</data></edge>
            <edge source="planner" target="coder"><data key="o">answered</data><data key="c">implement</data></edge>
            <edge source="coder" target="reviewer"><data key="o">completed</data><data key="c">review</data></edge>
            <edge source="reviewer" target="repository"><data key="o">approved</data><data key="c">close</data></edge>
            <edge source="repository" target="end"><data key="o">closed</data></edge>
          </graph>
        </graphml>
        """;
    private static RunnerPolicy policy() {
        return new RunnerPolicy(Set.of(RunnerPolicy.Capability.WORKSPACE_READ, RunnerPolicy.Capability.WORKSPACE_WRITE,
                RunnerPolicy.Capability.PROCESS_EXECUTE), Set.of(), Set.of(), Set.of(), Set.of(),
                new RunnerPolicy.Limits(Duration.ofMinutes(2), 64_000_000, 8, 8_000_000, 1024, 1024, 4096));
    }
    private static RunnerJobService service(ExecutionStore store) {
        store.renewRunnerAvailability(new RunnerAvailability("tenant", "worker-a", WORKER_SESSION, 7, 0, Set.of("runtime"),
                CLOCK.instant(), CLOCK.instant().plusSeconds(30)), Duration.ofSeconds(30)).toCompletableFuture().join();
        var definitions = new ArrayList<AgentDefinition>();
        Map.of("planner", "plan", "coder", "implement", "reviewer", "review").forEach((name, command) ->
            definitions.add(new AgentDefinition(new AgentDefinition.Reference("tenant", name, 1),
                "Approved " + name, "runtime", "test-model", Map.of(command,
                    new AgentCommand(command, !name.equals("coder"), policy(), AgentCommand.STANDARD_OUTCOMES)),
                Set.of(), Set.of("development"), policy(), Duration.ofDays(1), "result")));
        var profile = new WorkspaceProfile(new AgentDefinition.Reference("tenant", "development", 1),
                WorkspaceProfile.Scope.PROCESS_INSTANCE, WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE,
                "development", "runtime", policy(), new WorkspaceProfile.Capacity(1, 3, 8, 64_000_000, 16, 1024,
                WorkspaceProfile.Admission.QUEUE), Duration.ofDays(1), WorkspaceProfile.CompletionPolicy.REQUIRE_CLOSED,
                Set.of("planner", "coder", "reviewer"));
        return new RunnerJobService(store, CLOCK, definitions, List.of(new RunnerRegistration(1, "tenant", "worker-a",
                "local-container-v1", Set.of("development"), policy())), Map.of("tenant", policy()), List.of(profile));
    }
    @Test void typedReferencesRejectMissingWrongKindAndCrossGraphTargetsWithoutChangingOrdinaryAgents(@TempDir Path directory) throws Exception {
        try (var store = new SqliteExecutionStore(directory.resolve("references.db"), CLOCK)) {
            var registry = BehaviorRegistry.standard().withRunnerJobs(service(store));
            var schema = new BehaviorPropertySchema(registry);
            for (String target : List.of("absent", "planner", "other-graph/repository")) {
                try (var graph = GraphManager.readGraphMl(new java.io.ByteArrayInputStream(
                        GRAPH.replace("<data key=\"w\">repository</data>", "<data key=\"w\">" + target + "</data>").getBytes(StandardCharsets.UTF_8)))) {
                    var failure = assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class, () -> schema.validate(graph.definition()));
                    assertEquals("workspaceRef", failure.propertyName());
                }
            }
            try (var graph = GraphManager.readGraphMl(new java.io.ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)))) {
                assertDoesNotThrow(() -> schema.validate(graph.definition()));
                assertEquals(ai.ravenroot.api.catalog.NodePropertyType.WORKSPACE_REFERENCE,
                        registry.descriptor("agent").orElseThrow().properties().stream().filter(value -> value.name().equals("workspaceRef")).findFirst().orElseThrow().type());
            }
            var ordinary = GraphNode.behavior("ordinary", "agent");
            assertFalse(GovernedAgent.usesWorkspace(ordinary));
            var removed = new GraphDefinition(List.of(GraphNode.start("start"), GraphNode.error("error"), GraphNode.end("end"),
                    GraphNode.behavior("removed", "workspace-agent")), List.of(GraphEdge.to("start", "removed"), GraphEdge.to("removed", "end")));
            assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class, () -> schema.validate(removed));
        }
    }
    @Test void namedConversationalAgentSnapshotsApprovedTenantDefinitionAndNeverUsesGraphOverrides(@TempDir Path directory) throws Exception {
        try (var store = new SqliteExecutionStore(directory.resolve("conversational.db"), CLOCK)) {
            var jobs = service(store);
            var captured = new ArrayList<AgentDefinition>();
            var plugin = new ai.ravenroot.api.node.GovernedAgentCapable() {
                public ai.ravenroot.api.catalog.NodeTypeDescriptor descriptor() {
                    return new ai.ravenroot.api.catalog.NodeTypeDescriptor("agent", "Agent", "AI", "Test package", "agent",
                            true, List.of(), Set.of("ai", "agentic"));
                }
                public ai.ravenroot.api.node.NodeAction create(ai.ravenroot.api.node.NodeConfiguration configuration) {
                    return message -> java.util.concurrent.CompletableFuture.completedFuture(
                            ai.ravenroot.api.execution.NodeResult.continueWith("legacy"));
                }
                public ai.ravenroot.api.node.NodeAction createGoverned(String nodeId,
                        ai.ravenroot.api.node.service.NodePackageServices services, AgentDefinition definition,
                        AgentCommand command, RunnerPolicy authority) {
                    captured.add(definition);
                    return message -> java.util.concurrent.CompletableFuture.completedFuture(
                            new ai.ravenroot.api.execution.NodeResult("answered", definition.instructions(), Map.of()));
                }
            };
            var registry = BehaviorRegistry.standard().withRunnerJobs(jobs);
            registry.registerFactory(new NodePackages.SdkNodeBehaviorFactory(plugin));
            var selection = registry.descriptor("agent").orElseThrow().properties().stream()
                    .filter(property -> property.name().equals("agentDefinition")).findFirst().orElseThrow();
            assertNull(selection.visibleWhen(), "named Agents remain selectable without a Workspace");
            assertEquals(ai.ravenroot.api.catalog.PropertyCondition.present("workspaceRef"), selection.requiredWhen());
            var node = new GraphNode("planner", NodeKind.BEHAVIOR, "agent", Map.of("agentDefinition", "planner",
                    "provider", "attacker", "instructions", "Graph override must not reach the action", "maxTurns", "999999"));
            var action = registry.create(node).orElseThrow();
            assertEquals(1, captured.size()); assertEquals("Approved planner", captured.getFirst().instructions());
            assertEquals("test-model", captured.getFirst().modelProfile());
            var message = new ai.ravenroot.api.execution.NodeMessage(ACTOR, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), Set.of(), "planner", "request", Map.of(),
                    ai.ravenroot.api.execution.NodeCommand.application("plan"));
            assertEquals("Approved planner", action.handle(message).toCompletableFuture().join().payload());
            assertTrue(store.loadRunnerWorkspace(new ExecutionKey(ACTOR.tenantId(), message.processInstanceId()))
                    .toCompletableFuture().join().isEmpty(), "conversational execution must not allocate a Workspace");
            var foreign = new ai.ravenroot.api.execution.NodeMessage(new SecurityContext("test", "foreign", "operator", PrincipalType.USER, "test"),
                    message.processInstanceId(), message.traversalId(), message.invocationId(), message.attemptId(), Set.of(),
                    message.nodeId(), message.payload(), Map.of(), message.command());
            assertThrows(CompletionException.class, () -> action.handle(foreign).toCompletableFuture().join());
            var resource = store.runnerResources("tenant").toCompletableFuture().join().stream()
                    .filter(value -> value.kind() == GovernedRunnerResource.Kind.AGENT_DEFINITION && value.name().equals("planner")).findFirst().orElseThrow();
            store.saveRunnerResource(new GovernedRunnerResource(resource.kind(), resource.tenantId(), resource.name(), resource.version(),
                    false, resource.document(), resource.revision(), "operator", CLOCK.instant()), resource.revision()).toCompletableFuture().join();
            assertThrows(CompletionException.class, () -> action.handle(message).toCompletableFuture().join());
            assertEquals("legacy", registry.create(GraphNode.behavior("legacy", "agent")).orElseThrow()
                    .handle(message).toCompletableFuture().join().payload());
        }
    }
    @Test void explicitLifecycleAndNamedAgentsRecoverTheirDirectEdges(@TempDir Path directory) throws Exception {
        var canonical = CanonicalGraphMl.of(GRAPH.getBytes(StandardCharsets.UTF_8));
        var key = new ExecutionKey("tenant", UUID.randomUUID());
        UUID traversal = UUID.randomUUID(); String runtime = "fixture-runtime";
        Path database = directory.resolve("workspace.db");
        try (var graphs = new ai.ravenroot.core.persistence.InMemoryGraphDefinitionStore(CLOCK); var engine = new JoinTestEngine()) {
            graphs.put("tenant", GraphDefinitionIdentity.forSubmission(canonical.contentId()), canonical).toCompletableFuture().join();
            try (var store = new SqliteExecutionStore(database, CLOCK); var graph = GraphManager.readGraphMl(
                    new java.io.ByteArrayInputStream(canonical.bytes()))) {
                var service = service(store);
                long revision = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent()).apply(
                        new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.RUNNING,
                                Map.of(traversal, new Traversal(traversal, "start", TraversalStatus.RUNNING, Map.of()))),
                                new GraphVersionPin(canonical.contentId().value()))).build()).toCompletableFuture().join().revision();
                try (var runner = new GraphRunner(graph, engine, BehaviorRegistry.standard().withRunnerJobs(service), new ExecutionMonitor());
                     var recorder = ExecutionRecorder.open(store, key, "fixture", Duration.ofSeconds(30), revision)) {
                    var failure = assertThrows(CompletionException.class, () -> runner.execute(ACTOR, key.processInstanceId(), traversal,
                            Map.of("request", "develop"), canonical.contentId().value(), null, null, recorder).toCompletableFuture()
                            .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join());
                    assertInstanceOf(RunnerJobSuspension.class, failure.getCause());
                }
            }
            UUID workspace = null;
            var commands = List.of("open", "plan", "implement", "review", "close");
            var outcomes = List.of("ready", "answered", "completed", "approved", "closed");
            for (int i = 0; i < commands.size(); i++) {
                try (var store = new SqliteExecutionStore(database, CLOCK)) {
                    var service = service(store);
                    var before = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow();
                    var resource = before.workspaces().get("repository");
                    if (workspace == null) workspace = resource.workspaceId();
                    assertEquals(workspace, resource.workspaceId());
                    var active = before.jobs().values().stream().filter(entry -> entry.job().retainsWorkspace()).toList();
                    assertEquals(1, active.size());
                    var job = active.getFirst().job();
                    assertEquals(commands.get(i), job.command().name());
                    assertEquals("repository", active.getFirst().workspaceNodeId());
                    if (i > 0) assertEquals(runtime, resource.runtimeId());
                    service.mutate(ACTOR, key, new RunnerJobOperation.Claim(job.identity().runnerJobId(), "worker-a", Duration.ofSeconds(30), WORKER_SESSION));
                    service.mutate(ACTOR, key, new RunnerJobOperation.Complete(job.identity().runnerJobId(), "worker-a", 1,
                            new RunnerResult(outcomes.get(i), OpaquePayload.of(RunnerJson.write(Map.of("result", "fixture-" + i)),
                                    "application/json"), List.of(), UUID.randomUUID(),
                                    new RunnerResult.WorkspaceObservation(resource.workspaceId(), runtime, null))));
                    try (var continuation = new PinnedRunnerContinuationExecutor(service, graphs, engine,
                            BehaviorRegistry.standard().withRunnerJobs(service), new ExecutionMonitor(), GraphExecutionLimits.DEFAULTS, null)) {
                        continuation.resume(key, job.identity().runnerJobId()).toCompletableFuture().orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join();
                    }
                }
            }
            try (var store = new SqliteExecutionStore(database, CLOCK)) {
                assertEquals(ProcessInstanceStatus.COMPLETED, store.load(key).toCompletableFuture().join().state().status());
                assertEquals(WorkspaceResource.State.CLOSED, store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow()
                        .workspaces().get("repository").state());
            }
        }
    }
}
