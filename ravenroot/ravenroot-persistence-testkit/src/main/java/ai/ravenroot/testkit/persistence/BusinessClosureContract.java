package ai.ravenroot.testkit.persistence;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.execution.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.security.*;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Atomic authored close barriers, pre-admitted causal continuations and immutable derivation lineage. */
@Timeout(120)
public abstract class BusinessClosureContract {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final BusinessScope SCOPE = new BusinessScope("tenant", "deployment", "graph-v1");
    private static final SecurityContext OWNER = new SecurityContext("request", "tenant", "author", PrincipalType.WORKLOAD, "issuer");
    protected abstract ExecutionStore createStore(String id, Clock clock);
    private ExecutionStore createStore(String id) { return createStore(id, Clock.fixed(NOW, ZoneOffset.UTC)); }
    private record Work(CorrelatedTraversal relation, UUID invocation, UUID attempt, LeaseHandle lease, long revision) { }

    @Test void closeSignalBlocksNewExternalAdmissionsImmediatelyAndFinalizesWithTheOriginalTraversal() throws Exception {
        try (var store = createStore(id())) {
            var work = work(store, "closed", "part-1"); var signal = signal(work, BusinessHistory.Lifecycle.COMPLETED);
            var requested = completeVisit(store, work, signal);
            var closing = await(store.businessHistory(SCOPE, "closed")).orElseThrow();
            assertEquals(BusinessHistory.Lifecycle.OPEN, closing.lifecycle()); assertEquals(1, closing.runningTraversals());
            assertEquals(signal, closing.closureSignal()); assertEquals(BusinessHistory.State.RUNNING, closing.state());
            assertThrows(Exception.class, () -> await(store.recordCorrelatedTraversal(relation("closed", "late"), CorrelationMapping.Mode.ACCEPT_OR_CREATE)));
            assertEquals(closing, await(store.recordCorrelatedTraversal(work.relation(), CorrelationMapping.Mode.ACCEPT_OR_CREATE)), "prior admitted retries remain valid");
            terminal(store, work, requested.revision(), TraversalStatus.COMPLETED);
            var closed = await(store.businessHistory(SCOPE, "closed")).orElseThrow();
            assertEquals(BusinessHistory.Lifecycle.COMPLETED, closed.lifecycle()); assertEquals(BusinessHistory.State.COMPLETED, closed.state());
            assertEquals(signal, closed.closureSignal()); assertEquals(1, closed.completedTraversals());
        }
    }

    @Test void preAdmittedArrivalsAndReadyContinuationSettleAcrossTheClosingBarrier() throws Exception {
        try (var store = createStore(id())) {
            var first = work(store, "causal", "part-1"); var second = work(store, "causal", "part-2");
            var key = new CorrelatedJoinKey(SCOPE, "causal", "join", "0");
            var opened = CorrelatedJoinRecord.open(key, Set.of(), 2, true, cause(first), NOW, NOW.plusSeconds(100));
            await(store.compareAndSetCorrelatedJoin(opened));
            var firstJoin = await(store.compareAndSetCorrelatedJoin(opened.contribute(contribution(first, "part-1"), Set.of(), null, false, NOW, store.businessHistoryPolicy())));
            var firstCompleted = completeVisit(store, first, signal(first, BusinessHistory.Lifecycle.COMPLETED));
            assertThrows(Exception.class, () -> await(store.recordCorrelatedTraversal(relation("causal", "new-external"), CorrelationMapping.Mode.ACCEPT_OR_CREATE)));
            var ready = await(store.compareAndSetCorrelatedJoin(firstJoin.contribute(contribution(second, "part-2"), Set.of(), null, false, NOW, store.businessHistoryPolicy())));
            assertEquals(CorrelatedJoinRecord.Phase.READY, ready.phase());
            terminal(store, first, firstCompleted.revision(), TraversalStatus.COMPLETED);
            var secondCompleted = completeVisit(store, second, null); terminal(store, second, secondCompleted.revision(), TraversalStatus.COMPLETED);
            var waiting = await(store.businessHistory(SCOPE, "causal")).orElseThrow();
            assertEquals(BusinessHistory.Lifecycle.OPEN, waiting.lifecycle()); assertEquals(BusinessHistory.State.CLOSING, waiting.state());
            assertEquals(0, waiting.runningTraversals());
            assertThrows(Exception.class, () -> await(store.closeBusinessHistory(SCOPE, "causal", waiting.revision(), BusinessHistory.Lifecycle.COMPLETED)),
                    "an immediate close cannot strand the already-ready outbox");
            var continuation = new CorrelatedTraversal(SCOPE, new ApplicationCorrelationContext("causal", Optional.empty(), Optional.empty(), "0", Optional.empty()), key.continuationProcessId(), key.continuationTraversalId(), "join",
                    CorrelatedTraversal.Cause.JOIN_CONTINUATION, TraversalStatus.ACCEPTED, null, NOW, NOW, null);
            var forged = new CorrelatedTraversal(SCOPE, continuation.context(), UUID.randomUUID(), UUID.randomUUID(), "join",
                    CorrelatedTraversal.Cause.JOIN_CONTINUATION, TraversalStatus.ACCEPTED, null, NOW, NOW, null);
            assertThrows(Exception.class, () -> await(store.recordCorrelatedTraversal(forged, CorrelationMapping.Mode.REQUIRE_EXISTING)),
                    "a cause label alone cannot bypass the closing barrier without exact READY outbox identities");
            await(store.recordCorrelatedTraversal(continuation, CorrelationMapping.Mode.REQUIRE_EXISTING));
            await(store.settleCorrelatedTraversal("tenant", continuation.processInstanceId(), continuation.traversalId(), TraversalStatus.COMPLETED, null));
            assertEquals(BusinessHistory.State.CLOSING, await(store.businessHistory(SCOPE, "causal")).orElseThrow().state(), "lost outbox acknowledgement is still pending");
            await(store.compareAndSetCorrelatedJoin(ready.dispatched(NOW)));
            var closed = await(store.businessHistory(SCOPE, "causal")).orElseThrow();
            assertEquals(BusinessHistory.Lifecycle.COMPLETED, closed.lifecycle()); assertEquals(3, closed.completedTraversals());
            assertEquals(0, closed.runningTraversals()); assertEquals(2, await(store.correlatedJoin(key)).orElseThrow().contributions().size());
        }
    }

