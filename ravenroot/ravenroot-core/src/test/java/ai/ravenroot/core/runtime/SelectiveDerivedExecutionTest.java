package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.core.persistence.InMemoryExecutionManifestStore;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.persistence.InMemoryGraphDefinitionStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SelectiveDerivedExecutionTest {
    private static final String GRAPH = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <graph id="selective" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="A"><data key="kind">BEHAVIOR</data><data key="behavior">effect-a</data></node>
                <node id="B"><data key="kind">BEHAVIOR</data><data key="behavior">effect-b</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="s-a" source="start" target="A"/>
                <edge id="a-b" source="A" target="B"/>
                <edge id="b-e" source="B" target="end"/>
              </graph>
            </graphml>
            """;

    @Test
    void cancelledSourceAfterAContinuesAtBWithoutRepeatingA() throws Exception {
        var aCalls = new AtomicInteger();
        var bCalls = new AtomicInteger();
        var bInput = new AtomicReference<Object>();
        var aGate = new AtomicReference<CompletableFuture<NodeResult>>();
        var behaviors = new BehaviorRegistry()
                .register("effect-a", message -> {
                    aCalls.incrementAndGet();
                    var gate = aGate.get();
                    return gate == null ? CompletableFuture.completedFuture(
                            NodeResult.continueWith(Map.of("from", "A"))) : gate;
                })
                .register("effect-b", message -> {
                    bCalls.incrementAndGet();
                    bInput.set(message.payload());
                    return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                });
        try (var executions = new InMemoryExecutionStore();
             var definitions = new InMemoryGraphDefinitionStore(Clock.systemUTC(),
                     executions.graphDefinitionReferences());
             var manifests = new InMemoryExecutionManifestStore(Clock.systemUTC(),
                     ExecutionManifestReferences.NONE);
             var app = new DefaultRavenrootApplication(new SameThreadExecutionEngine(),
                     new ExecutionMonitor(), behaviors,
                     new ai.ravenroot.core.programming.InMemoryArtifactRegistry(),
                     new ai.ravenroot.core.programming.DisabledProgramRuntime(),
                     ExecutionIdentitySource.randomUuids(), executions, 0,
                     UnknownBehaviorPolicy.passThrough(), definitions, null, null,
                     GraphExecutionLimits.DEFAULTS, null, manifests)) {
            var baseline = app.startGraphMl(TestIdentities.TENANT_A, UUID.randomUUID(),
                    new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)), Map.of("initial", true));
            var baselineKey = new ExecutionKey(TestIdentities.TENANT_A.tenantId(), baseline.processInstanceId());
            var baselineManifest = app.executionManifests().verify(baselineKey, ExecutionPolicy.STANDARD);

            aCalls.set(0);
            bCalls.set(0);
            bInput.set(null);
            var gate = new CompletableFuture<NodeResult>();
            aGate.set(gate);
            var sourceSubmission = app.startGraphMl(TestIdentities.TENANT_A, UUID.randomUUID(),
                    new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)), Map.of("source", true));
            var source = new ExecutionKey(TestIdentities.TENANT_A.tenantId(),
                    sourceSubmission.processInstanceId());
            assertEquals(1, aCalls.get(), "A is the real source effect and is waiting before completion");
            assertTrue(app.pauseTraversal(sourceSubmission.traversalId()));
            gate.complete(NodeResult.continueWith(Map.of("from", "A")));
            assertEquals(0, bCalls.get(), "the pause holds the first pending B dispatch");
            assertTrue(app.cancelTraversal(sourceSubmission.traversalId()));
            awaitCondition(() -> executions.replaySettlement(source).toCompletableFuture().join().isPresent());
            var settlement = executions.replaySettlement(source).toCompletableFuture().join().orElseThrow();
            var retained = executions.replayEvidence(source, 2).toCompletableFuture().join();
            var evidence = retained.stream().filter(value -> value.nodeId().equals("A"))
                    .findFirst().orElseThrow();
            UUID invocation = evidence.invocationId();
            assertEquals(1, aCalls.get());
            assertEquals(0, bCalls.get());

            var request = new DerivedExecutionRequest(List.of(new ReplayBoundarySeed("B", Set.of(invocation))),
                    "continue-at-b", "resume after settled cancellation", "B is authorized to repeat", true);
            var preview = app.previewDerivedExecution(TestIdentities.TENANT_A, source.processInstanceId(), request);
            assertTrue(preview.admissible(), () -> preview.refusalCodes().toString());
            assertTrue(preview.inheritedEvidence().contains(invocation));
            assertEquals(2, preview.inheritedEvidence().size(),
                    "the exact retained closure includes START and A");
            assertEquals(List.of("B", "end"), preview.scopeNodeIds());

            var started = app.startDerivedExecution(TestIdentities.TENANT_A,
                    source.processInstanceId(), request);
            var outcome = assertInstanceOf(ExecutionLookup.Found.class,
                    app.executionResult(TestIdentities.TENANT_A.tenantId(), started.traversalId()));
            assertEquals(ProcessInstanceStatus.COMPLETED, outcome.outcome().status());
            assertEquals(1, aCalls.get(), "completed predecessor A must remain historical evidence");
            assertEquals(1, bCalls.get(), "the pending boundary B runs exactly once in the new execution");
            assertEquals(Map.of("from", "A"), bInput.get());
            assertEquals(ProcessInstanceStatus.FAILED, executions.load(source).toCompletableFuture().join()
                    .state().status(), "the cancelled source history is immutable");
            assertTrue(executions.derivedAncestry(new ExecutionKey(TestIdentities.TENANT_A.tenantId(),
                    started.processInstanceId())).toCompletableFuture().join().isPresent());

            // A second accepted derivation is left at its atomically admitted SCHEDULED boundary,
            // exactly the crash window recovery owns. The sweep must use the copied seed from the
            // derived ancestry, not consult live source state or mint a second B invocation.
            var recoveredKey = new ExecutionKey(TestIdentities.TENANT_A.tenantId(), UUID.randomUUID());
            UUID recoveredTraversal = UUID.randomUUID();
            UUID recoveredInvocation = UUID.randomUUID();
            UUID recoveredAttempt = UUID.randomUUID();
            var recoverySeed = new ReplayBoundarySeed("B", Set.of(invocation));
            var recoveredWork = new DerivedExecutionWork(recoveredKey, recoveredTraversal,
                    recoveredInvocation, recoveredAttempt, recoverySeed, "A", NodeCommand.PROCESS,
                    evidence.output(), evidence.attributes(), TestIdentities.TENANT_A);
            var recoveredAncestry = new DerivedExecutionAncestry(recoveredKey, source,
                    List.of(recoverySeed), recoveredWork, "recovery-fingerprint",
                    TestIdentities.TENANT_A.qualifiedIdentity(), "recover admitted B", "reviewed",
                    Instant.now());
            app.executionManifests().pinDerived(recoveredKey,
                    app.executionManifests().verify(source, ExecutionPolicy.STANDARD));
            var recoveredTraversalState = new Traversal(recoveredTraversal, "B",
                    TraversalStatus.ACCEPTED, Map.of());
            executions.apply(ExecutionBatch.to(recoveredKey).expecting(RevisionExpectation.notPresent())
                    .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(
                            recoveredKey.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                            Map.of(recoveredTraversal, recoveredTraversalState)),
                            new GraphVersionPin(baseline.graphVersion())))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                    .apply(new ExecutionTransition.TraversalTransitioned(recoveredTraversal,
                            TraversalStatus.RUNNING))
                    .apply(new ExecutionTransition.InvocationAdded(recoveredTraversal,
                            new NodeInvocation(recoveredInvocation, "B", Set.of(),
                                    NodeInvocationStatus.SCHEDULED, List.of(), NodeCommand.PROCESS)))
                    .apply(new ExecutionTransition.InvocationTransitioned(recoveredTraversal,
                            recoveredInvocation, NodeInvocationStatus.RUNNING))
                    .apply(new ExecutionTransition.AttemptAdded(recoveredTraversal, recoveredInvocation,
                            new NodeAttempt(recoveredAttempt, 1, NodeAttemptStatus.SCHEDULED)))
                    .recordDerivedAncestry(recoveredAncestry)
                    .requiringReplaySourceSettlement(settlement).build()).toCompletableFuture().join();

            String recoveryWorker = "derived-recovery";
            Duration recoveryTtl = Duration.ofSeconds(30);
            var authority = new ai.ravenroot.core.recovery.PinnedGraphRecoveryAuthority(
                    executions, definitions, app.executionManifests(), behaviors::descriptor,
                    GraphExecutionLimits.DEFAULTS);
            var coordinator = new ai.ravenroot.core.recovery.ExecutionRecoveryCoordinator(authority,
                    List.of(app.derivedExecutionRecoveryDispatcher(recoveryWorker, recoveryTtl)));
            var recovery = new ai.ravenroot.core.recovery.ExecutionRecoveryService(executions,
                    List.of(TestIdentities.TENANT_A.tenantId()), recoveryWorker, 10, recoveryTtl,
                    coordinator.declarations(), coordinator);
            var recoveryOutcomes = recovery.sweepOnce();
            assertEquals(1, recoveryOutcomes.size());
            awaitCondition(() -> executions.load(recoveredKey).toCompletableFuture().join()
                    .state().status() == ProcessInstanceStatus.COMPLETED);
            assertEquals(1, aCalls.get(), "restart recovery must not repeat source A");
            assertEquals(2, bCalls.get(), "restart recovery dispatches the copied B seed once");
            var recoveredState = executions.load(recoveredKey).toCompletableFuture().join().state();
            var recoveredB = recoveredState.traversals().get(recoveredTraversal).invocations().values()
                    .stream().filter(value -> value.nodeId().equals("B")).toList();
            assertEquals(1, recoveredB.size());
            assertEquals(recoveredInvocation, recoveredB.getFirst().invocationId(),
                    "recovery reuses the atomically admitted boundary invocation");
        }
    }

    private static OpaquePayload json(Object value) {
        String encoded = ai.ravenroot.api.payload.PayloadJson.write(
                ai.ravenroot.api.payload.PayloadValue.fromJava(value,
                        ai.ravenroot.api.payload.PayloadLimits.DEFAULTS));
        return OpaquePayload.of(encoded.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("condition was not met before the deadline");
            Thread.sleep(10);
        }
    }
}
