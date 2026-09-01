package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.JoinKey;
import ai.ravenroot.api.persistence.JoinRecord;
import ai.ravenroot.api.persistence.JoinStore;
import ai.ravenroot.api.persistence.JournalRecord;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the runner writes into the append-only event journal, and what each row claims caused it.
 *
 * <h2>Why the fan-in case is first in this file and was written first</h2>
 * <p>Every other case here is a chain, and a chain has only one candidate cause, so an implementation
 * that threads causation badly still satisfies it. A fan-in has several candidates that all look
 * plausible, and only one of them triggered the dispatch. That is the cell that separates a correct
 * threading from one that merely passes: the ordinary-node assertions below stay green under an
 * implementation that names the wrong branch.</p>
 *
 * <p>The journal is append-only, so an event published with a cause that exists but is not named, or
 * with the wrong cause named, is a permanently false row that no later correction can amend. These
 * assertions are therefore about the <em>content</em> of the rows, not merely about their presence.</p>
 */
class JournalCausationTest {

    private static final String TENANT = "tenant-a";
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final String GRAPH_VERSION = "v1";

    /** On a regression a fan-in ordering test does not fail slowly, it hangs. */
    private static final long BOUND_MILLIS = 20_000;

    private final JoinTestEngine engine = new JoinTestEngine();
    private final ExecutionMonitor monitor = new ExecutionMonitor();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    // ------------------------------------------------------------------ the fan-in cell

    @Test
    @DisplayName("a fan-in node's start names the arrival that satisfied the join, not the first or lowest branch")
    void aJoinIsCausedByTheArrivalThatSatisfiedIt() throws Exception {
        var store = new InMemoryExecutionStore();
        var joins = new InMemoryJoinStore();
        // b0 lands first and is recorded first; b1 is held until that is a fact rather than a race,
        // so b1 is unambiguously the arrival that crossed the threshold. b1 is also the branch that
        // sorts LAST, which is what makes the assertion discriminating: an implementation that reads
        // the cause off the merged arrivals takes the first by branch id, and would name b0.
        var b0Recorded = new CountDownLatch(1);
        var gated = new ArrivalGatedJoinStore(joins, "b0", b0Recorded);
        var behaviors = new BehaviorRegistry()
                .register("b0", message -> CompletableFuture.completedFuture(NodeResult.continueWith("from-b0")))
                .register("b1", message -> {
                    awaitLatch(b0Recorded);
                    return CompletableFuture.completedFuture(NodeResult.continueWith("from-b1"));
                });

        Journal journal = runAndReadJournal(fanInOfTwo(), behaviors, gated, store, TENANT);

        UUID joinStartedCause = journal.causationOf(ExecutionEventType.NODE_STARTED, "join");
        UUID b1Completed = journal.eventIdOf(ExecutionEventType.NODE_COMPLETED, "b1");
        UUID b0Completed = journal.eventIdOf(ExecutionEventType.NODE_COMPLETED, "b0");

        assertNotEquals(b0Completed, b1Completed, "the fixture is broken: the two branches share an event");
        assertEquals(b1Completed, joinStartedCause,
                "the join's start must name the join-satisfying arrival — the last branch to cross the "
                        + "threshold, which is what made the runtime dispatch the join at that instant. "
                        + "b0's completion is a necessary condition, not the trigger, and it is already "
                        + "durably recorded as its own event and in the join record");
        assertNotEquals(b0Completed, joinStartedCause,
                "the join named the branch that arrived first and sorts lowest: that is the value a "
                        + "plausible implementation reads off the merged arrivals, and it is wrong on "
                        + "every run where the lowest branch id is not also the last to arrive");
    }

    @Test
    @DisplayName("reversing which branch arrives last reverses which completion the join names")
    void theJoinFollowsTheArrivalOrderRatherThanTheBranchOrder() throws Exception {
        var store = new InMemoryExecutionStore();
        var joins = new InMemoryJoinStore();
        // The converse of the case above, and the reason that one is not an accident of ordering.
        // Here b1 lands first and b0 is the join-satisfying arrival, so the expected answer flips
        // while branch-id order does not. An implementation that hard-codes either the lowest or the
        // highest branch id passes exactly one of this pair and fails the other.
        var b1Recorded = new CountDownLatch(1);
        var gated = new ArrivalGatedJoinStore(joins, "b1", b1Recorded);
        var behaviors = new BehaviorRegistry()
                .register("b1", message -> CompletableFuture.completedFuture(NodeResult.continueWith("from-b1")))
                .register("b0", message -> {
                    awaitLatch(b1Recorded);
                    return CompletableFuture.completedFuture(NodeResult.continueWith("from-b0"));
                });

        Journal journal = runAndReadJournal(fanInOfTwo(), behaviors, gated, store, TENANT);

        assertEquals(journal.eventIdOf(ExecutionEventType.NODE_COMPLETED, "b0"),
                journal.causationOf(ExecutionEventType.NODE_STARTED, "join"),
                "with the arrival order reversed the join must name b0, the branch that crossed the "
                        + "threshold this time");
    }

