package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.persistence.JoinBranchOutcome;
import ai.ravenroot.api.persistence.JoinKey;
import ai.ravenroot.api.persistence.JoinPhase;
import ai.ravenroot.api.persistence.JoinRecord;
import ai.ravenroot.api.persistence.JoinStore;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.JoinSemantics;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iteration behavior at the level the correlation actually lives: {@link JoinCoordinator} on the real
 * {@link InMemoryJoinStore}, driven arrival by arrival.
 *
 * <p>The graph-level proof is {@code JoinIterationRearmTest}. This is the other half, and it exists
 * because these properties — arrivals correlate by iteration, two
 * arrivals of one branch do not satisfy a join expecting three, a retry is not a lap — are
 * statements about the coordinator that a whole traversal can only demonstrate indirectly. Modelled
 * on {@code JoinSemanticsTest}: nothing is stubbed but the order of arrival.</p>
 */
class JoinIterationCorrelationTest {

    private final JoinTestEngine engine = new JoinTestEngine();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    // ------------------------------------------------- two arrivals of one branch are not a quorum

    /**
     * The asymmetric-duration case, which is what "correlated by iteration" is for. One branch that
     * is much faster than its siblings presents itself twice before they present once; those two
     * presentations belong to two different iterations of the join and must not, between them,
     * satisfy a join that is waiting for three <em>branches</em>.
     *
     * <p>The second presentation carries the same iteration here on purpose: that is what a
     * redelivery looks like, and the join must recognise it as one. A genuine second lap cannot be
     * constructed by hand at this level without lying about causality — a lap-1 arrival exists only
     * downstream of the join's own lap-0 firing — so the test that exercises a real second lap is the
     * graph-level one.</p>
     */
    @Test
    void twoArrivalsOfOneBranchDoNotSatisfyAJoinWaitingForThree() throws Exception {
        var monitor = new ExecutionMonitor();
        var coordinator = coordinator(monitor, allOf("a", "b", "c"));

        // Parks: one of three, and the quorum is still reachable.
        var parked = coordinator.arrive("join", arrival("a", 0, "a-first"));
        var second = await(coordinator.arrive("join", arrival("a", 0, "a-second")));

        var discarded = assertInstanceOf(JoinDecision.Discarded.class, second,
                "a second arrival of the same branch in the same iteration is a redelivery, not a contribution");
        assertEquals(JoinDecision.Discarded.Reason.DUPLICATE, discarded.reason());
        assertFalse(parked.toCompletableFuture().isDone(),
                "the join must not have fired: two arrivals of one branch are one branch");

        var parkedB = coordinator.arrive("join", arrival("b", 0, "b"));
        assertFalse(parkedB.toCompletableFuture().isDone(), "two of three is still short of the quorum");
        var proceed = assertInstanceOf(JoinDecision.Proceed.class,
                await(coordinator.arrive("join", arrival("c", 0, "c"))));

        assertEquals(3, proceed.arrivals().size());
        assertEquals(List.of("a-second", "b", "c"),
                proceed.arrivals().stream().sorted(java.util.Comparator.comparing(JoinArrival::branchId))
                        .map(JoinArrival::payload).toList(),
                "the redelivery refreshed the payload without moving the quorum, so the newest one is carried");
    }

    // ------------------------------------------------------------- a retry is not a second iteration

    /**
     * The invariance that makes iteration correlation independent of PERS-04.
     *
     * <p>Whether a retried upstream node is modelled as a new attempt or a new invocation is PERS-04's
     * question and is still open. The iteration token is read from neither: it lives in the content of
     * the delivery, so a redelivery along the same edge carries the same lap however its retry is
     * counted. The two arrivals here differ in <em>every</em> identifier a retry could plausibly
     * change and are still the same arrival.</p>
     */
    @Test
    void aRetryWithDifferentParentInvocationsStaysInTheSameIteration() throws Exception {
        var coordinator = coordinator(new ExecutionMonitor(), allOf("a", "b"));

        var parked = coordinator.arrive("join",
                new JoinArrival(BranchId.of("a"), "first", Map.of(), Set.of(UUID.randomUUID())));
        var retried = await(coordinator.arrive("join",
                new JoinArrival(BranchId.of("a"), "second", Map.of(), Set.of(UUID.randomUUID()))));

        assertEquals(JoinDecision.Discarded.Reason.DUPLICATE,
                assertInstanceOf(JoinDecision.Discarded.class, retried).reason());
        assertFalse(parked.toCompletableFuture().isDone(), "a retry must not advance the iteration and fire the join");

        var proceed = assertInstanceOf(JoinDecision.Proceed.class,
                await(coordinator.arrive("join", arrival("b", 0, "b"))));
        assertEquals(2, proceed.arrivals().size());
    }

