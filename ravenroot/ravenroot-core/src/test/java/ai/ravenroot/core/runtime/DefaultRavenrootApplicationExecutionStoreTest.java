package ai.ravenroot.core.runtime;

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
     * The priority-two API-projection proof: an execution's result must be sourced from the durable
     * record after a restart, exercised through the real {@link RavenrootApplication#executionResult}
     * production path rather than assumed from {@code ExecutionResultRegistryDurableTest}'s
     * registry-level coverage alone.
     *
     * <p>{@code application} answers the first read from its own in-memory
     * {@code ExecutionResultRegistry}, exactly as it always has. {@code restarted} is a brand new
     * {@link DefaultRavenrootApplication} -- a fresh engine, a fresh registry, nothing carried over --
     * composed against the identical {@code store}. {@link DefaultRavenrootApplication#close()} never
     * touches the {@link ExecutionStore} it was given (see its own Javadoc: the store is a resource the
     * caller owns), so {@code store} survives {@code application}'s shutdown exactly as it would survive
     * a process restart, and {@code restarted} answering correctly here is the same fact a second
     * instance sharing the store, or the same instance after a crash and recovery, would observe.</p>
     */
    @Test
    void executionResultSurvivesARestartByReadingTheDurableRecord() {
        var store = new InMemoryExecutionStore();
        var application = applicationWith(new StubExecutionEngine(), store);

        ExecutionSubmission submission = application.startGraphMl(TestIdentities.TENANT_A,
                java.util.UUID.randomUUID(), new ByteArrayInputStream(graphBytes()), "restart-payload");

        // Sanity: the first read, against the process that just ran it, answers from the in-memory
        // cache -- the behaviour this whole feature must not regress.
        var beforeRestart = assertInstanceOf(ai.ravenroot.api.application.ExecutionLookup.Found.class,
                application.executionResult(TestIdentities.TENANT_A.tenantId(), submission.executionId()));
        assertEquals(ProcessInstanceStatus.COMPLETED, beforeRestart.outcome().status());

        application.close();

        var restarted = applicationWith(new StubExecutionEngine(), store);
        var afterRestart = restarted.executionResult(TestIdentities.TENANT_A.tenantId(), submission.executionId());
        assertInstanceOf(ai.ravenroot.api.application.ExecutionLookup.Found.class, afterRestart,
                () -> "a restarted process with no memory of this execution must still answer from the "
                        + "durable record rather than as Unknown: " + afterRestart);
        var found = (ai.ravenroot.api.application.ExecutionLookup.Found) afterRestart;
        assertEquals(ProcessInstanceStatus.COMPLETED, found.outcome().status());
        assertEquals(beforeRestart.outcome().payload(), found.outcome().payload(),
                "the restarted read must agree with the pre-restart one on what the execution produced");
        assertEquals(beforeRestart.outcome().visitedNodes(), found.outcome().visitedNodes());

        restarted.close();
        store.close();
    }

    /**
     * The defect an independent verification pass found in the seam the restart test above exercises:
     * {@code startGraphMl}'s {@code execution.whenComplete(...)} return value is never observed, so an
     * exception thrown from inside that action -- including {@code recordDurableResult}'s durable-write
     * refusal -- used to vanish rather than reach a caller, a log, or a retry. A caller reusing a
     * traversal id across two submissions is a real, deterministic conflict ({@code traversalId} must
     * be unique per tenant, per {@code DurableExecutionResult}'s own Javadoc), and the store correctly
     * refuses to let the second submission's outcome replace the first's -- but nothing said so.
     *
     * <p>This proves two things about the fix rather than one. First, the refusal is now observable: a
     * {@link java.util.logging.Handler} attached to the same logger name
     * {@code recordDurableResult} logs through captures a record naming the reused id. Second, and
     * deliberately unchanged: once neither submission's entry survives in the process-local registry --
     * simulated here the same way {@link #executionResultSurvivesARestartByReadingTheDurableRecord}
     * simulates a restart, with a fresh application over the same store -- a read of the reused id
     * still answers with the <em>first</em> submission's payload. That is {@code recordExecutionResult}'s
     * own idempotent-by-refusal design, stated on its own Javadoc, and this fix does not and must not
     * change it: rewriting the durable record to prefer whichever submission happened to finish last
     * would silently replace one caller's already-durable answer with another's, for two submissions
     * that were never the same execution to begin with.</p>
     */
    @Test
    void aReusedExecutionIdSilentlyMisreportsTheStaleFirstResultOnceNoCacheEntryRemains() throws Exception {
        var store = new InMemoryExecutionStore();
        var application = applicationWith(new StubExecutionEngine(), store);
        java.util.UUID reusedId = java.util.UUID.randomUUID();

        application.startGraphMl(TestIdentities.TENANT_A, reusedId,
                new ByteArrayInputStream(graphBytes()), "first-payload");

        var captured = new java.util.concurrent.LinkedBlockingQueue<java.util.logging.LogRecord>();
        var handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                captured.add(record);
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        var logger = java.util.logging.Logger.getLogger(
                "ai.ravenroot.core.runtime.DefaultRavenrootApplication");
        logger.addHandler(handler);
        logger.setLevel(java.util.logging.Level.ALL);
        try {
            // Same traversal id, a second submission, a different payload: activeExecutions no
            // longer holds the first (StubExecutionEngine completes synchronously, like every other
            // assertion in this class that reads store state immediately after startGraphMl returns),
            // so this is accepted and runs to completion -- and its durable write collides.
            application.startGraphMl(TestIdentities.TENANT_A, reusedId,
                    new ByteArrayInputStream(graphBytes()), "second-payload");
        } finally {
            logger.removeHandler(handler);
        }

        var refusal = captured.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(refusal != null, "a reused traversal id's durable-write refusal must be logged, "
                + "not silently discarded by whenComplete's unobserved return value");
        assertEquals(java.util.logging.Level.WARNING, refusal.getLevel(),
                "ExecutionResultNotRecordable is a DETERMINISTIC_REJECT, not an unclassified failure");
        assertTrue(List.of(refusal.getParameters()).contains(reusedId),
                () -> "expected the reused traversal id among the log parameters: "
                        + List.of(refusal.getParameters()));

        application.close();

        // The restart case, exactly as the neighbouring test performs it: a fresh application, a
        // fresh in-memory registry, the same store. Neither submission's entry survives in memory.
        var restarted = applicationWith(new StubExecutionEngine(), store);
        var afterRestart = restarted.executionResult(TestIdentities.TENANT_A.tenantId(), reusedId);
        var found = assertInstanceOf(ai.ravenroot.api.application.ExecutionLookup.Found.class, afterRestart,
                () -> "the durable record for the reused id must still answer as a completed execution, "
                        + "not as Unknown: " + afterRestart);
        assertEquals("first-payload", found.outcome().payload(),
                "the durable store keeps whichever submission recorded first and never overwrites it "
                        + "with a later, colliding one -- this is the documented, unchanged behaviour, "
                        + "not the defect this test guards");

        restarted.close();
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
