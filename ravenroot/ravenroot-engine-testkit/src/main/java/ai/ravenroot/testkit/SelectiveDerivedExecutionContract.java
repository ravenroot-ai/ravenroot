package ai.ravenroot.testkit;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.persistence.InMemoryExecutionManifestStore;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.persistence.InMemoryGraphDefinitionStore;
import ai.ravenroot.core.runtime.*;
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

public abstract class SelectiveDerivedExecutionContract {
    private static final SecurityContext IDENTITY = new SecurityContext(
            "selective-derived-request", "selective-derived-tenant", "operator",
            PrincipalType.USER, "urn:ravenroot:test");

    /** Supplies the engine adapter under test. */
    protected abstract ExecutionEngine createEngine(String systemName);
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

    private static final String JOIN_GRAPH = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <graph id="selective-join" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="A"><data key="kind">BEHAVIOR</data><data key="behavior">join-a</data></node>
                <node id="left"><data key="kind">BEHAVIOR</data><data key="behavior">join-left</data></node>
                <node id="right"><data key="kind">BEHAVIOR</data><data key="behavior">join-right</data></node>
                <node id="join"><data key="kind">BEHAVIOR</data><data key="behavior">join-node</data></node>
                <node id="B"><data key="kind">BEHAVIOR</data><data key="behavior">join-b</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="s-a" source="start" target="A"/>
                <edge id="a-l" source="A" target="left"/>
                <edge id="a-r" source="A" target="right"/>
                <edge id="l-j" source="left" target="join"/>
                <edge id="r-j" source="right" target="join"/>
                <edge id="j-b" source="join" target="B"/>
                <edge id="b-e" source="B" target="end"/>
              </graph>
            </graphml>
            """;

    @Test
    protected void boundaryAfterCompletedJoinUsesExactParentClosureAndRefusesTheJoinItself() throws Exception {
        var bCalls = new AtomicInteger();
        var behaviors = new BehaviorRegistry()
                .register("join-a", message -> CompletableFuture.completedFuture(NodeResult.continueWith("a")))
                .register("join-left", message -> CompletableFuture.completedFuture(NodeResult.continueWith("left")))
                .register("join-right", message -> CompletableFuture.completedFuture(NodeResult.continueWith("right")))
                .register("join-node", message -> CompletableFuture.completedFuture(NodeResult.continueWith("joined")))
                .register("join-b", message -> {
                    bCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                });
        try (var engine = createEngine("selective-derived-join-" + UUID.randomUUID());
             var executions = new InMemoryExecutionStore();
             var definitions = new InMemoryGraphDefinitionStore(Clock.systemUTC(),
                     executions.graphDefinitionReferences());
             var manifests = new InMemoryExecutionManifestStore(Clock.systemUTC(),
                     ExecutionManifestReferences.NONE);
             var app = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), behaviors,
                     new ai.ravenroot.core.programming.InMemoryArtifactRegistry(),
                     new ai.ravenroot.core.programming.DisabledProgramRuntime(),
                     ExecutionIdentitySource.randomUuids(), executions, 0,
                     UnknownBehaviorPolicy.passThrough(), definitions, null, null,
                     GraphExecutionLimits.DEFAULTS, null, manifests)) {
            var sourceStart = app.startGraphMl(IDENTITY, UUID.randomUUID(),
                    new ByteArrayInputStream(JOIN_GRAPH.getBytes(StandardCharsets.UTF_8)), Map.of());
            var source = new ExecutionKey(IDENTITY.tenantId(), sourceStart.processInstanceId());
            awaitCondition(() -> executions.replaySettlement(source).toCompletableFuture().join().isPresent());
            var evidence = executions.replayEvidence(source, 32).toCompletableFuture().join();
            var join = evidence.stream().filter(value -> value.nodeId().equals("join")
                    && value.parentInvocationIds().size() == 2).findFirst().orElseThrow();

            var request = new DerivedExecutionRequest(List.of(
                    new ReplayBoundarySeed("B", Set.of(join.invocationId()))),
                    "after-join", "continue after completed join", "B may run again", true);
            var preview = app.previewDerivedExecution(IDENTITY, source.processInstanceId(), request);
            assertTrue(preview.admissible(), () -> preview.refusalCodes().toString());
            assertTrue(preview.inheritedEvidence().containsAll(join.parentInvocationIds()));
            int before = bCalls.get();
            var started = app.startDerivedExecution(IDENTITY, source.processInstanceId(), request);
            awaitCondition(() -> app.executionResult(IDENTITY.tenantId(), started.traversalId())
                    instanceof ExecutionLookup.Found found && found.outcome().status().terminal());
            assertEquals(before + 1, bCalls.get());

            var directJoin = new DerivedExecutionRequest(List.of(
                    new ReplayBoundarySeed("join", join.parentInvocationIds())),
                    "at-join", "unsupported join boundary", "", true);
            var refused = app.previewDerivedExecution(IDENTITY, source.processInstanceId(), directJoin);
            assertFalse(refused.admissible());
            assertTrue(refused.refusalCodes().contains("UNSUPPORTED_JOIN_OR_MAPPED_BOUNDARY"));

            var unauthorized = new DerivedExecutionRequest(request.boundaries(), "effects-refused",
                    "prove effect authority", "", false);
            assertTrue(app.previewDerivedExecution(IDENTITY, source.processInstanceId(), unauthorized)
                    .refusalCodes().contains("EXTERNAL_EFFECT_AUTHORIZATION_REQUIRED"));
            var undecided = new DerivedExecutionRequest(request.boundaries(), "effects-undecided",
                    "prove reconciliation decision", "", true);
            assertTrue(app.previewDerivedExecution(IDENTITY, source.processInstanceId(), undecided)
                    .refusalCodes().contains("EFFECT_REPEATABILITY_DECISION_REQUIRED"));
        }
    }

    @Test
    protected void cancelledSourceAfterAContinuesAtBWithoutRepeatingA() throws Exception {
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
        try (var engine = createEngine("selective-derived-" + UUID.randomUUID());
             var executions = new InMemoryExecutionStore();
             var definitions = new InMemoryGraphDefinitionStore(Clock.systemUTC(),
                     executions.graphDefinitionReferences());
             var manifests = new InMemoryExecutionManifestStore(Clock.systemUTC(),
                     ExecutionManifestReferences.NONE);
             var app = new DefaultRavenrootApplication(engine,
                     new ExecutionMonitor(), behaviors,
                     new ai.ravenroot.core.programming.InMemoryArtifactRegistry(),
                     new ai.ravenroot.core.programming.DisabledProgramRuntime(),
                     ExecutionIdentitySource.randomUuids(), executions, 0,
                     UnknownBehaviorPolicy.passThrough(), definitions, null, null,
                     GraphExecutionLimits.DEFAULTS, null, manifests)) {
            var baseline = app.startGraphMl(IDENTITY, UUID.randomUUID(),
                    new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)), Map.of("initial", true));
            awaitCondition(() -> {
                var lookup = app.executionResult(IDENTITY.tenantId(), baseline.traversalId());
                return lookup instanceof ExecutionLookup.Found found && found.outcome().status().terminal();
            });
            var baselineKey = new ExecutionKey(IDENTITY.tenantId(), baseline.processInstanceId());
            var baselineManifest = app.executionManifests().verify(baselineKey, ExecutionPolicy.STANDARD);

            aCalls.set(0);
            bCalls.set(0);
            bInput.set(null);
            var gate = new CompletableFuture<NodeResult>();
            aGate.set(gate);
            var sourceSubmission = app.startGraphMl(IDENTITY, UUID.randomUUID(),
                    new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)), Map.of("source", true));
            var source = new ExecutionKey(IDENTITY.tenantId(),
                    sourceSubmission.processInstanceId());
            awaitCondition(() -> aCalls.get() == 1);
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
            var sourceAggregateBefore = executions.load(source).toCompletableFuture().join();
            var sourceResultBefore = app.executionResult(IDENTITY.tenantId(), sourceSubmission.traversalId());
            var sourceEventsBefore = executions.readProcessJournal(source, 0, 1_000)
                    .toCompletableFuture().join();

            var request = new DerivedExecutionRequest(List.of(new ReplayBoundarySeed("B", Set.of(invocation))),
                    "continue-at-b", "resume after settled cancellation", "B is authorized to repeat", true);
            var preview = app.previewDerivedExecution(IDENTITY, source.processInstanceId(), request);
            assertTrue(preview.admissible(), () -> preview.refusalCodes().toString());
            assertTrue(preview.inheritedEvidence().contains(invocation));
            assertEquals(2, preview.inheritedEvidence().size(),
                    "the exact retained closure includes START and A");
            assertEquals(List.of("B", "end"), preview.scopeNodeIds());

            var started = app.startDerivedExecution(IDENTITY,
                    source.processInstanceId(), request);
            awaitCondition(() -> {
                var lookup = app.executionResult(IDENTITY.tenantId(), started.traversalId());
                return lookup instanceof ExecutionLookup.Found found && found.outcome().status().terminal();
            });
            var outcome = assertInstanceOf(ExecutionLookup.Found.class,
                    app.executionResult(IDENTITY.tenantId(), started.traversalId()));
            assertEquals(ProcessInstanceStatus.COMPLETED, outcome.outcome().status());
            assertEquals(1, aCalls.get(), "completed predecessor A must remain historical evidence");
            assertEquals(1, bCalls.get(), "the pending boundary B runs exactly once in the new execution");
            assertEquals(Map.of("from", "A"), bInput.get());
            assertEquals(ProcessInstanceStatus.FAILED, executions.load(source).toCompletableFuture().join()
                    .state().status(), "the cancelled source history is immutable");
            assertEquals(sourceAggregateBefore, executions.load(source).toCompletableFuture().join());
            assertEquals(sourceResultBefore,
                    app.executionResult(IDENTITY.tenantId(), sourceSubmission.traversalId()));
            assertEquals(sourceEventsBefore, executions.readProcessJournal(source, 0, 1_000)
                    .toCompletableFuture().join());
            assertTrue(executions.derivedAncestry(new ExecutionKey(IDENTITY.tenantId(),
                    started.processInstanceId())).toCompletableFuture().join().isPresent());

            var concurrentRequest = new DerivedExecutionRequest(request.boundaries(),
                    "concurrent-derived", request.reason(), request.repeatabilityDecision(), true);
            int beforeConcurrent = bCalls.get();
            var concurrentA = CompletableFuture.supplyAsync(() -> app.startDerivedExecution(
                    IDENTITY, source.processInstanceId(), concurrentRequest));
            var concurrentB = CompletableFuture.supplyAsync(() -> app.startDerivedExecution(
                    IDENTITY, source.processInstanceId(), concurrentRequest));
            var concurrentFirst = concurrentA.join();
            var concurrentSecond = concurrentB.join();
            assertEquals(concurrentFirst, concurrentSecond,
                    "concurrent duplicate admission must converge on one derived identity");
            awaitCondition(() -> bCalls.get() == beforeConcurrent + 1);
            var otherTenant = new SecurityContext("other-request", "other-tenant", "operator",
                    PrincipalType.USER, "urn:ravenroot:test");
            assertThrows(RuntimeException.class, () -> app.previewDerivedExecution(
                    otherTenant, source.processInstanceId(), request),
                    "a source identifier must not become a cross-tenant evidence oracle");

            // A second accepted derivation is left at its atomically admitted SCHEDULED boundary,
            // exactly the crash window recovery owns. The sweep must use the copied seed from the
            // derived ancestry, not consult live source state or mint a second B invocation.
            var recoveredKey = new ExecutionKey(IDENTITY.tenantId(), UUID.randomUUID());
            UUID recoveredTraversal = UUID.randomUUID();
            UUID recoveredInvocation = UUID.randomUUID();
            UUID recoveredAttempt = UUID.randomUUID();
            var recoverySeed = new ReplayBoundarySeed("B", Set.of(invocation));
            var recoveredWork = new DerivedExecutionWork(recoveredKey, recoveredTraversal,
                    recoveredInvocation, recoveredAttempt, recoverySeed, "A", NodeCommand.PROCESS,
                    evidence.output(), evidence.attributes(), IDENTITY);
            var recoveredAncestry = new DerivedExecutionAncestry(recoveredKey, source,
                    List.of(recoverySeed), recoveredWork, "recovery-fingerprint",
                    IDENTITY.qualifiedIdentity(), "recover admitted B", "reviewed",
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
                    List.of(IDENTITY.tenantId()), recoveryWorker, 10, recoveryTtl,
                    coordinator.declarations(), coordinator);
            var recoveryOutcomes = recovery.sweepOnce();
            assertEquals(1, recoveryOutcomes.size());
            awaitCondition(() -> executions.load(recoveredKey).toCompletableFuture().join()
                    .state().status() == ProcessInstanceStatus.COMPLETED);
            assertEquals(1, aCalls.get(), "restart recovery must not repeat source A");
            assertEquals(beforeConcurrent + 2, bCalls.get(),
                    "restart recovery dispatches the copied B seed once");
            var recoveredState = executions.load(recoveredKey).toCompletableFuture().join().state();
            var recoveredB = recoveredState.traversals().get(recoveredTraversal).invocations().values()
                    .stream().filter(value -> value.nodeId().equals("B")).toList();
            assertEquals(1, recoveredB.size());
            assertEquals(recoveredInvocation, recoveredB.getFirst().invocationId(),
                    "recovery reuses the atomically admitted boundary invocation");
            assertEquals(sourceAggregateBefore, executions.load(source).toCompletableFuture().join());
            assertEquals(sourceResultBefore,
                    app.executionResult(IDENTITY.tenantId(), sourceSubmission.traversalId()));
            assertEquals(sourceEventsBefore, executions.readProcessJournal(source, 0, 1_000)
                    .toCompletableFuture().join());
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
