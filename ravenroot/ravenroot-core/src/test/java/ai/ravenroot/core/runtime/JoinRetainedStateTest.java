package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.persistence.JoinStore;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What bounds join state, and what evicts it.
 *
 * <p>Every assertion here is paired with the same measurement against
 * {@link EvictionDisabledJoinStore}, because "the count is zero" is only evidence if the identical
 * check is seen to fail when eviction is removed.</p>
 */
class JoinRetainedStateTest {

    /** Enough executions that per-execution retention is unmistakable rather than arguable. */
    private static final int EXECUTIONS = 500;

    /** Short enough that a shutdown which waits for the store is unmistakably slower than the bound. */
    private static final Duration SHORT_BOUND = Duration.ofMillis(300);

    /**
     * On a regression these are not slow tests, they are tests that never return, and a build that
     * hangs reports nothing at all.
     */
    private static final Duration NEVER_HANGS = Duration.ofSeconds(20);

    private final JoinTestEngine engine = new JoinTestEngine();
    private final ExecutionMonitor monitor = new ExecutionMonitor();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    @Test
    void retainsNoJoinRecordAfterFiveHundredSuccessfulExecutionsOnOneRunner() throws Exception {
        var store = new InMemoryJoinStore();
        long retained = runAll(store, EXECUTIONS, false);

        assertEquals(0, retained, "every settled join must be discarded when its traversal ends");
        assertEquals(0, store.totalRecordCount());
    }

    /**
     * The control for the assertion above. Without {@code discard} the same 500 executions retain
     * one record each, so the zero measured above is eviction working rather than nothing happening.
     */
    @Test
    void theSameFiveHundredExecutionsRetainOneRecordEachWithoutEviction() throws Exception {
        var backing = new InMemoryJoinStore();
        long retained = runAll(new EvictionDisabledJoinStore(backing), EXECUTIONS, false);

        assertEquals(EXECUTIONS, retained,
                "the mutant must retain exactly one record per execution, or the bound assertion is vacuous");
    }

    /** A failing traversal is the path that leaks, because nothing on it looks like a success. */
    @Test
    void retainsNoJoinRecordAfterFiveHundredFailedExecutions() throws Exception {
        var store = new InMemoryJoinStore();
        long retained = runAll(store, EXECUTIONS, true);

        assertEquals(0, retained, "a traversal that failed must still discard its join records");
    }

    @Test
    void theSameFiveHundredFailedExecutionsRetainOneRecordEachWithoutEviction() throws Exception {
        var backing = new InMemoryJoinStore();
        long retained = runAll(new EvictionDisabledJoinStore(backing), EXECUTIONS, true);
        assertEquals(EXECUTIONS, retained);
    }