    @Test void missingContributionsAndOriginalFailuresCannotBecomeSuccessFromQuietness() throws Exception {
        try (var store = createStore(id())) {
            var work = work(store, "missing", "part-1"); var key = new CorrelatedJoinKey(SCOPE, "missing", "join", "0");
            var opened = CorrelatedJoinRecord.open(key, Set.of("part-1", "part-2"), null, true, cause(work), NOW, NOW.plusSeconds(100));
            await(store.compareAndSetCorrelatedJoin(opened));
            await(store.compareAndSetCorrelatedJoin(opened.contribute(contribution(work, "part-1"), Set.of(), null, false, NOW, store.businessHistoryPolicy())));
            var completed = completeVisit(store, work, signal(work, BusinessHistory.Lifecycle.COMPLETED));
            terminal(store, work, completed.revision(), TraversalStatus.COMPLETED);
            var closed = await(store.businessHistory(SCOPE, "missing")).orElseThrow();
            assertEquals(BusinessHistory.Lifecycle.FAILED, closed.lifecycle()); assertTrue(closed.incompleteHistory());
            assertEquals(BusinessHistory.Lifecycle.COMPLETED, closed.closureSignal().requested(), "the requested outcome is retained independently of the actual verdict");
            assertEquals(CorrelatedJoinRecord.Phase.FAILED, await(store.correlatedJoin(key)).orElseThrow().phase());
            var missing = await(store.correlatedJoinMembers(key, JoinMemberFilter.MISSING, Optional.empty(), 10));
            assertEquals(List.of("part-2"), missing.items().stream().map(JoinMemberSummary::contributionId).toList());
            assertEquals(1, await(store.correlatedJoin(key)).orElseThrow().contributions().size());
            var failed = work(store, "failed", "part-1");
            var marker = completeVisit(store, failed, signal(failed, BusinessHistory.Lifecycle.COMPLETED));
            terminal(store, failed, marker.revision(), TraversalStatus.FAILED);
            var failure = await(store.businessHistory(SCOPE, "failed")).orElseThrow();
            assertEquals(BusinessHistory.Lifecycle.FAILED, failure.lifecycle()); assertEquals(1, failure.failedTraversals());
        }
    }