    // ------------------------------------------------------- the ordinary chain, and the one absence

    @Test
    @DisplayName("a chain names its predecessor's completion, and only the traversal's acceptance has no cause")
    void anOrdinaryChainNamesItsPredecessorsCompletion() throws Exception {
        var store = new InMemoryExecutionStore();
        Journal journal = runAndReadJournal(chain(), chainBehaviors(), new InMemoryJoinStore(), store, TENANT);

        UUID accepted = journal.eventIdOf(ExecutionEventType.EXECUTION_STARTED, null);
        assertNull(journal.causationOf(ExecutionEventType.EXECUTION_STARTED, null),
                "the traversal was accepted because an authenticated request asked for it, and that "
                        + "request is not in this journal: this is the only event entitled to absent causation");
        assertEquals(1L, journal.streamSequenceOf(accepted),
                "the acceptance must be the first row of the instance's stream, because the start node's "
                        + "event names it and a cause published after its effect is unreadable in stream order");

        assertEquals(accepted, journal.causationOf(ExecutionEventType.NODE_STARTED, "start"),
                "the start node was dispatched by the traversal being accepted");
        assertEquals(journal.eventIdOf(ExecutionEventType.NODE_STARTED, "start"),
                journal.causationOf(ExecutionEventType.NODE_COMPLETED, "start"),
                "a node's completion is emitted because that node started");
        assertEquals(journal.eventIdOf(ExecutionEventType.NODE_COMPLETED, "start"),
                journal.causationOf(ExecutionEventType.NODE_STARTED, "work"),
                "an ordinary successor is dispatched by its predecessor's completion");
        assertEquals(journal.eventIdOf(ExecutionEventType.NODE_COMPLETED, "work"),
                journal.causationOf(ExecutionEventType.NODE_STARTED, "end"));
    }

    @Test
    @DisplayName("a failed node's failure names that node's own start")
    void aFailedNodeNamesItsOwnStart() throws Exception {
        var store = new InMemoryExecutionStore();
        var behaviors = new BehaviorRegistry()
                .register("work", message ->
                        CompletableFuture.failedFuture(new IllegalStateException("node exploded")));

        Journal journal = runAndReadJournal(chain(), behaviors, new InMemoryJoinStore(), store, TENANT, true);

        assertEquals(journal.eventIdOf(ExecutionEventType.NODE_STARTED, "work"),
                journal.causationOf(ExecutionEventType.NODE_FAILED, "work"),
                "the failure was emitted because the attempt was running, and the attempt was running "
                        + "because this node started");
        assertTrue(journal.records().stream().noneMatch(record ->
                        ExecutionEventType.NODE_STARTED.name().equals(record.envelope().eventType())
                                && "end".equals(journal.nodeOf(record))),
                "a node downstream of the failure never started, so it must have published nothing");
    }

    // ------------------------------------------------- the defaulted node, journalled as its own row