    @Test
    void retainsNoCoordinatorOrScheduledTimeoutAfterFiveHundredExecutions() throws Exception {
        var store = new InMemoryJoinStore();
        var graph = JoinMiniGraphs.fanIn(3, JoinMiniGraphs.quorumWithTimeout(3, "PT30S"));

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, branches(), monitor,
                     ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store,
                     java.time.Clock.systemUTC())) {
            for (int index = 0; index < EXECUTIONS; index++) {
                runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            assertEquals(0, runner.liveCoordinatorCount(), "no traversal may outlive its execution");
            assertEquals(0, runner.liveJoinTimeoutCount(), "every scheduled join timeout must be cancelled");
        }
        assertEquals(0, engine.manualScheduler().liveCount(),
                "a timeout left live keeps its traversal reachable from the scheduler until it fires");
        assertEquals(EXECUTIONS, engine.manualScheduler().cancelledCount());
        assertEquals(0, store.totalRecordCount());
    }

    /** Concurrent executions bound the peak too: fan-in nodes times traversals in flight, not total. */
    @Test
    void boundsPeakRetentionByTraversalsInFlightRatherThanByTotalExecutions() throws Exception {
        var store = new InMemoryJoinStore();
        int inFlight = 16;
        var gate = new CompletableFuture<NodeResult>();
        var graph = JoinMiniGraphs.fanIn(2);

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, gated(gate), monitor,
                     ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store,
                     java.time.Clock.systemUTC())) {
            var executions = new ArrayList<CompletionStage<GraphExecutionResult>>();
            for (int index = 0; index < inFlight; index++) {
                executions.add(runner.execute(TestIdentities.TENANT_A, "in"));
            }
            awaitRecordCount(store, inFlight);
            // One join node, one record per traversal in flight. The graph has one fan-in, so this
            // is the declared bound rather than an observation that happens to be small.
            assertEquals(inFlight, store.totalRecordCount());

            gate.complete(NodeResult.continueWith("from-b1"));
            for (var execution : executions) {
                execution.toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            assertEquals(0, store.totalRecordCount());
        }
    }

    @Test
    void closeReleasesJoinStateOfATraversalStillInFlight() throws Exception {
        var store = new InMemoryJoinStore();
        var blocked = new CompletableFuture<NodeResult>();
        var graph = JoinMiniGraphs.fanIn(2, JoinMiniGraphs.quorumWithTimeout(2, "PT30S"));

        var manager = GraphManager.from(graph);
        var runner = new GraphRunner(manager, engine, gated(blocked), monitor,
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store,
                java.time.Clock.systemUTC());
        var execution = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture();
        awaitRecordCount(store, 1);

        runner.close();
        manager.close();

        assertEquals(0, store.totalRecordCount(), "close() must release the join records it accumulated");
        assertEquals(0, engine.manualScheduler().liveCount(), "close() must cancel scheduled join timeouts");
        assertEquals(0, runner.liveCoordinatorCount());
        // The parked branch is released rather than left pending, so nothing waits on a dead runner.
        assertThrows(Exception.class, () -> execution.get(5, TimeUnit.SECONDS));
        blocked.complete(NodeResult.continueWith("from-b1"));
    }

    /**
     * The window between asking a scheduler for a timeout and receiving the handle that cancels it.
     *
     * <p>{@code schedule} cannot be called while holding the coordinator's termination lock, because
     * an implementation that blocks there would block every terminating thread behind it — so the
     * call is made outside the lock, and the window exists. A {@code close()} landing inside it used
     * to run its cancellation against a field that had not been assigned yet, cancel nothing, and
     * return; the timer assigned a moment later then had no owner and no way to be cancelled, and
     * held the coordinator, its payloads and the traversal's security context for the entire
     * configured duration of a traversal that had already been shut down.</p>
     *
     * <p>The scheduler blocks inside {@code schedule}, and the package-private runner seam holds
     * {@code close()} immediately after it has relinquished the timeout under the handoff lock.
     * Releasing the scheduler only after that observation constructs the contested interleaving:
     * the arming side must return a handle after terminal relinquishment and therefore cancel it
     * rather than publish it.</p>
     *
     * <p><strong>No wall clock, and no negative half here.</strong> This test used to sleep 200ms and
     * then assert that {@code close()} was still running. That assertion was a bet on a build
     * machine's scheduling rather than a measurement — it is the intermittent CI failure this test
     * was known for — and it did not discriminate the ordering it appeared to be about: while a
     * {@code schedule} call holds a drain slot, {@code close()} cannot return on <em>either</em>
     * ordering of the handoff, so it passed on the broken one too. Replacing it with a shorter or
     * longer wait would keep both problems.</p>
     *
     * <p>The property it was reaching for — that termination does not complete while a timeout it has
     * already accounted for is still armed — is asserted deterministically, without a clock, by
     * {@link #terminateDoesNotCompleteWhileTheTimeoutItIsCancellingIsStillArmed()}. That test can do
     * so because it drives {@code terminate()} in its own program order instead of across a thread it
     * can only observe. What remains here is the integration-level check that a timer created inside
     * the window is not left armed once {@code close()} has returned.</p>
     */
    @Test
    void closeCancelsATimeoutThatWasStillBeingScheduledWhenCloseRan() throws Exception {
        var store = new InMemoryJoinStore();
        var blocked = new CompletableFuture<NodeResult>();
        var graph = JoinMiniGraphs.fanIn(2, JoinMiniGraphs.quorumWithTimeout(2, "PT30S"));
        var timeoutRelinquished = new CountDownLatch(1);
        var releaseRelinquishment = new CountDownLatch(1);

        engine.manualScheduler().blockInsideSchedule();

        var manager = GraphManager.from(graph);
        var runner = new GraphRunner(manager, engine, gated(blocked), monitor,
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store,
                java.time.Clock.systemUTC(), GraphRunner.DEFAULT_SHUTDOWN_BOUND, () -> {
                    timeoutRelinquished.countDown();
                    try {
                        releaseRelinquishment.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
        var execution = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture();

        assertTrue(engine.manualScheduler().awaitInsideSchedule(5_000),
                "the runtime must have reached the scheduler");

        var closing = new Thread(runner::close, "close-during-schedule");
        try {
            closing.start();
            assertTrue(timeoutRelinquished.await(5, TimeUnit.SECONDS),
                    "close() must relinquish the timeout before scheduling can return");
            engine.manualScheduler().releaseSchedule();
        } finally {
            // A failed rendezvous must not leave either test thread holding the engine for later tests.
            engine.manualScheduler().releaseSchedule();
            releaseRelinquishment.countDown();
            closing.join(10_000);
            manager.close();
        }
        assertFalse(closing.isAlive(), "close() must complete once the scheduler returns");

        assertEquals(0, engine.manualScheduler().liveCount(),
                "a timeout created inside close()'s window must still be cancelled, not left to hold the traversal");
        assertEquals(0, runner.liveJoinTimeoutCount(),
                "no timeout may still be counted live once close() has returned");
        assertEquals(0, store.totalRecordCount());
        assertEquals(0, runner.liveCoordinatorCount());
        assertFalse(execution.isDone() && !execution.isCompletedExceptionally(),
                "close() must not make a traversal with a blocked branch report success");
        blocked.complete(NodeResult.continueWith("from-b1"));
    }

    /**
     * The quiescence contract at the completion boundary: {@code terminate()} may not complete while
     * a timeout it has already accounted for is still armed at the scheduler.
     *
     * <p>Arming a deadline claims a slot in the drain barrier, and that slot used to be released in
     * the {@code finally} that closed the scheduler call — so it was released <em>before</em> the
     * handle was published under the handoff lock and before the losing side cancelled it. The drain
     * could therefore open, {@code terminate()}'s stage complete and {@code close()} return with the
     * task still armed and {@link JoinCoordinator#liveTimeoutCount()} still one, the cancellation
     * arriving an instant later on another thread.</p>
     *
     * <p><strong>Nothing is lost when that happens</strong>, which is why it survived a green suite
     * and why this test asserts the boundary rather than hunting for a leak. The arming thread
     * cancels its own timer a few instructions later, and a timer that fired inside the window is
     * refused by the same barrier in {@code onTimeout}, so no write can land after the discard. What
     * is broken is only the guarantee an embedder relies on when it closes the engine and then
     * dismantles the scheduler underneath it: a task the contract says cannot exist.</p>
     *
     * <p>Deterministic rather than timed, and deliberately so. The scheduler blocks inside
     * {@code schedule} so the window is entered on purpose, then blocks inside {@code cancel} so the
     * arming thread is held at exactly the instruction between releasing its drain slot and
     * cancelling. On the broken ordering that same thread has already completed the stage before it
     * reaches {@code cancel}, so the observation below is ordered by one thread's own program order
     * and the assertion fails every time rather than under load. Reading the counts <em>after</em>
     * the fact — which is all a sleep can do — cannot distinguish "cancelled before completion" from
     * "cancelled a moment after", and that indistinguishability is the whole reason this held.</p>
     */
    @Test
    void terminateDoesNotCompleteWhileTheTimeoutItIsCancellingIsStillArmed() throws Exception {
        var store = new InMemoryJoinStore();
        engine.manualScheduler().blockInsideSchedule();
        var coordinator = coordinatorWithTimeout(store);

        var arming = new Thread(() -> coordinator.arrive("join", arrival("b0")), "arming-timeout");
        arming.start();
        assertTrue(engine.manualScheduler().awaitInsideSchedule(5_000),
                "the coordinator must have reached the scheduler");

        var termination = coordinator.terminate().toCompletableFuture();
        assertFalse(termination.isDone(),
                "the drain slot is held by the schedule call still in flight, so the drain cannot be open");

        // Read at the instant the stage completes, on whatever thread completes it. Reading them
        // after the join below would measure a world in which the cancellation has already caught
        // up, which is true on both orderings and therefore proves nothing.
        var liveAtCompletion = new java.util.concurrent.atomic.AtomicLong(-1);
        var countedAtCompletion = new java.util.concurrent.atomic.AtomicInteger(-1);
        termination.thenRun(() -> {
            liveAtCompletion.set(engine.manualScheduler().liveCount());
            countedAtCompletion.set(coordinator.liveTimeoutCount());
        });

        engine.manualScheduler().blockInsideCancel();
        engine.manualScheduler().releaseSchedule();
        try {
            assertTrue(engine.manualScheduler().awaitInsideCancel(5_000),
                    "the arming thread must cancel the timer it created inside terminate()'s window");
            assertFalse(termination.isDone(),
                    "terminate() completed while the timeout it is cancelling was still armed: close() "
                            + "can return and the embedder can dismantle the scheduler under a live task");
        } finally {
            engine.manualScheduler().releaseCancel();
        }

        termination.get(5, TimeUnit.SECONDS);
        arming.join(5_000);

        assertEquals(0, liveAtCompletion.get(),
                "no task may be armed at the scheduler at the moment terminate() completes");
        assertEquals(0, countedAtCompletion.get(),
                "liveTimeouts must already be zero at the moment terminate() completes");
        assertEquals(0, engine.manualScheduler().liveCount());
        assertEquals(0, coordinator.liveTimeoutCount());
        assertEquals(0, store.totalRecordCount());
    }

    /**
     * The release of process memory must not be behind the wait for the store.
     *
     * <p>Termination is two things wearing one name: discarding the store record, which genuinely
     * has to wait for a settle that may still be inside its compare-and-set, and releasing what this
     * process holds — a scheduled timer and the branches parked on the join. Sequencing the second
     * behind the first made a purely local release conditional on a dependency that may never
     * answer. A store that neither succeeds nor fails then left the deadline armed and the branches
     * pending, and the runner's bounded wait walked away from both: the timer went on holding the
     * coordinator, every arrival payload and the ingress security context for up to
     * {@link JoinSpec#MAX_TIMEOUT}.</p>
     *
     * <p>Asserted against the coordinator rather than through a graph because the assertion is about
     * what has happened <em>while termination is still in progress</em>. Through a runner the only
     * observable moment is after {@code close()} returns, which cannot distinguish "released
     * immediately" from "released when the store finally answered"; here the store never answers at
     * all, so the release can only have come from termination itself.</p>
     */
    @Test
    void terminateReleasesTimeoutAndParkedBranchWithoutWaitingForTheStore() throws Exception {
        // The second compare-and-set stalls, so b0 has genuinely settled and parked before the
        // store goes silent rather than racing it.
        var store = new StalledJoinStore(new InMemoryJoinStore(), 2);
        var coordinator = coordinatorWithTimeout(store);

        var parked = coordinator.arrive("join", arrival("b0")).toCompletableFuture();
        assertFalse(parked.isDone(), "b0 must be parked: one arrival cannot satisfy a quorum of two");
        assertEquals(1, engine.manualScheduler().liveCount(), "the join's deadline must be armed");

        var stuck = coordinator.arrive("join", arrival("b1")).toCompletableFuture();
        assertTrue(store.awaitStall(5_000), "b1 must have reached the store");
        assertFalse(stuck.isDone(), "b1 is inside the store, so the drain barrier cannot open");

        var termination = coordinator.terminate().toCompletableFuture();

        assertThrows(java.util.concurrent.ExecutionException.class, () -> parked.get(5, TimeUnit.SECONDS),
                "the parked branch must be released by terminate(), not by the store answering");
        assertEquals(0, coordinator.liveTimeoutCount(), "the armed deadline must be cancelled");
        assertEquals(0, engine.manualScheduler().liveCount(),
                "a deadline left with the scheduler holds the coordinator and its payloads until it fires");
        // The other half of the same property: the retention bound is not being bought by
        // discarding the record early. That still waits, and here it waits forever.
        assertFalse(termination.isDone(), "the record discard must still be sequenced behind the drain");
    }

    /**
     * Bounded shutdown must coexist with early release: releasing early must not become waiting late.
     */
    @Test
    void closeStillReturnsWithinItsBoundWhenTheJoinStoreStopsAnswering() throws Exception {
        var store = new StalledJoinStore(new InMemoryJoinStore());
        var graph = JoinMiniGraphs.fanIn(2, JoinMiniGraphs.quorumWithTimeout(2, "PT10S"));

        var manager = GraphManager.from(graph);
        var runner = new GraphRunner(manager, engine, branches(), monitor,
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store,
                java.time.Clock.systemUTC(), SHORT_BOUND);
        runner.execute(TestIdentities.TENANT_A, "in");
        assertTrue(store.awaitStall(5_000), "the traversal must have reached the store");

        Duration elapsed = assertTimeoutPreemptively(NEVER_HANGS, () -> {
            long start = System.nanoTime();
            runner.close();
            return Duration.ofNanos(System.nanoTime() - start);
        });
        manager.close();

        assertTrue(elapsed.toMillis() < 5_000,
                "close() took " + elapsed.toMillis() + "ms against a bound of " + SHORT_BOUND
                        + ": the wait for the store is no longer bounded");
        assertEquals(0, engine.manualScheduler().liveCount(),
                "the deadline must be cancelled even though the drain never finished");
    }

    /**
     * The instrument must not be reset by the very event it exists to report.
     *
     * <p>{@code liveJoinTimeoutCount()} sums over the coordinators the runner still holds, so
     * clearing that map as shutdown gives up made the count read zero precisely when a timeout had
     * been left armed — and no assertion written against it could ever have seen the leak. The
     * scheduler here refuses cancellation, which is the only way to hold a timeout armed past
     * {@code close()} once the release itself is correct: without that mutant this test would be
     * asserting zero against zero and would pass on the blind instrument too.</p>
     */
    @Test
    void reportsTheLiveTimeoutItCouldNotRelease() throws Exception {
        var store = new StalledJoinStore(new InMemoryJoinStore());
        var blocked = new CompletableFuture<NodeResult>();
        var graph = JoinMiniGraphs.fanIn(2, JoinMiniGraphs.quorumWithTimeout(2, "PT10S"));
        engine.manualScheduler().refuseCancellation();

        var manager = GraphManager.from(graph);
        var runner = new GraphRunner(manager, engine, gated(blocked), monitor,
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store,
                java.time.Clock.systemUTC(), SHORT_BOUND);
        // b1 never leaves its node, so only b0 reaches the join and no branch is ever parked. That
        // is what keeps the traversal from completing and retiring its own coordinator, so what the
        // count reports afterwards is shutdown's decision rather than a race with cleanup.
        runner.execute(TestIdentities.TENANT_A, "in");
        assertTrue(store.awaitStall(5_000), "the traversal must have reached the store");

        assertTimeoutPreemptively(NEVER_HANGS, runner::close);
        manager.close();

        assertEquals(1, engine.manualScheduler().liveCount(),
                "the mutant must genuinely leave the deadline armed, or this asserts nothing");
        assertTrue(runner.liveJoinTimeoutCount() > 0,
                "the diagnostic reported no live timeout while the scheduler still holds one");
        assertTrue(runner.liveCoordinatorCount() > 0,
                "a traversal whose drain never finished must stay observable, not be cleared away");
        blocked.complete(NodeResult.continueWith("from-b1"));
    }

    /**
     * The crash backstop: records a dead runtime left behind are reclaimable and OPEN ones are not.
     *
     * <p><b>Which records are settled changed with re-arming, and the evidence changed with it.</b> The
     * orphans here used to come from fifty <em>successful</em> traversals, because a join that fired
     * wrote {@link ai.ravenroot.api.persistence.JoinPhase#SATISFIED} and a satisfied record is
     * settled. A join that re-arms cannot write that phase — it may still fire again on the next lap,
     * and saying otherwise is precisely the defect re-arming fixes — so its record stays
     * {@link ai.ravenroot.api.persistence.JoinPhase#OPEN} and this sweep must not touch it. Fifty
     * failing traversals produce the terminal records the sweep is for, and the second half of the
     * test now states the new fact rather than leaving it to be discovered by a leak: an orphan of a
     * join that fired is <em>not</em> reclaimable this way, and belongs to traversal-level recovery
     * (PERS-04), which is the same place an orphan of a join still waiting has always belonged.</p>
     */
    @Test
    void purgesSettledRecordsLeftBehindWithoutTouchingOpenOnes() throws Exception {
        var backing = new InMemoryJoinStore();
        runAll(new EvictionDisabledJoinStore(backing), 50, true);
        assertEquals(50, backing.totalRecordCount(), "the mutant left 50 orphans, as a crash would");

        long purged = backing.purgeSettledBefore(TestIdentities.TENANT_A.tenantId(), Instant.now().plusSeconds(60))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(50, purged);
        assertEquals(0, backing.totalRecordCount());

        // The other half, with re-arming stated as a property rather than left implicit: a join
        // that fired successfully is still open, because it re-arms, so this sweep leaves it alone.
        var afterSuccess = new InMemoryJoinStore();
        runAll(new EvictionDisabledJoinStore(afterSuccess), 50, false);
        assertEquals(50, afterSuccess.totalRecordCount());
        assertEquals(0, afterSuccess.purgeSettledBefore(TestIdentities.TENANT_A.tenantId(),
                        Instant.now().plusSeconds(60)).toCompletableFuture().get(5, TimeUnit.SECONDS),
                "a join that fired is OPEN because it may fire again, so it is not this sweep's to reclaim");
    }

    // ----------------------------------------------------------------------------------- helpers

    private long runAll(JoinStore store, int executions, boolean failOneBranch) throws Exception {
        var graph = JoinMiniGraphs.fanIn(3);
        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, failOneBranch ? failing() : branches(), monitor,
                     ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store,
                     java.time.Clock.systemUTC())) {
            for (int index = 0; index < executions; index++) {
                var execution = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture();
                if (failOneBranch) {
                    assertThrows(Exception.class, () -> execution.get(5, TimeUnit.SECONDS));
                } else {
                    execution.get(5, TimeUnit.SECONDS);
                }
            }
        }
        return store.recordCount(TestIdentities.TENANT_A.tenantId()).toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
    }

    private static BehaviorRegistry branches() {
        var registry = new BehaviorRegistry();
        for (String node : List.of("b0", "b1", "b2", "b3")) {
            registry.register(node, message ->
                    CompletableFuture.completedFuture(NodeResult.continueWith("from-" + node)));
        }
        return registry;
    }

    private static BehaviorRegistry failing() {
        var registry = new BehaviorRegistry();
        for (String node : List.of("b0", "b1", "b2", "b3")) {
            registry.register(node, message -> "b1".equals(message.nodeId())
                    ? CompletableFuture.failedFuture(new IllegalStateException("branch exploded"))
                    : CompletableFuture.completedFuture(NodeResult.continueWith("from-" + node)));
        }
        return registry;
    }

    private static BehaviorRegistry gated(CompletableFuture<NodeResult> gate) {
        var registry = new BehaviorRegistry();
        for (String node : List.of("b0", "b2", "b3")) {
            registry.register(node, message ->
                    CompletableFuture.completedFuture(NodeResult.continueWith("from-" + node)));
        }
        registry.register("b1", message -> gate);
        return registry;
    }

    /**
     * Termination distinguishes a join that settled from one that was still holding a branch.
     *
     * <p>Releasing parked branches is something termination has to do either way — a pending future
     * on a traversal that no longer exists is a thread parked forever. But <em>how many</em> it had
     * to release is not bookkeeping. Zero says every branch of every join was accounted for. More
     * than zero says the traversal ended while a branch was still waiting for a verdict, which is
     * only reachable when that branch was abandoned rather than resolved, and a traversal that
     * abandoned a branch has not succeeded no matter what its top-level stage says.</p>
     *
     * <p>Tested here rather than through a graph because the graph-level path that abandons a
     * <em>parked</em> branch needs a join whose quorum is short of arrivals it has already recorded
     * — the post-restart shape, where the store says a branch arrived and no process holds its
     * payload. This asserts the mechanism the runner reads; the shape that reaches it through a
     * graph is not covered.</p>
     */
    @Test
    void reportsTheBranchItHadToAbandonWhenTheTraversalEndedWithItStillParked() throws Exception {
        var store = new InMemoryJoinStore();
        var coordinator = coordinator(store);

        var parked = coordinator.arrive("join", new JoinArrival("b0", "from-b0", Map.of(), java.util.Set.of()))
                .toCompletableFuture();
        coordinator.terminate().toCompletableFuture().get(5, TimeUnit.SECONDS);

        JoinFailureException abandoned = coordinator.abandonedBranchFailure();
        assertTrue(abandoned != null, "a branch was parked when the traversal ended and must be reported");
        assertEquals("join", abandoned.nodeId());
        assertThrows(java.util.concurrent.ExecutionException.class, () -> parked.get(5, TimeUnit.SECONDS),
                "the parked branch itself is released with the same verdict rather than left pending");
    }

    /** The control: a coordinator that never parked a branch has nothing to report. */
    @Test
    void reportsNothingAbandonedWhenNoBranchWasEverParked() throws Exception {
        var store = new InMemoryJoinStore();
        var coordinator = coordinator(store);

        coordinator.terminate().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(null, coordinator.abandonedBranchFailure());
    }

    /** One {@code all} join over {@code b0} and {@code b1}, so a single arrival cannot satisfy it. */
    private JoinCoordinator coordinator(JoinStore store) {
        return coordinator(store, null);
    }

    /** The same join with a deadline, so termination has a scheduled timer to release. */
    private JoinCoordinator coordinatorWithTimeout(JoinStore store) {
        return coordinator(store, Duration.ofSeconds(10));
    }

    private JoinCoordinator coordinator(JoinStore store, Duration timeout) {
        var identity = new ExecutionMonitor.ExecutionIdentity(TestIdentities.TENANT_A, "join-test", "v1",
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
        var spec = new JoinSpec("join", List.of("b0", "b1"), 2, timeout);
        return new JoinCoordinator(store, engine.scheduler(), monitor, identity, Map.of("join", spec),
                java.time.Clock.systemUTC());
    }

    private static JoinArrival arrival(String branchId) {
        return new JoinArrival(branchId, "from-" + branchId, Map.of(), java.util.Set.of());
    }

    private static void awaitRecordCount(InMemoryJoinStore store, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (store.totalRecordCount() == expected) {
                return;
            }
            Thread.sleep(5);
        }
        assertTrue(false, "expected " + expected + " records, saw " + store.totalRecordCount());
    }
}