    /**
     * The same invariance across a restart, which is where the marker earns its keep.
     *
     * <p>A record left {@link JoinPhase#OPEN} by a firing looks, to a naive reader, exactly like a
     * record still being filled: same branches, same phase. Without {@link JoinRecord#firedThrough()}
     * the redelivery that follows a restart would meet the quorum a second time and run everything
     * downstream twice. {@link JoinPhase#SATISFIED} used to prevent that and is not available to a
     * join that may fire again.</p>
     */
    @Test
    void aRestartCompletesTheIterationExactlyOnceAndRefusesTheNextRedelivery() throws Exception {
        JoinStore store = new InMemoryJoinStore();
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();

        var dying = coordinator(new ExecutionMonitor(), allOf("a", "b"), store, processInstanceId, traversalId);
        dying.arrive("join", arrival("a", 0, "a"));

        // A new process: the record survives, the payloads do not.
        var monitor = new ExecutionMonitor();
        var revived = coordinator(monitor, allOf("a", "b"), store, processInstanceId, traversalId);
        assertInstanceOf(JoinDecision.Discarded.class, await(revived.arrive("join", arrival("a", 0, "a"))),
                "the store says this branch counted, so its redelivery refreshes the payload and nothing else");
        assertInstanceOf(JoinDecision.Proceed.class, await(revived.arrive("join", arrival("b", 0, "b"))));

        var afterFiring = await(revived.arrive("join", arrival("a", 0, "a")));
        assertEquals(JoinDecision.Discarded.Reason.LATE,
                assertInstanceOf(JoinDecision.Discarded.class, afterFiring).reason(),
                "the iteration already fired, so its redelivery must not fire it again");
        assertEquals(1, monitor.eventsAfter(0).stream()
                .filter(event -> event.type() == ExecutionEventType.JOIN_SATISFIED).count(),
                "exactly one firing across the restart");

        var record = await(store.load(new JoinKey(TestIdentities.TENANT_A.tenantId(), processInstanceId,
                traversalId, "join"))).orElseThrow();
        assertEquals(JoinPhase.OPEN, record.phase(), "a join that re-arms stays open");
        assertEquals(Integer.valueOf(0), record.firedThrough(), "and says how far it has fired instead");
    }

    // ------------------------------------------------------------------------------- the diagnostic

    /**
     * Above the threshold the diagnostic is reported once, not once per arrival.
     *
     * <p>The laps here are minted by hand, which is what makes this a test of the <em>mechanism</em>
     * — the counting, the threshold, the once-only latch — and not of the phenomenon. That the number
     * is reachable at all from an executing graph is
     * {@code JoinIterationRearmTest#reportsTheIterationBacklogFromARealCycle}, and it has to be a
     * separate test because emitting the diagnostic through the coordinator here does not prove that
     * an executing graph can reach it.</p>
     */
    @Test
    void reportsTheIterationDiagnosticOncePerCrossing() throws Exception {
        var monitor = new ExecutionMonitor();
        var coordinator = coordinator(monitor, allOf("a", "b"));

        for (int lap = 0; lap <= JoinCoordinator.ITERATION_BACKLOG_THRESHOLD + 2; lap++) {
            coordinator.arrive("join", arrival("a", lap, "a-" + lap));
        }

        assertEquals(1, backlogEvents(monitor).size(),
                "the event marks a crossing of the threshold, not every arrival on the far side of it");
        assertTrue(backlogEvents(monitor).getFirst().detail()
                        .contains("threshold=" + JoinCoordinator.ITERATION_BACKLOG_THRESHOLD),
                "the reader must not have to know the runtime's internal default to interpret the count");
    }

    /** And below it, nothing: a join that is merely running is not a join that is accumulating. */
    @Test
    void reportsNoIterationDiagnosticBelowTheThreshold() throws Exception {
        var monitor = new ExecutionMonitor();
        var coordinator = coordinator(monitor, allOf("a", "b"));

        for (int lap = 0; lap < JoinCoordinator.ITERATION_BACKLOG_THRESHOLD; lap++) {
            coordinator.arrive("join", arrival("a", lap, "a-" + lap));
        }

        assertEquals(List.of(), backlogEvents(monitor));
    }

    // --------------------------------------------------------- an incomplete iteration is not silence