    /**
     * {@code NODE_DEFAULTED} was the one node event type published only to
     * {@link ExecutionMonitor}'s in-memory stream and never journalled, so a deployment with a
     * journal-capable store had no durable record of an invocation having defaulted at all.
     *
     * <p>Four separate claims, because three of them stay green under an implementation that gets the
     * fourth wrong: the row exists; it names this node's own start as its cause, exactly as a failure
     * does; it precedes the completion in stream order rather than merely being present somewhere;
     * and — the one that pins the decision rather than the mechanism — the completion beside it still
     * names the <em>start</em>, not the defaulted row. That last one is the causal model's removal
     * test applied at the instant of emission: deleting the defaulted event would change nothing
     * about why the completion was emitted, so it is not its cause. An implementation that chained
     * them start → defaulted → completed would satisfy every other assertion here.</p>
     */
    @Test
    @DisplayName("a defaulted node journals its own row, caused by its start, before its completion")
    void aDefaultedNodeJournalsItsOwnRowBeforeTheCompletion() throws Exception {
        var store = new InMemoryExecutionStore();
        // No registration for "work": the runner composes its own pass-through and records the node in
        // passThroughNodes, which is what makes the fact structural rather than self-declared (SEC-09).
        Journal journal = runAndReadJournal(chain(), new BehaviorRegistry(), new InMemoryJoinStore(),
                store, TENANT);

        UUID defaulted = journal.eventIdOf(ExecutionEventType.NODE_DEFAULTED, "work");
        UUID started = journal.eventIdOf(ExecutionEventType.NODE_STARTED, "work");
        assertEquals(started, journal.causationOf(ExecutionEventType.NODE_DEFAULTED, "work"),
                "the defaulted row was emitted because this node's attempt was running, and the attempt "
                        + "was running because this node started -- the same cause a failure names");
        assertTrue(journal.streamSequenceOf(defaulted)
                        < journal.streamSequenceOf(journal.eventIdOf(ExecutionEventType.NODE_COMPLETED, "work")),
                "the defaulted row must precede the completion in stream order, matching the in-memory "
                        + "stream's order, which TelemetryBridge relies on when it declines to end a node "
                        + "span on a defaulted event");
        assertEquals(started, journal.causationOf(ExecutionEventType.NODE_COMPLETED, "work"),
                "the completion still names the start: removing the defaulted event changes nothing about "
                        + "why the completion was emitted, so chaining them would name a cause that is not one");
    }

    /**
     * The negative half, and it is not symmetry for its own sake: an implementation that published the
     * row unconditionally — ignoring {@code passThroughNodes} — would leave every assertion above
     * green while writing, into an append-only log, the permanently false claim that a registered
     * behavior did nothing. That is the same forgery {@code DefaultedNodeMarkerForgeryTest} pins on
     * the in-memory side, and on the journal it cannot be retracted.
     */
    @Test
    @DisplayName("a registered behavior journals no defaulted row")
    void aRegisteredBehaviorJournalsNoDefaultedRow() throws Exception {
        var store = new InMemoryExecutionStore();
        Journal journal = runAndReadJournal(chain(), chainBehaviors(), new InMemoryJoinStore(), store, TENANT);

        assertTrue(journal.records().stream().noneMatch(record ->
                        ExecutionEventType.NODE_DEFAULTED.name().equals(record.envelope().eventType())),
                "this deployment has the behavior, so no invocation in this run defaulted; a row saying "
                        + "otherwise is a claim about an operator's own catalog that cannot be withdrawn");
    }

    // --------------------------------------------------------------- invariants over every row

    @Test
    @DisplayName("every row's cause is present, earlier in the stream, and no row is self-caused")
    void everyCauseIsAPriorRowOfTheSameStream() throws Exception {
        var store = new InMemoryExecutionStore();
        Journal journal = runAndReadJournal(fanInOfTwo(), branchBehaviors(), new InMemoryJoinStore(),
                store, TENANT);

        long uncaused = journal.records().stream()
                .filter(record -> record.envelope().causation().isEmpty())
                .count();
        assertEquals(1, uncaused,
                "exactly one row may claim its cause lies outside this journal — the traversal's "
                        + "acceptance. Any other absence is a permanently false statement, because "
                        + "EventEnvelope's contract makes absence mean 'no cause in this journal'");

        for (JournalRecord record : journal.records()) {
            EventEnvelope envelope = record.envelope();
            assertTrue(envelope.digestMatchesContent(), "a stored row disagrees with its own digest");
            assertEquals(TENANT, envelope.tenantId(),
                    "the tenant is the authenticated request's, imposed by the batch's key; a client "
                            + "never supplies it and a cursor is not a cross-tenant capability");
            if (envelope.causation().isEmpty()) {
                continue;
            }
            UUID cause = envelope.causation().orElseThrow();
            assertNotEquals(envelope.eventId(), cause, "no row may name itself as its own cause");
            Long causeSequence = journal.streamSequenceOrNull(cause);
            assertNotNull(causeSequence, () -> envelope.eventType()
                    + " names a cause that is not in this journal at all: a dangling causal edge on an "
                    + "append-only log can never be repaired");
            assertTrue(causeSequence < record.streamSequence(),
                    () -> envelope.eventType() + " names a cause published after it, so a consumer "
                            + "reading the stream in order meets the effect before the cause");
        }
    }

