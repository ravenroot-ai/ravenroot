package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionLookup;
import ai.ravenroot.api.application.ExecutionSubmission;
import ai.ravenroot.api.application.EdgeTraversalWireBudget;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.Scheduler;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.IdempotencyRecord;
import ai.ravenroot.api.persistence.LeaseHandle;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that core's dependency on the {@link ExecutionStore} port is a real, exercised compile-time
 * dependency rather than an unused field: PERS-04 would otherwise inherit dead code.
 *
 * <p>Core references only the port type here. {@link InMemoryExecutionStore} appears solely as a test
 * fixture, exactly as a SQLite adapter will once PERS-03 lands.</p>
 */
class DefaultRavenrootApplicationExecutionStoreTest {

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="wiring" edgedefault="directed">
                <node id="error"><data key="node-kind">ERROR</data></node>
                <node id="start"><data key="node-kind">START</data></node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="start-end" source="start" target="end">
                  <data key="edge-outcome">continue</data>
                </edge>
              </graph>
            </graphml>
            """;

    @Test
    void recordsInstanceCreationAndOneStateTransitionThroughThePort() {
        var store = new InMemoryExecutionStore();
        var engine = new StubExecutionEngine();
        var application = applicationWith(engine, store);

        ExecutionSubmission submission = application.startGraphMl(TestIdentities.TENANT_A,
                java.util.UUID.randomUUID(), new ByteArrayInputStream(graphBytes()), "payload");

        StoredProcessInstance stored = store.load(
                        new ExecutionKey(TestIdentities.TENANT_A.tenantId(), submission.processInstanceId()))
                .toCompletableFuture().join();

        // The runner now writes through the store, so a traversal that finished is recorded
        // as finished. Previously this read RUNNING forever -- not because the traversal was
        // running, but because nothing after acceptance ever reached the store.
        assertEquals(ProcessInstanceStatus.COMPLETED, stored.state().status());
        assertEquals(TraversalStatus.COMPLETED,
                stored.state().traversals().get(submission.traversalId()).status());

        // And the node-level rows exist, which is the part that makes a crash recoverable at all:
        // every visited node has an invocation and a completed attempt under it.
        var invocations = stored.state().traversals().get(submission.traversalId()).invocations();
        assertEquals(2, invocations.size(), "start and end each recorded an invocation");
        invocations.values().forEach(invocation -> {
            assertEquals(ai.ravenroot.api.application.NodeInvocationStatus.COMPLETED, invocation.status());
            assertEquals(1, invocation.attempts().size());
            assertEquals(ai.ravenroot.api.application.NodeAttemptStatus.COMPLETED,
                    invocation.attempts().getLast().status());
        });
        // The pin is the graph version the submission was acknowledged with, so recovery would
        // replay against the same definition.
        assertEquals(new GraphVersionPin(submission.graphVersion()), stored.graphVersionPin());
        assertEquals("start", stored.state().traversals().get(submission.traversalId()).ingressNodeId());

        application.close();
        store.close();
    }

    /**
     * Issue 154's write path, at the transient-submission admission point: {@code startGraphMl}
     * never opens a deployment domain and models no workload, so the durable inventory row it
     * writes must carry an absent {@code deploymentId} and {@code workloadId} while still recording
     * the caller's own correlation identity -- the distinction acceptance criterion 2 requires
     * between "transient" and "deployment-hosted" without conflating either with a deployment or a
     * graph version.
     */
    @Test
    void recordsTransientOriginWithAbsentDeploymentAndWorkloadButPresentCorrelationId() {
        var store = new InMemoryExecutionStore();
        var engine = new StubExecutionEngine();
        var application = applicationWith(engine, store);

        ExecutionSubmission submission = application.startGraphMl(TestIdentities.TENANT_A,
                java.util.UUID.randomUUID(), new ByteArrayInputStream(graphBytes()), "payload");

        var entry = store.findProcessInstance(
                        new ExecutionKey(TestIdentities.TENANT_A.tenantId(), submission.processInstanceId()))
                .toCompletableFuture().join().orElseThrow();

        assertTrue(entry.deploymentId().isEmpty(), "a transient submission opens no deployment domain");
        assertTrue(entry.workloadId().isEmpty(), "a transient submission models no workload");
        assertEquals(TestIdentities.TENANT_A.requestId(), entry.correlationId().orElseThrow(),
                "the caller's own ingress correlation identity must still be recorded");

        application.close();
        store.close();
    }

    @Test
    void durableReplayPreservesTheSameStableEdgeIdentityAndCausalOrdering() {
        var store = new InMemoryExecutionStore();
        var engine = new StubExecutionEngine();
        var application = applicationWith(engine, store);

        ExecutionSubmission submission = application.startGraphMl(TestIdentities.TENANT_A,
                java.util.UUID.randomUUID(), new ByteArrayInputStream(graphBytes()), "payload");
        var events = application.durableEventsAfter(TestIdentities.TENANT_A.tenantId(), 0, 100).stream()
                .filter(event -> submission.processInstanceId().equals(event.processInstanceId()))
                .toList();
        var traversal = events.stream()
                .filter(event -> "EDGE_TRAVERSED".equals(event.eventType()))
                .findFirst().orElseThrow();
        var sourceCompletion = events.stream()
                .filter(event -> "NODE_COMPLETED".equals(event.eventType()))
                .filter(event -> "start".equals(event.nodeId()))
                .findFirst().orElseThrow();
        var targetStart = events.stream()
                .filter(event -> "NODE_STARTED".equals(event.eventType()))
                .filter(event -> "end".equals(event.nodeId()))
                .findFirst().orElseThrow();

        assertEquals("start-end", traversal.edgeId());
        assertEquals(sourceCompletion.eventId(), traversal.causationId(),
                "the edge traversal is caused by the source completion");
        assertEquals(sourceCompletion.eventId(), targetStart.causationId(),
                "the successor retains the established source-completion causation contract");
        assertTrue(sourceCompletion.streamSequence() < traversal.streamSequence());
        assertTrue(traversal.streamSequence() < targetStart.streamSequence());

        application.close();
        store.close();
    }

    @Test
    void auxiliaryOverflowIsRejectedBeforeTheTraversalIsAppendedDurably() {
        String oversizedEngineId = escapedBytes(
                EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES + 1);
        var store = new InMemoryExecutionStore();
        var engine = new StubExecutionEngine(oversizedEngineId);
        var application = applicationWith(engine, store);

        ExecutionSubmission submission = application.startGraphMl(TestIdentities.TENANT_A,
                java.util.UUID.randomUUID(), new ByteArrayInputStream(graphBytes()), "payload");
        var events = application.durableEventsAfter(TestIdentities.TENANT_A.tenantId(), 0, 100).stream()
                .filter(event -> submission.processInstanceId().equals(event.processInstanceId()))
                .toList();

        assertTrue(events.stream().noneMatch(event -> "EDGE_TRAVERSED".equals(event.eventType())),
                "the invalid live identity must be refused before the durable traversal append");

        application.close();
        store.close();
    }

    /**
     * The SEC-07 store rule, stated as a falsifiable assertion.
     *
     * <p>Before SEC-07 this test could not have failed, because the tenant was a constructor constant:
     * two submissions from two different authenticated tenants both landed under {@code "default"}, and
     * PERS-10's tenant-scoped port was being fed one value forever. The evidence that tenancy now
     * reaches the store is precisely that each instance is loadable under its own submitter's tenant
     * and <em>not</em> under the other's — and that the negative direction is {@code NotFound} rather
     * than a denial, so the store does not become a cross-tenant existence oracle.</p>
     */
    @Test
    void partitionsStoredInstancesByTheAuthenticatedTenantOfEachSubmission() {
        var store = new InMemoryExecutionStore();
        var engine = new StubExecutionEngine();
        var application = applicationWith(engine, store);

        ExecutionSubmission fromA = application.startGraphMl(TestIdentities.TENANT_A, java.util.UUID.randomUUID(),
                new ByteArrayInputStream(graphBytes()), "payload");
        ExecutionSubmission fromB = application.startGraphMl(TestIdentities.TENANT_B, java.util.UUID.randomUUID(),
                new ByteArrayInputStream(graphBytes()), "payload");

        // Recorded through to completion, per tenant, with no leakage between them.
        assertEquals(ProcessInstanceStatus.COMPLETED, store
                .load(new ExecutionKey("tenant-a", fromA.processInstanceId()))
                .toCompletableFuture().join().state().status());
        assertEquals(ProcessInstanceStatus.COMPLETED, store
                .load(new ExecutionKey("tenant-b", fromB.processInstanceId()))
                .toCompletableFuture().join().state().status());

        // The cross-tenant read is NotFound, never a denial: indistinguishable from absence by design.
        var crossTenant = assertThrows(ExecutionStoreException.class, () -> await(
                store.load(new ExecutionKey("tenant-b", fromA.processInstanceId()))));
        assertInstanceOf(ExecutionStoreFailure.NotFound.class, crossTenant.failure());

        application.close();
        store.close();
    }

    @Test
    void behavesExactlyAsBeforeWhenNoStoreIsConfigured() {
        var engine = new StubExecutionEngine();
        var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor());

        ExecutionSubmission submission = application.startGraphMl(TestIdentities.TENANT_A,
                java.util.UUID.randomUUID(), new ByteArrayInputStream(graphBytes()), "payload");

        assertTrue(submission.processInstanceId() != null);
        application.close();
    }

    private static DefaultRavenrootApplication applicationWith(StubExecutionEngine engine, ExecutionStore store) {
        return new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new ai.ravenroot.core.programming.InMemoryArtifactRegistry(),
                new ai.ravenroot.core.programming.DisabledProgramRuntime(),
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store);
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException wrapped) {
            ExecutionStoreException failure = ExecutionStoreException.unwrap(wrapped);
            throw failure == null ? wrapped : failure;
        }
    }

    @Test
    void refusesToComposeAgainstAStoreThatCannotPromiseAtomicBatches() {
        var engine = new StubExecutionEngine();

        // Failing at composition time is the point: core relies on all-or-nothing batches, so a
        // store that cannot promise them must not be discovered mid-write.
        var failure = assertThrows(IllegalArgumentException.class, () -> new DefaultRavenrootApplication(
                engine, new ExecutionMonitor(),
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new ai.ravenroot.core.programming.InMemoryArtifactRegistry(),
                new ai.ravenroot.core.programming.DisabledProgramRuntime(),
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(),
                new NonTransactionalStore()));

        assertTrue(failure.getMessage().contains("TRANSACTIONAL_BATCH"));
        engine.close();
    }

    @Test
    void aRejectedStoreWriteFailsTheSubmissionWithItsClassificationIntact() {
        var engine = new StubExecutionEngine();
        var application = applicationWith(engine, new RejectingStore());

        var thrown = assertThrows(ExecutionStoreException.class,
                () -> application.startGraphMl(TestIdentities.TENANT_A, java.util.UUID.randomUUID(),
                        new ByteArrayInputStream(graphBytes()), "payload"));

        // The sealed classification survives the join; it is not flattened into a CompletionException.
        assertInstanceOf(ExecutionStoreFailure.Unavailable.class, thrown.failure());
        // Recording precedes execution, so a rejected write leaves nothing running behind it.
        assertEquals(0, application.runtimeSnapshot().activeExecutions());

        application.close();
    }

    /**
     * DEFECT (issue #104 wave 3, reported not fixed -- {@code DefaultRavenrootApplication.java} is
     * outside this territory). {@code ExecutionStore.recordExecutionResult}'s identity is
     * {@code (tenantId, traversalId)} alone (see {@code ExecutionStoreContract}'s own
     * {@code aTraversalIdReusedByAnUnrelatedProcessInstanceConflictsRatherThanOverwritingTheFirst}),
     * so a second, wholly unrelated process instance that reuses a caller-supplied traversal id
     * collides with the first at the result layer. That collision is refused by the store exactly as
     * designed -- the defect is what {@code DefaultRavenrootApplication} does with the refusal.
     *
     * <p>{@code recordDurableResult}'s {@code ExecutionStoreException} is caught inside the completion
     * lambda passed to {@code execution.whenComplete(...)} (see the seam around line 1350 of that
     * file) and re-thrown at the end of that same lambda. A {@code CompletionStage#whenComplete}
     * action's exception is never delivered to any caller of the method that registered it: it only
     * completes the <em>derived</em> stage exceptionally, and nothing in this codebase observes that
     * derived stage. The failure is therefore not reported, not logged, and not retried -- it is
     * discarded in its entirety.</p>
     *
     * <p>While the process that ran the second submission stays up, this is invisible: the
     * process-local {@code ExecutionResultRegistry} entry is updated unconditionally on completion
     * (it does not consult the store), so a same-process read of {@code executionResult} still
     * reports the second, correct, in-memory outcome. The defect surfaces only once that entry is
     * gone -- evicted by {@code ExecutionResultRegistry.DEFAULT_MAX_RESULTS}, or the process restarted,
     * or (as reproduced here, without needing 256 evictions) a different application instance sharing
     * only the durable store asks about the id. From that point on the durable record permanently and
     * silently reports the <em>first</em> submission's outcome for an id whose most recent, true
     * completion was the second's, and no exception, log line or classified failure anywhere says so.</p>
     *
     * <p>This test is the demonstration, not the fix: it names the defect at the boundary
     * ({@code DefaultRavenrootApplication}, in {@code ravenroot-core/src/main}) that this A2A wave does
     * not own. It is left {@code @Disabled} because it fails today by construction -- the assertion
     * below is the property a caller actually needs and does not get.</p>
     */
    @Test
    @Disabled("DEFECT: a reused execution id silently reports the stale first result once no "
            + "process-local cache entry remains, because the store's refusal of the second write is "
            + "thrown from inside an unobserved CompletionStage#whenComplete callback and discarded -- "
            + "see this method's javadoc and the A2A report for issue #104 wave 3")
    void aReusedExecutionIdSilentlyMisreportsTheStaleFirstResultOnceNoCacheEntryRemains() {
        var store = new InMemoryExecutionStore();
        var sharedTraversalId = java.util.UUID.randomUUID();

        // First process instance, submitted and completed under a caller-chosen traversal id.
        try (var first = applicationWith(new StubExecutionEngine(), store)) {
            first.startGraphMl(TestIdentities.TENANT_A, sharedTraversalId,
                    new ByteArrayInputStream(graphBytes()), "first-payload");
        }

        // A second, unrelated process instance reuses the identical traversal id. Nothing at the
        // process/traversal level rejects this -- that primary key is (tenant, processInstanceId) --
        // so the collision is invisible until completion tries to record a *result*, whose store-side
        // primary key is (tenant, traversalId) alone. This submission's own attempt to record its
        // result is refused as a conflict, and that refusal is silently discarded as described above.
        try (var second = applicationWith(new StubExecutionEngine(), store)) {
            second.startGraphMl(TestIdentities.TENANT_A, sharedTraversalId,
                    new ByteArrayInputStream(graphBytes()), "second-payload");
        }

        // A third instance, sharing nothing with either but the store -- exactly like a restart or
        // another pod -- asks about the traversal id both submissions used.
        try (var third = applicationWith(new StubExecutionEngine(), store)) {
            var lookup = third.executionResult(TestIdentities.TENANT_A.tenantId(), sharedTraversalId);
            var found = assertInstanceOf(ExecutionLookup.Found.class, lookup);
            // The property a caller actually needs: the most recently completed execution under this
            // id is reported. Today this reads "first-payload" instead, with nothing anywhere to say
            // that the second submission's own outcome was silently lost.
            assertEquals("second-payload", found.outcome().payload(),
                    "the most recently completed execution under this id must be the one reported");
        }

        store.close();
    }

    private static byte[] graphBytes() {
        return GRAPH.getBytes(StandardCharsets.UTF_8);
    }

    /** Declares no capabilities, so composition must refuse it. */
    private static class NonTransactionalStore implements ExecutionStore {
        // Durable inventory. This double declares no capabilities and exists only to be
        // refused at composition time, so every operation stays unimplemented rather than acquiring a
        // behaviour that no assertion covers.

        @Override
        public int maxInventoryPageSize() {
            return 1;
        }

        @Override
        public Duration terminalRetention() {
            return Duration.ofHours(1);
        }

        @Override
        public CompletionStage<ai.ravenroot.api.persistence.ProcessInventoryPage> listProcessInstances(
                String tenantId, ai.ravenroot.api.persistence.ProcessInventoryQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<java.util.Optional<ai.ravenroot.api.persistence.ProcessInventoryEntry>>
                findProcessInstance(ExecutionKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<List<ai.ravenroot.api.persistence.TraversalInventoryEntry>> listTraversals(
                ExecutionKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<java.time.Instant> inventoryRetainedFrom(String tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Long> purgeExpiredProcessInstances(String tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<StoreCapability> capabilities() {
            return Set.of();
        }

        @Override
        public Duration maxLeaseTtl() {
            return Duration.ofMinutes(1);
        }

        @Override
        public int maxPayloadBytes() {
            return 1024;
        }

        @Override
        public CompletionStage<StoredProcessInstance> apply(ExecutionBatch batch) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<StoredProcessInstance> load(ExecutionKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<LeaseHandle> claim(ExecutionKey key, String workerId, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<LeaseHandle> renew(LeaseHandle lease, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Void> release(LeaseHandle lease) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<List<LeaseHandle>> leases(String tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<List<PendingWork>> claimPendingWork(String tenantId, String workerId,
                                                                   int limit, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<List<PendingWork.TimerDue>> claimDueTimers(String tenantId, String workerId,
                                                                          int limit, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Void> ack(PendingWork item) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<java.util.Optional<IdempotencyRecord>> lookupIdempotency(
                String tenantId, String key, java.time.Instant keyIssuedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Duration maxClockSkew() {
            return Duration.ofSeconds(5);
        }

        @Override
        public Duration journalRetention() {
            return Duration.ofHours(1);
        }

        @Override
        public CompletionStage<List<ai.ravenroot.api.persistence.JournalRecord>> readJournal(
                String tenantId, long afterOffset, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Long> journalRetainedFrom(String tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<ai.ravenroot.api.persistence.JournalCursor> outboxCursor(
                String tenantId, String destination) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<ai.ravenroot.api.persistence.JournalCursor> advanceOutboxCursor(
                ai.ravenroot.api.persistence.JournalCursor expected, long throughOffset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Boolean> recordInboxDelivery(String tenantId, String consumerId,
                                                            java.util.UUID eventId, Duration retention) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Long> inboxRecordCount(String tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Long> compactJournal(String tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<java.time.Instant> forgottenBefore(String tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Long> idempotencyRecordCount(String tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Long> purgeExpiredIdempotencyRecords(String tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }

    /** Accepts composition, then fails the first write the way a transiently down store would. */
    private static final class RejectingStore extends NonTransactionalStore {
        @Override
        public Set<StoreCapability> capabilities() {
            return Set.of(StoreCapability.TRANSACTIONAL_BATCH);
        }

        @Override
        public CompletionStage<StoredProcessInstance> apply(ExecutionBatch batch) {
            return CompletableFuture.failedFuture(new ExecutionStoreException(
                    new ExecutionStoreFailure.Unavailable("store is down")));
        }
    }

    /**
     * The ordering assertion, observed at the only instant where it is falsifiable.
     *
     * <p><strong>A test observing final state cannot distinguish before-send from after-send: both
     * end identically.</strong> Moving the write after the send also causes stale-revision failures,
     * but those are collateral and do not prove ordering. This probe's inside-send assertion is the
     * evidence for the ordering guarantee.</p>
     *
     * <p>So this test reads the store from <em>inside</em> {@code send}, which is what a crash
     * occurring during the send would have left behind. If the attempt is not already
     * {@code RUNNING} at that instant, PERS-04's recovery loop is reading a status that does not mean
     * what it believes: a crashed attempt would be indistinguishable from one that never started,
     * and would be re-dispatched as provably effect-free when it was nothing of the kind.</p>
     */
    @Test
    void theAttemptIsAlreadyRunningInTheStoreAtTheMomentTheEngineIsSentTo() {
        var store = new InMemoryExecutionStore();
        var observed = new java.util.concurrent.ConcurrentLinkedQueue<ai.ravenroot.api.application.NodeAttemptStatus>();
        var engine = new StubExecutionEngine();
        engine.observeAtSend(message -> store
                .load(new ExecutionKey(TestIdentities.TENANT_A.tenantId(), message.processInstanceId()))
                .toCompletableFuture().join()
                .state().traversals().get(message.traversalId())
                .invocations().get(message.invocationId())
                .attempts().getLast().status());
        engine.recordInto(observed);
        var application = applicationWith(engine, store);

        application.startGraphMl(TestIdentities.TENANT_A, java.util.UUID.randomUUID(),
                new ByteArrayInputStream(graphBytes()), "payload");

        assertFalse(observed.isEmpty(), "the engine was never sent to, so nothing was observed");
        observed.forEach(status -> assertEquals(ai.ravenroot.api.application.NodeAttemptStatus.RUNNING, status,
                "the dispatch must be durable before it reaches the engine, or a crash during the "
                        + "send is indistinguishable from work that never started"));

        application.close();
        store.close();
    }

    private static final class StubExecutionEngine implements ExecutionEngine {
        private final String id;
        private final AtomicInteger spawnCount = new AtomicInteger();
        private java.util.function.Function<NodeMessage, ai.ravenroot.api.application.NodeAttemptStatus> probe;
        private java.util.Queue<ai.ravenroot.api.application.NodeAttemptStatus> sink;

        private StubExecutionEngine() {
            this("stub");
        }

        private StubExecutionEngine(String id) {
            this.id = id;
        }

        void observeAtSend(java.util.function.Function<NodeMessage,
                ai.ravenroot.api.application.NodeAttemptStatus> probe) {
            this.probe = probe;
        }

        void recordInto(java.util.Queue<ai.ravenroot.api.application.NodeAttemptStatus> sink) {
            this.sink = sink;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Set<EngineCapability> capabilities() {
            return Set.of();
        }

        @Override
        public Scheduler scheduler() {
            return (delay, task) -> () -> true;
        }

        @Override
        public NodeRef spawn(String logicalName, RavenNode node) {
            spawnCount.incrementAndGet();
            return new NodeRef(logicalName);
        }

        @Override
        public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
            if (probe != null && sink != null) {
                sink.add(probe.apply(message));
            }
            return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        }

        @Override
        public CompletionStage<Void> stop(NodeRef target) {
            return CompletableFuture.completedFuture(null);
        }


        @Override
        public EngineState state() {
            return EngineState.RUNNING;
        }

        @Override
        public Optional<NodeStatus> status(NodeRef target) {
            return Optional.of(StubEngineLifecycle.running(target));
        }

        @Override
        public CompletionStage<Void> cancel(NodeRef target) {
            return stop(target);
        }

        @Override
        public CompletionStage<Void> drain() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
        }
    }

    private static String escapedBytes(int count) {
        return Character.toString(1).repeat(count / 6) + "x".repeat(count % 6);
    }
}