    /**
     * A traversal that ends with an iteration half-filled fails, naming the join.
     *
     * <p>{@code releaseWaiters} and {@code abandonedBranchFailure} prevent a traversal from reporting
     * success while a branch remains parked. The parking latch must be per iteration: a shared latch
     * would already be tripped by lap 0, so the branches of lap 1 would never park, nothing would be
     * found here, and the traversal would report success after dropping that lap.</p>
     */
    @Test
    void anIterationLeftIncompleteAtTheEndFailsTheTraversalNamingTheJoin() throws Exception {
        var coordinator = coordinator(new ExecutionMonitor(), allOf("a", "b", "c"));

        coordinator.arrive("join", arrival("a", 0, "a0"));
        coordinator.arrive("join", arrival("b", 0, "b0"));
        assertInstanceOf(JoinDecision.Proceed.class, await(coordinator.arrive("join", arrival("c", 0, "c0"))));

        // Lap 1: two of three, and then the traversal ends.
        var strandedA = coordinator.arrive("join", arrival("a", 1, "a1"));
        var strandedB = coordinator.arrive("join", arrival("b", 1, "b1"));
        assertFalse(strandedA.toCompletableFuture().isDone(), "the second lap's branches must park, not be answered");

        await(coordinator.terminate());

        JoinFailureException abandoned = coordinator.abandonedBranchFailure();
        assertNotNull(abandoned, "a traversal that ends with an iteration half-filled has not succeeded");
        assertEquals("join", abandoned.nodeId());
        assertEquals(List.of("a", "b"), abandoned.arrived().stream().sorted().toList(),
                "the verdict describes the incomplete iteration, not every branch of every completed one");
        for (var stranded : List.of(strandedA, strandedB)) {
            assertInstanceOf(JoinFailureException.class,
                    assertThrows(ExecutionException.class,
                            () -> stranded.toCompletableFuture().get(5, TimeUnit.SECONDS)).getCause());
        }
    }

    // ------------------------------------------------------------------------------ deadline per lap

    /** Healthy laps are not measured against one deadline armed at the first arrival. */
    @Test
    void healthyLapsNeverTimeOutAndLeaveNoLiveDeadline() throws Exception {
        var monitor = new ExecutionMonitor();
        var coordinator = coordinator(monitor, withTimeout(allOf("a", "b")));

        for (int lap = 0; lap < 3; lap++) {
            coordinator.arrive("join", arrival("a", lap, "a-" + lap));
            assertInstanceOf(JoinDecision.Proceed.class, await(coordinator.arrive("join", arrival("b", lap, "b-" + lap))));
        }

        assertEquals(0, coordinator.liveTimeoutCount(), "each lap's deadline is released when that lap fires");
        assertEquals(0, engine.manualScheduler().liveCount());
        assertEquals(3, engine.manualScheduler().cancelledCount(), "one deadline armed and released per lap");
        assertEquals(0, monitor.eventsAfter(0).stream()
                .filter(event -> event.type() == ExecutionEventType.JOIN_FAILED).count());
    }

    /** A lap that genuinely stalls still has a deadline of its own, and it still fires. */
    @Test
    void aStalledLaterIterationStillTimesOut() throws Exception {
        var monitor = new ExecutionMonitor();
        var coordinator = coordinator(monitor, withTimeout(allOf("a", "b")));

        coordinator.arrive("join", arrival("a", 0, "a0"));
        assertInstanceOf(JoinDecision.Proceed.class, await(coordinator.arrive("join", arrival("b", 0, "b0"))));

        // Lap 1 receives one branch and nothing more.
        var stranded = coordinator.arrive("join", arrival("a", 1, "a1"));
        assertEquals(1, engine.manualScheduler().liveCount(), "the second lap must have a deadline of its own");

        engine.manualScheduler().fireAll();

        var failure = assertInstanceOf(JoinFailureException.class,
                assertThrows(ExecutionException.class,
                        () -> stranded.toCompletableFuture().get(5, TimeUnit.SECONDS)).getCause());
        assertEquals(JoinFailureException.Reason.TIMEOUT, failure.reason());
        assertEquals(List.of("a"), failure.arrived(), "the verdict is about the stalled iteration alone");
        assertEquals(List.of("b"), failure.outstanding());

        await(coordinator.terminate());
        assertEquals(0, coordinator.liveTimeoutCount(), "no deadline may outlive the traversal");
    }

    // ------------------------------------------------------------- what does not change (compatibility)