    /**
     * <p><strong>What this asserts, and what it does not.</strong> Two guards stand in series on this
     * path: {@code ExecutionState.journalling()} declines to author envelopes at all, and
     * {@link ExecutionRecorder#record} declines to attach them. They are redundant, so removing
     * either one alone leaves this test green — both mutations were run and both survived. Removing
     * both together reds it and nothing else. So this is evidence about the composite behaviour, and
     * deliberately not evidence that either guard individually is load-bearing; the earlier of the two
     * exists so a causation identifier is never handed out for a row this store would have dropped.</p>
     */
    @Test
    @DisplayName("a store without the journal capability still records transitions and publishes nothing")
    void withoutTheJournalCapabilityTheTransitionsStillLand() throws Exception {
        var store = new InMemoryExecutionStore();
        var withoutJournal = new JournalFreeExecutionStore(store);

        ExecutionKey key = run(chain(), chainBehaviors(), new InMemoryJoinStore(), withoutJournal, TENANT, false);

        assertEquals(ProcessInstanceStatus.COMPLETED, await(store.load(key)).state().status(),
                "the transitions are the durability guarantee and must survive an adapter that cannot "
                        + "journal; the journal is a projection of them");
        assertTrue(await(store.readJournal(TENANT, 0, 100)).isEmpty(),
                "an adapter that does not declare the capability must have been published nothing at all, "
                        + "rather than events silently dropped after their identities were handed out as causes");
    }

    // ------------------------------------------------------------------------------- fixtures

    /**
     * A run whose journal was actually written. Every accessor resolves a node id through the
     * aggregate's own {@code InvocationAdded} rows, which is the join a durable projection performs:
     * the envelope deliberately carries no node id because that binding is already recorded as
     * structure in the same transaction.
     */
    private record Journal(List<JournalRecord> records, Map<UUID, String> nodeByInvocation) {

        private UUID eventIdOf(ExecutionEventType type, String nodeId) {
            return one(type, nodeId).envelope().eventId();
        }

        private UUID causationOf(ExecutionEventType type, String nodeId) {
            return one(type, nodeId).envelope().causationId();
        }

        private long streamSequenceOf(UUID eventId) {
            Long sequence = streamSequenceOrNull(eventId);
            assertNotNull(sequence, () -> "no row with event id " + eventId);
            return sequence;
        }

        private Long streamSequenceOrNull(UUID eventId) {
            return records.stream()
                    .filter(record -> record.envelope().eventId().equals(eventId))
                    .map(JournalRecord::streamSequence)
                    .findFirst().orElse(null);
        }

        private String nodeOf(JournalRecord record) {
            return record.envelope().invocation().map(nodeByInvocation::get).orElse(null);
        }

        private JournalRecord one(ExecutionEventType type, String nodeId) {
            List<JournalRecord> matching = records.stream()
                    .filter(record -> type.name().equals(record.envelope().eventType()))
                    .filter(record -> java.util.Objects.equals(nodeId, nodeOf(record)))
                    .toList();
            assertEquals(1, matching.size(),
                    () -> "expected exactly one " + type + " for node " + nodeId + ", journal held "
                            + matching.size() + " of " + records.size() + " rows");
            return matching.getFirst();
        }
    }

    private Journal runAndReadJournal(GraphDefinition graph, BehaviorRegistry behaviors, JoinStore joins,
                                      ExecutionStore store, String tenant) throws Exception {
        return runAndReadJournal(graph, behaviors, joins, store, tenant, false);
    }

    private Journal runAndReadJournal(GraphDefinition graph, BehaviorRegistry behaviors, JoinStore joins,
                                      ExecutionStore store, String tenant, boolean expectFailure)
            throws Exception {
        ExecutionKey key = run(graph, behaviors, joins, store, tenant, expectFailure);
        List<JournalRecord> records = await(store.readJournal(tenant, 0, 1_000)).stream()
                .filter(record -> record.key().equals(key))
                .toList();
        assertFalse(records.isEmpty(), "the run journalled nothing, so every assertion below is vacuous");
        return new Journal(records, nodeByInvocation(store, key));
    }