    @Test void closureAuthorityAndConflictingRetriesRollBackWithTheVisit() throws Exception {
        try (var store = createStore(id())) {
            var work = work(store, "authority", "part"); var signal = signal(work, BusinessHistory.Lifecycle.COMPLETED);
            var wrong = new BusinessClosureSignal(new BusinessScope("foreign", "deployment", "graph-v1"), "authority", signal.requested(), signal.auditReason(),
                    signal.processInstanceId(), signal.traversalId(), signal.invocationId(), signal.attemptId(), NOW);
            assertThrows(Exception.class, () -> await(store.apply(completion(work, work.revision()).requestBusinessClosure(wrong).build())));
            assertEquals(NodeInvocationStatus.RUNNING, await(store.load(work.lease().key())).state().traversals().get(work.relation().traversalId()).invocations().get(work.invocation()).status());
            assertNull(await(store.businessHistory(SCOPE, "authority")).orElseThrow().closureSignal());
            var completed = completeVisit(store, work, signal); var beforeRetry = await(store.businessHistory(SCOPE, "authority")).orElseThrow();
            var retry = await(store.apply(ExecutionBatch.to(work.lease().key()).expecting(RevisionExpectation.exactly(completed.revision())).fencedBy(work.lease())
                    .requestBusinessClosure(signal.at(NOW.plusSeconds(1))).build()));
            assertEquals(beforeRetry, await(store.businessHistory(SCOPE, "authority")).orElseThrow());
            var conflicting = new BusinessClosureSignal(SCOPE, "authority", signal.requested(), "different-reason", signal.processInstanceId(), signal.traversalId(), signal.invocationId(), signal.attemptId(), NOW);
            assertThrows(Exception.class, () -> await(store.apply(ExecutionBatch.to(work.lease().key()).expecting(RevisionExpectation.exactly(retry.revision())).fencedBy(work.lease())
                    .requestBusinessClosure(conflicting).build())));
            assertEquals(beforeRetry, await(store.businessHistory(SCOPE, "authority")).orElseThrow());
        }
    }

    @Test void durableReopenRetainsRequestedCloseAndOriginalCausality() throws Exception {
        String id = id(); Work original; long revision;
        try (var store = createStore(id)) {
            assumeTrue(store.supports(StoreCapability.DURABLE), "memory reference cannot reopen durable close signals");
            original = work(store, "reopen", "part"); revision = completeVisit(store, original, signal(original, BusinessHistory.Lifecycle.COMPLETED)).revision();
        }
        try (var store = createStore(id, Clock.fixed(NOW.plusSeconds(31), ZoneOffset.UTC))) {
            var retained = await(store.businessHistory(SCOPE, "reopen")).orElseThrow();
            assertEquals(signal(original, BusinessHistory.Lifecycle.COMPLETED), retained.closureSignal());
            var lease = await(store.claim(original.lease().key(), "recovered-close", Duration.ofSeconds(30)));
            terminal(store, new Work(original.relation(), original.invocation(), original.attempt(), lease, revision), revision, TraversalStatus.COMPLETED);
            assertEquals(BusinessHistory.Lifecycle.COMPLETED, await(store.businessHistory(SCOPE, "reopen")).orElseThrow().lifecycle());
        }
    }