    /**
     * A legacy record — bare branch keys, {@link JoinPhase#OPEN}, no firing marker — is
     * read as iteration zero and completed by the redelivery, with no migration.
     */
    @Test
    void readsAPreIssueRecordAsIterationZero() throws Exception {
        JoinStore store = new InMemoryJoinStore();
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        var key = new JoinKey(TestIdentities.TENANT_A.tenantId(), processInstanceId, traversalId, "join");

        // Exactly the seven-component shape the previous runtime wrote.
        await(store.compareAndSet(new JoinRecord(key, JoinRecord.ABSENT_REVISION + 1,
                Map.of("a", JoinBranchOutcome.ARRIVED), JoinPhase.OPEN, Instant.now(), null, null)));

        var coordinator = coordinator(new ExecutionMonitor(), allOf("a", "b"), store, processInstanceId, traversalId);
        assertInstanceOf(JoinDecision.Discarded.class, await(coordinator.arrive("join", arrival("a", 0, "a"))));
        assertInstanceOf(JoinDecision.Proceed.class, await(coordinator.arrive("join", arrival("b", 0, "b"))));
    }

    /** An acyclic graph writes the branch keys it has always written: no suffix, nowhere. */
    @Test
    void anAcyclicGraphWritesBareBranchKeys() throws Exception {
        var backing = new InMemoryJoinStore();
        var monitor = new ExecutionMonitor();
        try (var manager = ai.ravenroot.core.graph.GraphManager.from(JoinMiniGraphs.fanIn(3));
             var runner = new GraphRunner(manager, engine, branches(), monitor,
                     ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(),
                     new EvictionDisabledJoinStore(backing), Clock.systemUTC())) {
            runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture().get(5, TimeUnit.SECONDS);
        }

        var records = await(backing.openJoins(TestIdentities.TENANT_A.tenantId()));
        assertEquals(1, records.size());
        assertEquals(Set.of("b0", "b1", "b2"), records.getFirst().branches().keySet(),
                "the iteration suffix appears only where the iteration is not zero, exactly as #ordinal does");
    }