    /** Creates the instance, takes its lease and runs one traversal under it. */
    private ExecutionKey run(GraphDefinition graph, BehaviorRegistry behaviors, JoinStore joins,
                             ExecutionStore store, String tenant, boolean expectFailure) throws Exception {
        var security = TestIdentities.of(tenant, "alice");
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        var key = new ExecutionKey(tenant, processInstanceId);

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, behaviors, monitor,
                     ExecutionIdentitySource.randomUuids(), joins, Clock.systemUTC())) {
            long revision = createRunningInstance(store, key, traversalId, manager.start().id());
            try (var recorder = ExecutionRecorder.open(store, key, "test-worker", TTL, revision)) {
                var execution = runner.execute(security, processInstanceId, traversalId, "payload",
                        GRAPH_VERSION, null, null, recorder).toCompletableFuture();
                if (expectFailure) {
                    assertThrows(Exception.class,
                            () -> execution.get(BOUND_MILLIS, TimeUnit.MILLISECONDS));
                } else {
                    execution.get(BOUND_MILLIS, TimeUnit.MILLISECONDS);
                }
            }
        }
        return key;
    }

    private static long createRunningInstance(ExecutionStore store, ExecutionKey key, UUID traversalId,
                                              String startNodeId) {
        var traversal = new Traversal(traversalId, startNodeId, TraversalStatus.ACCEPTED, Map.of());
        var accepted = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                Map.of(traversalId, traversal));
        var created = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(accepted, new GraphVersionPin(GRAPH_VERSION)))
                .build()));
        return await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .build())).revision();
    }

    /** The invocation-to-node binding the aggregate recorded in the same transactions. */
    private static Map<UUID, String> nodeByInvocation(ExecutionStore store, ExecutionKey key) {
        var bindings = new HashMap<UUID, String>();
        for (Traversal traversal : await(store.load(key)).state().traversals().values()) {
            for (NodeInvocation invocation : traversal.invocations().values()) {
                bindings.put(invocation.invocationId(), invocation.nodeId());
            }
        }
        return Map.copyOf(bindings);
    }

    private static GraphDefinition fanInOfTwo() {
        return JoinMiniGraphs.fanIn(2, JoinMiniGraphs.quorum(2));
    }

    private static GraphDefinition chain() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("work", "work"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "work"),
                GraphEdge.to("work", "end")));
    }

    private static BehaviorRegistry chainBehaviors() {
        return new BehaviorRegistry().register("work", message ->
                CompletableFuture.completedFuture(NodeResult.continueWith("done")));
    }

    private static BehaviorRegistry branchBehaviors() {
        var registry = new BehaviorRegistry();
        for (String branch : List.of("b0", "b1")) {
            registry.register(branch, message ->
                    CompletableFuture.completedFuture(NodeResult.continueWith("from-" + branch)));
        }
        return registry;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(BOUND_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("the gating arrival was never recorded; the ordering this "
                        + "test depends on never happened and its result would be a coin toss");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    /**
     * Opens a latch once a named branch's arrival is durably recorded, so a test can hold another
     * branch back until being second is a fact rather than a hope.
     *
     * <p>The observation point is the store write, not the behaviour's return: a branch that has
     * merely finished executing has not yet presented itself at the join, so gating on completion
     * would still let the two arrivals race and would make this a test whose verdict depends on the
     * build machine.</p>
     */
    private static final class ArrivalGatedJoinStore implements JoinStore {
        private final JoinStore delegate;
        private final String branchId;
        private final CountDownLatch recorded;

        private ArrivalGatedJoinStore(JoinStore delegate, String branchId, CountDownLatch recorded) {
            this.delegate = delegate;
            this.branchId = branchId;
            this.recorded = recorded;
        }

        @Override
        public boolean durable() {
            return delegate.durable();
        }

        @Override
        public CompletionStage<Optional<JoinRecord>> load(JoinKey key) {
            return delegate.load(key);
        }

        @Override
        public CompletionStage<JoinRecord> compareAndSet(JoinRecord desired) {
            boolean gating = desired.branches().containsKey(branchId);
            return delegate.compareAndSet(desired).whenComplete((stored, failure) -> {
                if (gating && failure == null) {
                    recorded.countDown();
                }
            });
        }

        @Override
        public CompletionStage<Boolean> discard(JoinKey key) {
            return delegate.discard(key);
        }

        @Override
        public CompletionStage<List<JoinRecord>> openJoins(String tenantId) {
            return delegate.openJoins(tenantId);
        }

        @Override
        public CompletionStage<Long> recordCount(String tenantId) {
            return delegate.recordCount(tenantId);
        }

        @Override
        public CompletionStage<Long> purgeSettledBefore(String tenantId, Instant cutoff) {
            return delegate.purgeSettledBefore(tenantId, cutoff);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
