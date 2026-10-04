package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.registry.*;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.flow.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.deployment.registry.InMemoryDeploymentRegistry;
import ai.ravenroot.core.flow.DefaultFlowInvocationCapability;
import ai.ravenroot.core.flow.FlowInvocationPolicy;
import ai.ravenroot.core.flow.FlowTargetAuthorizer;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class DefaultFlowInvocationCapabilityTest {
    private static final String TENANT = "acme";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final SecurityContext CALLER = new SecurityContext(
            "request", TENANT, "alice", PrincipalType.USER, "issuer");

    @TempDir Path directory;

    @Test
    void registeredChildrenArePinnedAuthorizedIdempotentAndSettleAllTerminalOutcomes() throws Exception {
        try (var store = new SqliteExecutionStore(directory.resolve("flows.db"), CLOCK);
             var engine = new SameThreadExecutionEngine()) {
            var tasks = new HumanTaskService(store, CLOCK);
            var behaviors = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults())
                    .register("fail-child", message -> CompletableFuture.failedFuture(
                            new IllegalStateException("child failed")))
                    .register("block-child", message -> new CompletableFuture<>());
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), behaviors,
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                    ExecutionIdentitySource.randomUuids(), store);
            var registry = new InMemoryDeploymentRegistry(CLOCK);
            GraphVersion successV1 = create(registry, "success", graph(null, "v1"), CALLER.qualifiedIdentity());
            GraphVersion failure = create(registry, "failure", graph("fail-child", "failure"),
                    CALLER.qualifiedIdentity());
            GraphVersion blocked = create(registry, "blocked", graph("block-child", "blocked"),
                    CALLER.qualifiedIdentity());
            append(registry, successV1, graph(null, "v2"), CALLER.qualifiedIdentity());

            try (var flows = new DefaultFlowInvocationCapability(store, registry, application, tasks,
                    FlowTargetAuthorizer.creatorOwnedTargets(),
                    new FlowInvocationPolicy(100, Duration.ofHours(1), Duration.ofDays(1)), CLOCK)) {
                behaviors.withFlowInvocations(flows);

                FlowTarget pinnedV1 = new FlowTarget(successV1.deploymentId(), 1);
                CallerFixture calling = caller(store, CALLER, Map.of("input", "call-flow"));
                FlowHandle callingHandle = flows.start(calling.message(), pinnedV1,
                        calling.message().payload(), Duration.ofMinutes(1)).toCompletableFuture().join();
                assertEquals(FlowInvocationStatus.COMPLETED, terminal(store, callingHandle).status());
                NodeResult callFlowResult = behaviors.create(new GraphNode("call", NodeKind.BEHAVIOR, "call-flow",
                                Map.of("deploymentId", successV1.deploymentId().value(), "version", 1L,
                                        "deadlineMs", 60_000L))).orElseThrow()
                        .handle(calling.message()).toCompletableFuture().join();
                assertEquals("completed", callFlowResult.outcome());
                assertEquals(Map.of("input", "call-flow"), callFlowResult.payload(),
                        "call-flow must return the registered child's terminal output downstream");

                CallerFixture successCaller = caller(store, CALLER);
                CompletableFuture<FlowHandle> first = flows.start(successCaller.message(), pinnedV1,
                        Map.of("input", 1), Duration.ofMinutes(1)).toCompletableFuture();
                CompletableFuture<FlowHandle> duplicate = flows.start(successCaller.message(), pinnedV1,
                        Map.of("input", 999), Duration.ofMinutes(1)).toCompletableFuture();
                FlowHandle handle = first.join();
                assertEquals(handle, duplicate.join(), "concurrent caller retry must reuse one relation");
                FlowInvocationRecord completed = terminal(store, handle);
                assertEquals(FlowInvocationStatus.COMPLETED, completed.status());
                assertEquals(1, completed.targetVersion());
                assertEquals(successV1.canonicalDigest(), completed.targetDigest(),
                        "a later registry version must not retarget the accepted child");
                assertNotEquals(successCaller.key().processInstanceId(), completed.childProcessInstanceId());

                FlowInvocationResult immediate = flows.await(successCaller.message(), handle)
                        .toCompletableFuture().join();
                assertEquals(FlowInvocationStatus.COMPLETED, immediate.status(),
                        "a child that completed before await must return without parking the caller");
                int committedInput = ((Number) ((Map<?, ?>) immediate.output()).get("input")).intValue();
                assertTrue(committedInput == 1 || committedInput == 999,
                        "one of the racing inputs must become the single committed child input");
                assertEquals(successCaller.message().invocationId(),
                        store.loadFlowInvocation(TENANT, handle).toCompletableFuture().join().orElseThrow()
                                .continuationClaim());
                FlowInvocationObservation observation = flows.observe(TENANT, handle).toCompletableFuture().join()
                        .orElseThrow();
                assertEquals(successCaller.key().processInstanceId(), observation.callerProcessInstanceId());
                assertEquals(completed.childProcessInstanceId(), observation.childProcessInstanceId());
                assertEquals(successV1.deploymentId(), observation.targetDeploymentId());
                assertEquals(FlowInvocationStatus.COMPLETED, observation.status());
                assertTrue(observation.awaited());
                assertTrue(flows.observe("other", handle).toCompletableFuture().join().isEmpty());
                assertEquals(immediate, flows.await(successCaller.message(), handle).toCompletableFuture().join(),
                        "the accepted invocation may safely retry its terminal await");
                NodeMessage competingAwait = new NodeMessage(CALLER, successCaller.key().processInstanceId(),
                        successCaller.message().traversalId(), UUID.randomUUID(), UUID.randomUUID(), Set.of(),
                        "another-await", null, Map.of(), ai.ravenroot.api.execution.NodeCommand.PROCESS);
                assertInstanceOf(IllegalStateException.class, failure(() -> flows.await(competingAwait, handle)
                        .toCompletableFuture().join()));
                assertTrue(store.loadHumanTask(TENANT, handle.value()).toCompletableFuture().join().isEmpty());
                assertTrue(tasks.inbox(requestContext(), HumanTaskQuery.everything(10)).items().isEmpty());

                CallerFixture recoveryCaller = caller(store, CALLER);
                FlowHandle recoveryHandle = new FlowHandle(UUID.randomUUID());
                UUID reservedChildProcess = UUID.randomUUID();
                UUID reservedChildTraversal = UUID.randomUUID();
                byte[] recoveryInput = ai.ravenroot.api.payload.PayloadJson.writeJava(
                        Map.of("input", "recovered-intent"), ai.ravenroot.api.payload.PayloadLimits.DEFAULTS);
                FlowInvocationRecord recoveryIntent = new FlowInvocationRecord(TENANT, recoveryHandle,
                        recoveryCaller.key().processInstanceId(), recoveryCaller.message().traversalId(),
                        recoveryCaller.message().invocationId(), CALLER.subject(), CALLER.principalType(),
                        CALLER.issuer(), pinnedV1.deploymentId(), pinnedV1.version(),
                        successV1.canonicalDigest(), reservedChildProcess, reservedChildTraversal,
                        FlowInvocationStatus.INTENT, recoveryInput, null, "", "", null, 1, NOW, NOW,
                        NOW.plusSeconds(60), NOW.plusSeconds(120));
                store.createFlowInvocation(recoveryIntent).toCompletableFuture().join();
                flows.recoverTenant(TENANT).toCompletableFuture().join();
                FlowInvocationRecord recovered = terminal(store, recoveryHandle);
                assertEquals(FlowInvocationStatus.COMPLETED, recovered.status());
                assertEquals(reservedChildProcess, recovered.childProcessInstanceId());
                assertEquals(reservedChildTraversal, recovered.childTraversalId());
                assertInstanceOf(ExecutionLookup.Found.class,
                        application.executionResult(TENANT, reservedChildTraversal));
                long recoveredRevision = recovered.revision();
                flows.recoverTenant(TENANT).toCompletableFuture().join();
                assertEquals(recoveredRevision, store.loadFlowInvocation(TENANT, recoveryHandle)
                        .toCompletableFuture().join().orElseThrow().revision(),
                        "recovery must not relaunch or rewrite a terminal recovered intent");

                CallerFixture unauthorized = caller(store, new SecurityContext(
                        "other", TENANT, "mallory", PrincipalType.USER, "issuer"));
                assertInstanceOf(SecurityException.class, failure(() -> flows.start(unauthorized.message(), pinnedV1,
                        null, Duration.ofMinutes(1)).toCompletableFuture().join()));
                CallerFixture crossTenant = caller(store, new SecurityContext(
                        "cross", "other", "alice", PrincipalType.USER, "issuer"));
                assertInstanceOf(IllegalArgumentException.class, failure(() -> flows.start(crossTenant.message(),
                        pinnedV1, null, Duration.ofMinutes(1)).toCompletableFuture().join()));

                CallerFixture failedCaller = caller(store, CALLER);
                FlowHandle failedHandle = flows.start(failedCaller.message(),
                        new FlowTarget(failure.deploymentId(), 1), null, Duration.ofMinutes(1))
                        .toCompletableFuture().join();
                assertEquals(FlowInvocationStatus.FAILED, terminal(store, failedHandle).status());

                CallerFixture cancelledCaller = caller(store, CALLER);
                FlowHandle cancelledHandle = flows.start(cancelledCaller.message(),
                        new FlowTarget(blocked.deploymentId(), 1), null, Duration.ofMinutes(1))
                        .toCompletableFuture().join();
                assertEquals(FlowInvocationStatus.CANCELLED,
                        flows.cancel(cancelledCaller.message(), cancelledHandle, "test")
                                .toCompletableFuture().join().status());

                CallerFixture deadlineCaller = caller(store, CALLER);
                FlowHandle deadlineHandle = flows.start(deadlineCaller.message(),
                        new FlowTarget(blocked.deploymentId(), 1), null, Duration.ofMillis(20))
                        .toCompletableFuture().join();
                assertEquals(FlowInvocationStatus.DEADLINE_EXCEEDED, terminal(store, deadlineHandle).status());
            } finally {
                application.close();
            }
        }
    }

    @Test
    void publishedTargetAndCallerExamplesParseAndExecuteTogether() throws Exception {
        try (var store = new SqliteExecutionStore(directory.resolve("examples.db"), CLOCK);
             var engine = new SameThreadExecutionEngine()) {
            var tasks = new HumanTaskService(store, CLOCK);
            var behaviors = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults());
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), behaviors,
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                    ExecutionIdentitySource.randomUuids(), store);
            var registry = new InMemoryDeploymentRegistry(CLOCK);
            Path examples = Path.of("../../docs/examples/intergraph");
            byte[] target = Files.readAllBytes(examples.resolve("callable-target.graphml"));
            GraphVersion version = create(registry, "documented-target", target, CALLER.qualifiedIdentity());
            byte[] caller = Files.readString(examples.resolve("call-flow.graphml"))
                    .replace("replace-with-registered-deployment-id", version.deploymentId().value())
                    .getBytes(StandardCharsets.UTF_8);
            try (var flows = new DefaultFlowInvocationCapability(store, registry, application, tasks,
                    FlowTargetAuthorizer.creatorOwnedTargets(),
                    new FlowInvocationPolicy(100, Duration.ofHours(1), Duration.ofDays(1)), CLOCK)) {
                behaviors.withFlowInvocations(flows);
                try (var parsed = GraphManager.readGraphMl(new ByteArrayInputStream(caller))) {
                    GraphNode documentedCall = parsed.definition().nodes().stream()
                            .filter(node -> node.id().equals("call")).findFirst().orElseThrow();
                    CallerFixture fixture = caller(store, CALLER, Map.of("docs", "execute"));
                    FlowHandle handle = flows.start(fixture.message(),
                            new FlowTarget(version.deploymentId(), version.version()),
                            fixture.message().payload(), Duration.ofSeconds(30)).toCompletableFuture().join();
                    assertEquals(FlowInvocationStatus.COMPLETED, terminal(store, handle).status());
                    NodeResult result = behaviors.create(documentedCall).orElseThrow()
                            .handle(fixture.message()).toCompletableFuture().join();
                    assertEquals("completed", result.outcome());
                    assertEquals(Map.of("docs", "execute"), result.payload());
                }
            } finally {
                application.close();
            }
        }
    }

    @Test
    void unfinishedTenantQuotaRefusesAnotherFreshChild() {
        try (var store = new SqliteExecutionStore(directory.resolve("quota.db"), CLOCK);
             var engine = new SameThreadExecutionEngine()) {
            var tasks = new HumanTaskService(store, CLOCK);
            var behaviors = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults())
                    .register("block-child", message -> new CompletableFuture<>());
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), behaviors,
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                    ExecutionIdentitySource.randomUuids(), store);
            var registry = new InMemoryDeploymentRegistry(CLOCK);
            GraphVersion blocked = create(registry, "quota-blocked", graph("block-child", "blocked"),
                    CALLER.qualifiedIdentity());
            try (var flows = new DefaultFlowInvocationCapability(store, registry, application, tasks,
                    FlowTargetAuthorizer.creatorOwnedTargets(),
                    new FlowInvocationPolicy(1, Duration.ofHours(1), Duration.ofDays(1)), CLOCK)) {
                FlowTarget target = new FlowTarget(blocked.deploymentId(), blocked.version());
                CallerFixture admitted = caller(store, CALLER);
                FlowHandle handle = flows.start(admitted.message(), target, null, Duration.ofMinutes(1))
                        .toCompletableFuture().join();
                CallerFixture refused = caller(store, CALLER);
                assertInstanceOf(IllegalStateException.class, failure(() -> flows.start(refused.message(),
                        target, null, Duration.ofMinutes(1)).toCompletableFuture().join()));
                flows.cancel(admitted.message(), handle, "test cleanup").toCompletableFuture().join();
            } finally {
                application.close();
            }
        }
    }

    private static GraphVersion create(InMemoryDeploymentRegistry registry, String key, byte[] bytes,
                                       String creator) {
        var record = registry.create(new GraphVersion.Content(1, bytes, creator, NOW),
                new DeploymentRegistry.CreateCommand(TENANT, key, "a".repeat(64), true))
                .toCompletableFuture().join();
        return registry.version(TENANT, record.deploymentId(), 1).toCompletableFuture().join().orElseThrow();
    }

    private static void append(InMemoryDeploymentRegistry registry, GraphVersion current, byte[] bytes,
                               String creator) {
        DeploymentRegistry.Record record = registry.get(TENANT, current.deploymentId())
                .toCompletableFuture().join().orElseThrow();
        registry.append(2, new GraphVersion.Content(1, bytes, creator, NOW),
                new DeploymentRegistry.Command(TENANT, current.deploymentId(), "append-v2", "b".repeat(64),
                        RevisionExpectation.exactly(record.revision()), GenerationExpectation.any()))
                .toCompletableFuture().join();
    }

    private static CallerFixture caller(ExecutionStore store, SecurityContext security) {
        return caller(store, security, null);
    }

    private static CallerFixture caller(ExecutionStore store, SecurityContext security, Object payload) {
        ExecutionKey key = new ExecutionKey(security.tenantId(), UUID.randomUUID());
        UUID traversalId = UUID.randomUUID(), invocationId = UUID.randomUUID(), attemptId = UUID.randomUUID();
        var attempt = new NodeAttempt(attemptId, 1, NodeAttemptStatus.RUNNING);
        var invocation = new NodeInvocation(invocationId, "call", Set.of(), NodeInvocationStatus.RUNNING,
                List.of(attempt));
        var traversal = new Traversal(traversalId, "call", TraversalStatus.RUNNING, Map.of(invocationId, invocation));
        long revision = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                        ProcessInstanceStatus.RUNNING, Map.of(traversalId, traversal)),
                        new GraphVersionPin("caller-graph"))).build()).toCompletableFuture().join().revision();
        return new CallerFixture(key, revision, new NodeMessage(security, key.processInstanceId(), traversalId,
                invocationId, attemptId, Set.of(), "call", payload, Map.of(),
                ai.ravenroot.api.execution.NodeCommand.PROCESS));
    }

    private static FlowInvocationRecord terminal(ExecutionStore store, FlowHandle handle) throws Exception {
        final FlowInvocationRecord[] result = new FlowInvocationRecord[1];
        waitUntil(() -> {
            result[0] = store.loadFlowInvocation(TENANT, handle).toCompletableFuture().join().orElse(null);
            return result[0] != null && result[0].terminal();
        });
        return result[0];
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= until) fail("condition did not become true");
            Thread.sleep(10);
        }
    }

    private static Throwable failure(Runnable action) {
        CompletionException thrown = assertThrows(CompletionException.class, action::run);
        return thrown.getCause();
    }

    private static ai.ravenroot.api.security.RequestContext requestContext() {
        return new ai.ravenroot.api.security.RequestContext("request", "alice", PrincipalType.USER, "issuer",
                TENANT, Set.of(), Set.of());
    }

    private static byte[] graph(String behavior, String value) {
        String middle = behavior == null ? "" : """
                <node id="work"><data key="kind">BEHAVIOR</data><data key="behavior">%s</data></node>
                """.formatted(behavior);
        String edges = behavior == null
                ? "<edge id=\"a\" source=\"start\" target=\"end\"/>"
                : "<edge id=\"a\" source=\"start\" target=\"work\"/><edge id=\"b\" source=\"work\" target=\"end\"/>";
        return ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
                  <key id="label" for="graph" attr.name="label" attr.type="string"/>
                  <graph id="child" edgedefault="directed"><data key="label">%s</data>
                    <node id="start"><data key="kind">START</data></node>%s
                    <node id="end"><data key="kind">END</data></node>%s
                  </graph>
                </graphml>
                """).formatted(value, middle, edges).getBytes(StandardCharsets.UTF_8);
    }

    private record CallerFixture(ExecutionKey key, long revision, NodeMessage message) {}
}