    /**
     * A fan-in left undeclared under declared-join semantics gets no coordinator, so it gets no iteration machinery
     * either: {@code each} arrivals keep invoking the node independently and the token passes through
     * unread.
     */
    @Test
    void anUndeclaredFanInIsStillNotAJoin() {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("b0", "b0"),
                GraphNode.behavior("b1", "b1"),
                new GraphNode("merge", NodeKind.PASSTHROUGH, null, Map.of()),
                GraphNode.error("error"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "b0"),
                GraphEdge.to("start", "b1"),
                GraphEdge.to("b0", "merge"),
                GraphEdge.to("b1", "merge"),
                GraphEdge.to("merge", "end")),
                Map.of(JoinSemantics.MARKER_PROPERTY, JoinSemantics.DECLARED));

        assertEquals(Map.of(), JoinSpec.validate(graph));
    }

    // ------------------------------------------ a straggler arms nothing, and is never a failure

    /**
     * A branch that finishes after the quorum was already met must leave no deadline behind.
     *
     * <p>This is the ordinary case a {@code k of n} join exists for, and it is where the first
     * attempt at a per-iteration deadline went wrong. Arming from {@code firedThrough + 1} on every
     * report armed one on behalf of a branch that was about to be answered {@code LATE}, guarding an
     * iteration that on an acyclic graph will never receive anything — and since the record stays
     * {@code OPEN}, nothing downstream could tell that the join had finished.</p>
     */
    @Test
    void aStragglerAfterTheFiringArmsNoDeadline() throws Exception {
        var coordinator = coordinator(new ExecutionMonitor(), quorumOf(1, Duration.ofSeconds(30), "a", "b", "c"));

        assertInstanceOf(JoinDecision.Proceed.class, await(coordinator.arrive("join", arrival("a", 0, "a"))));
        assertEquals(0, coordinator.liveTimeoutCount(), "the fired iteration releases its own deadline");
        assertEquals(0, engine.manualScheduler().liveCount());

        await(coordinator.arrive("join", arrival("b", 0, "b")));

        assertEquals(0, coordinator.liveTimeoutCount(),
                "a straggler guards no iteration: the one it would arm for has said nothing and may never exist");
        assertEquals(0, engine.manualScheduler().liveCount());
        assertEquals(0, engine.manualScheduler().fireAll(), "there must be nothing left to fire");
    }

    /** And however many stragglers arrive, each is discarded and none is ever a failure. */
    @Test
    void everyStragglerIsDiscardedAndNoneIsAFailure() throws Exception {
        var coordinator = coordinator(new ExecutionMonitor(), quorumOf(1, Duration.ofSeconds(30), "a", "b", "c"));
        assertInstanceOf(JoinDecision.Proceed.class, await(coordinator.arrive("join", arrival("a", 0, "a"))));

        for (String straggler : List.of("b", "c")) {
            var decision = await(coordinator.arrive("join", arrival(straggler, 0, straggler)));
            assertEquals(JoinDecision.Discarded.Reason.LATE,
                    assertInstanceOf(JoinDecision.Discarded.class, decision,
                            () -> straggler + " must be discarded, never failed").reason());
        }
        assertEquals(0, engine.manualScheduler().liveCount());
    }

    /**
     * The correction must not have bought its safety by switching the re-arming off: a cyclic
     * {@code k of n} join still gets a deadline per lap, and still fires on every lap, with a
     * straggler of the previous lap in between.
     */
    @Test
    void aCyclicQuorumJoinStillArmsAndFiresOnEveryLap() throws Exception {
        var monitor = new ExecutionMonitor();
        var coordinator = coordinator(monitor, quorumOf(2, Duration.ofSeconds(30), "a", "b", "c"));

        var parkedFirst = coordinator.arrive("join", arrival("a", 0, "a0"));
        assertEquals(1, engine.manualScheduler().liveCount(), "the first report of a bucket arms its deadline");
        assertInstanceOf(JoinDecision.Proceed.class, await(coordinator.arrive("join", arrival("b", 0, "b0"))));
        assertFalse(parkedFirst.toCompletableFuture().isCompletedExceptionally());

        // The straggler of lap 0, in between, must change nothing about lap 1's deadline.
        await(coordinator.arrive("join", arrival("c", 0, "c0")));
        assertEquals(0, engine.manualScheduler().liveCount());

        coordinator.arrive("join", arrival("a", 1, "a1"));
        assertEquals(1, engine.manualScheduler().liveCount(), "the second lap gets a deadline of its own");
        assertInstanceOf(JoinDecision.Proceed.class, await(coordinator.arrive("join", arrival("b", 1, "b1"))));

        assertEquals(2, monitor.eventsAfter(0).stream()
                .filter(event -> event.type() == ExecutionEventType.JOIN_SATISFIED).count());
        assertEquals(0, coordinator.liveTimeoutCount());
        assertEquals(2, engine.manualScheduler().cancelledCount(), "one deadline armed and released per lap");
        assertEquals(0, engine.manualScheduler().fireAll());
    }

    // ----------------------------------------------------------------------------------- helpers

    private static JoinSpec quorumOf(int quorum, Duration timeout, String... branches) {
        return new JoinSpec("join", List.of(branches), quorum, timeout);
    }

    private static JoinArrival arrival(String branch, int lap, Object payload) {
        return new JoinArrival(BranchId.of(branch).atLap(lap), payload, Map.of(), Set.of());
    }

    private static JoinSpec allOf(String... branches) {
        return new JoinSpec("join", List.of(branches), branches.length, null);
    }

    private static JoinSpec withTimeout(JoinSpec spec) {
        return new JoinSpec(spec.nodeId(), spec.branches(), spec.quorum(), Duration.ofSeconds(30));
    }

    private JoinCoordinator coordinator(ExecutionMonitor monitor, JoinSpec spec) {
        return coordinator(monitor, spec, new InMemoryJoinStore(), UUID.randomUUID(), UUID.randomUUID());
    }

    private JoinCoordinator coordinator(ExecutionMonitor monitor, JoinSpec spec, JoinStore store,
                                        UUID processInstanceId, UUID traversalId) {
        var identity = new ExecutionMonitor.ExecutionIdentity(TestIdentities.TENANT_A, "join-test", "v1",
                processInstanceId, traversalId);
        return new JoinCoordinator(store, engine.scheduler(), monitor, identity, Map.of("join", spec),
                Clock.systemUTC());
    }

    private static BehaviorRegistry branches() {
        var registry = new BehaviorRegistry();
        for (String node : List.of("b0", "b1", "b2")) {
            registry.register(node, message -> java.util.concurrent.CompletableFuture.completedFuture(
                    ai.ravenroot.api.execution.NodeResult.continueWith("from-" + node)));
        }
        return registry;
    }

    private static List<ai.ravenroot.api.application.ExecutionEvent> backlogEvents(ExecutionMonitor monitor) {
        return monitor.eventsAfter(0).stream()
                .filter(event -> event.type() == ExecutionEventType.JOIN_ITERATION_BACKLOG)
                .toList();
    }

    private static <T> T await(java.util.concurrent.CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