    @Test void derivedInputAttributesCommandAndLineageSurviveTerminalCustodyRetirement() throws Exception {
        try (var store = createStore(id())) {
            var original = work(store, "original", "part"); UUID child = UUID.randomUUID(), traversal = UUID.randomUUID();
            var derived = new DeploymentEntryIntent(SCOPE, child, traversal, "publish", new ApplicationCorrelationContext("computed", Optional.empty(), Optional.empty(), "0", Optional.empty()),
                    OWNER, OpaquePayload.of("{\"computed\":\"value\"}".getBytes(StandardCharsets.UTF_8), "application/json"), NOW, NOW.plusSeconds(100),
                    CorrelatedTraversal.Cause.DERIVED_CONTEXT, cause(original), "computed-ingestion-id", null,
                    OpaquePayload.of("{\"provenance\":\"preserved\"}".getBytes(StandardCharsets.UTF_8), "application/json"), NodeCommand.application("publish"));
            assertThrows(Exception.class, () -> await(store.acceptDeploymentEntry(derived, CorrelationMapping.Mode.ACCEPT_OR_CREATE)));
            var accepted = await(store.acceptDeploymentEntry(derived, CorrelationMapping.Mode.ACCEPT_OR_CREATE, Optional.of(original.lease()))).intent().orElseThrow();
            assertEquals(derived, accepted); assertEquals("original", await(store.correlatedTraversal("tenant", original.relation().processInstanceId(), original.relation().traversalId())).orElseThrow().context().applicationCorrelationId());
            var relation = await(store.correlatedTraversal("tenant", child, traversal)).orElseThrow();
            assertEquals(new CorrelationDerivation(original.relation().processInstanceId(), original.relation().traversalId(), original.invocation(), original.attempt(), "computed-ingestion-id"), relation.derivation());
            var created = await(store.apply(ExecutionBatch.to(derived.key()).expecting(RevisionExpectation.notPresent()).apply(new ExecutionTransition.ProcessCreated(
                    new ProcessInstance(child, ProcessInstanceStatus.ACCEPTED, Map.of(traversal, new Traversal(traversal, "publish", TraversalStatus.ACCEPTED, Map.of()))), new GraphVersionPin("graph-v1")))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING)).apply(new ExecutionTransition.TraversalTransitioned(traversal, TraversalStatus.RUNNING)).build()));
            await(store.apply(ExecutionBatch.to(derived.key()).expecting(RevisionExpectation.exactly(created.revision()))
                    .apply(new ExecutionTransition.TraversalTransitioned(traversal, TraversalStatus.COMPLETED)).apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.COMPLETED)).build()));
            assertTrue(await(store.deploymentEntry(derived.key())).isEmpty());
            assertEquals(relation.derivation(), await(store.correlatedTraversal("tenant", child, traversal)).orElseThrow().derivation());
        }
    }

    @Test void derivedHandoffAndActualVisitCompletionCommitOrRollBackTogether() throws Exception {
        try (var store = createStore(id())) {
            var original = work(store, "atomic-original", "part");
            var intent = new DeploymentEntryIntent(SCOPE, UUID.randomUUID(), UUID.randomUUID(), "publish",
                    new ApplicationCorrelationContext("atomic-child", Optional.empty(), Optional.empty(), "0", Optional.empty()), OWNER,
                    OpaquePayload.of("{}".getBytes(StandardCharsets.UTF_8), "application/json"), NOW, NOW.plusSeconds(100),
                    CorrelatedTraversal.Cause.DERIVED_CONTEXT, cause(original), "normalized-input", null);
            var handoff = new DerivedEntryHandoff(intent, CorrelationMapping.Mode.REQUIRE_EXISTING);
            assertThrows(Exception.class, () -> await(store.apply(completion(original, original.revision()).handoffDerivedEntry(handoff).build())));
            assertEquals(NodeInvocationStatus.RUNNING, await(store.load(original.lease().key())).state().traversals().get(original.relation().traversalId()).invocations().get(original.invocation()).status());
            assertTrue(await(store.deploymentEntry(intent.key())).isEmpty()); assertTrue(await(store.businessHistory(SCOPE, "atomic-child")).isEmpty());
            var accepted = await(store.apply(completion(original, original.revision()).handoffDerivedEntry(new DerivedEntryHandoff(intent, CorrelationMapping.Mode.ACCEPT_OR_CREATE)).build()));
            assertEquals(NodeInvocationStatus.COMPLETED, accepted.state().traversals().get(original.relation().traversalId()).invocations().get(original.invocation()).status());
            assertEquals(intent, await(store.deploymentEntry(intent.key())).orElseThrow());
            var group = await(store.businessHistory(SCOPE, "atomic-child")).orElseThrow();
            await(store.apply(ExecutionBatch.to(original.lease().key()).expecting(RevisionExpectation.exactly(accepted.revision())).fencedBy(original.lease())
                    .handoffDerivedEntry(new DerivedEntryHandoff(intent, CorrelationMapping.Mode.ACCEPT_OR_CREATE)).build()));
            assertEquals(group, await(store.businessHistory(SCOPE, "atomic-child")).orElseThrow(), "retry does not add a child or a running counter");
        }
    }

    @Test void derivedAttributeByteRefusalLeavesOriginalVisitAndHistoryUnchanged() throws Exception {
        try (var store = createStore(id())) {
            int max = store.maxPayloadBytes(); assumeTrue(max < 64 * 1024 * 1024 - 128, "finite fixture allocation must fit the public custody codec ceiling");
            var original = work(store, "bounded-original", "part");
            var intent = new DeploymentEntryIntent(SCOPE, UUID.randomUUID(), UUID.randomUUID(), "publish",
                    new ApplicationCorrelationContext("bounded-child", Optional.empty(), Optional.empty(), "0", Optional.empty()), OWNER,
                    OpaquePayload.of("{}".getBytes(StandardCharsets.UTF_8), "application/json"), NOW, NOW.plusSeconds(100),
                    CorrelatedTraversal.Cause.DERIVED_CONTEXT, cause(original), "normalized-input", null,
                    OpaquePayload.of(("{\"value\":\"" + "x".repeat(max) + "\"}").getBytes(StandardCharsets.UTF_8), "application/json"), NodeCommand.PROCESS);
            assertThrows(Exception.class, () -> await(store.apply(completion(original, original.revision())
                    .handoffDerivedEntry(new DerivedEntryHandoff(intent, CorrelationMapping.Mode.ACCEPT_OR_CREATE)).build())));
            assertEquals(NodeInvocationStatus.RUNNING, await(store.load(original.lease().key())).state().traversals().get(original.relation().traversalId()).invocations().get(original.invocation()).status());
            assertTrue(await(store.deploymentEntry(intent.key())).isEmpty()); assertTrue(await(store.businessHistory(SCOPE, "bounded-child")).isEmpty());
        }
    }

    private static Work work(ExecutionStore store, String app, String contribution) throws Exception {
        var relation = relation(app, contribution); await(store.recordCorrelatedTraversal(relation, CorrelationMapping.Mode.ACCEPT_OR_CREATE));
        UUID invocation = UUID.randomUUID(), attempt = UUID.randomUUID(); var key = new ExecutionKey("tenant", relation.processInstanceId());
        var created = await(store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent()).apply(new ExecutionTransition.ProcessCreated(
                new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED, Map.of(relation.traversalId(), new Traversal(relation.traversalId(), "entry", TraversalStatus.ACCEPTED, Map.of()))),
                new GraphVersionPin("graph-v1"))).apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(relation.traversalId(), TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(relation.traversalId(), new NodeInvocation(invocation, "close", Set.of(), NodeInvocationStatus.SCHEDULED, List.of())))
                .apply(new ExecutionTransition.InvocationTransitioned(relation.traversalId(), invocation, NodeInvocationStatus.RUNNING))
                .apply(new ExecutionTransition.AttemptAdded(relation.traversalId(), invocation, new NodeAttempt(attempt, 1, NodeAttemptStatus.SCHEDULED)))
                .apply(new ExecutionTransition.AttemptTransitioned(relation.traversalId(), invocation, attempt, NodeAttemptStatus.RUNNING)).build()));
        return new Work(relation, invocation, attempt, await(store.claim(key, "author", Duration.ofSeconds(30))), created.revision());
    }
    private static CorrelatedTraversal relation(String app, String contribution) {
        return new CorrelatedTraversal(SCOPE, new ApplicationCorrelationContext(app, Optional.of(contribution), Optional.empty(), "0", Optional.empty()), UUID.randomUUID(), UUID.randomUUID(), "entry",
                CorrelatedTraversal.Cause.SOURCE_EVENT, TraversalStatus.ACCEPTED, null, NOW, NOW, null);
    }
    private static BusinessClosureSignal signal(Work work, BusinessHistory.Lifecycle disposition) {
        var relation = work.relation(); return new BusinessClosureSignal(SCOPE, relation.context().applicationCorrelationId(), disposition, "declared-production-complete",
                relation.processInstanceId(), relation.traversalId(), work.invocation(), work.attempt(), NOW);
    }
    private static ExecutionBatch.Builder completion(Work work, long revision) {
        return ExecutionBatch.to(work.lease().key()).expecting(RevisionExpectation.exactly(revision)).fencedBy(work.lease())
                .apply(new ExecutionTransition.AttemptTransitioned(work.relation().traversalId(), work.invocation(), work.attempt(), NodeAttemptStatus.COMPLETED))
                .apply(new ExecutionTransition.InvocationTransitioned(work.relation().traversalId(), work.invocation(), NodeInvocationStatus.COMPLETED));
    }
    private static StoredProcessInstance completeVisit(ExecutionStore store, Work work, BusinessClosureSignal signal) throws Exception {
        var batch = completion(work, work.revision()); if (signal != null) batch.requestBusinessClosure(signal); return await(store.apply(batch.build()));
    }
    private static void terminal(ExecutionStore store, Work work, long revision, TraversalStatus status) throws Exception {
        await(store.apply(ExecutionBatch.to(work.lease().key()).expecting(RevisionExpectation.exactly(revision)).fencedBy(work.lease())
                .apply(new ExecutionTransition.TraversalTransitioned(work.relation().traversalId(), status))
                .apply(new ExecutionTransition.ProcessTransitioned(status == TraversalStatus.COMPLETED ? ProcessInstanceStatus.COMPLETED : ProcessInstanceStatus.FAILED)).build()));
    }
    private static JoinCause cause(Work work) { return new JoinCause(work.relation().processInstanceId(), work.relation().traversalId(), work.invocation(), work.attempt(), OWNER); }
    private static JoinContribution contribution(Work work, String id) {
        return new JoinContribution(id, null, work.relation().processInstanceId(), work.relation().traversalId(), work.invocation(), work.attempt(), OWNER,
                OpaquePayload.of("{}".getBytes(StandardCharsets.UTF_8), "application/json"), JoinContribution.digest("{}".getBytes(StandardCharsets.UTF_8)), NOW);
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private static <T> T await(CompletionStage<T> value) throws Exception { return value.toCompletableFuture().get(30, TimeUnit.SECONDS); }
}
