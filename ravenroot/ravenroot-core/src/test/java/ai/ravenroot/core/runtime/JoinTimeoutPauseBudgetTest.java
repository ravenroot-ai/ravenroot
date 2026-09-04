package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A join deadline measures active execution, not wall-clock time: an operator hold stops it and a
 * resume gives it back exactly what was left.
 *
 * <h2>Why this can be asserted exactly rather than approximately</h2>
 * <p>Two things are under the test's control and neither is a timing window. The
 * {@link MutableClock} is the only source the coordinator measures a budget against, so "twelve
 * seconds of the thirty were spent" is a statement the test makes rather than one it waits for. The
 * {@link JoinTestEngine.ManualScheduler} fires only when told, and — for this file — records the
 * delay it was asked for, so "re-armed with eighteen seconds" is read back rather than inferred from
 * a firing that did or did not happen. A test that instead slept would confirm only that something
 * eventually happened, which is exactly the shape of assertion a resume that quietly reset the
 * budget would still pass.</p>
 *
 * <h2>The arrivals are not gated by the pause, and that is the point of half of this file</h2>
 * <p>{@code GraphRunner.run} is where a hop parks on a hold, and {@code dispatch} calls
 * {@code JoinCoordinator.arrive} <em>before</em> reaching it. So a branch really does arrive at a
 * join while its traversal is held, a bucket really does open there, and a join really can settle,
 * fire, or fail during a hold. Every one of those is constructed below rather than raced for: the
 * branch's own completion is a future the test completes, so the moment it arrives is the moment the
 * test chooses.</p>
 */
final class JoinTimeoutPauseBudgetTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration BUDGET = Duration.ofSeconds(30);
    private static final String BUDGET_ISO = "PT30S";
    /** Far longer than {@link #BUDGET}, so a hold that failed to stop the clock could not hide. */
    private static final Duration LONG_HOLD = Duration.ofHours(1);
    private static final Duration BOUND = Duration.ofSeconds(10);

    private final ExecutionMonitor monitor = new ExecutionMonitor();
    private final MutableClock clock = new MutableClock(EPOCH);
    private final InMemoryJoinStore joinStore = new InMemoryJoinStore();

    private JoinTestEngine engine;

    @AfterEach
    void closeEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    // ------------------------------------------------------------------ the required behaviour

    /**
     * A hold longer than the whole remaining budget does not time the join out.
     *
     * <p>The absence is not measured at an arbitrary moment. Nothing is scheduled while the hold is
     * in place, so {@code fireAll()} — which fires every task the runtime ever asked for — has
     * nothing to fire, and the assertion is over the scheduler's own books rather than over an
     * interval the test hoped was long enough. The positive control is the same fixture without the
     * hold, at the bottom of this file.</p>
     */
    @Test
    void aHoldLongerThanTheRemainingBudgetDoesNotTimeTheJoinOut() throws Exception {
        var fixture = new Fixture(2, 2);
        fixture.start();
        fixture.arrive(0);
        fixture.awaitDeadlineArmed();

        assertEquals(1, fixture.runner.liveJoinTimeoutCount(), "the join armed a deadline");
        assertTrue(fixture.runner.pauseTraversal(fixture.traversalId()), "the hold was installed");

        assertEquals(0, fixture.runner.liveJoinTimeoutCount(),
                "a held traversal must have no deadline running against it");
        assertEquals(1, fixture.runner.suspendedJoinTimeoutCount(),
                "the deadline was suspended rather than forgotten");
        assertEquals(0, fixture.engine.manualScheduler().liveCount(),
                "the scheduler must hold no live task for a held traversal");

        clock.advance(LONG_HOLD);
        assertEquals(0, fixture.engine.manualScheduler().fireAll(),
                "there was nothing left to fire, so the hold cannot have timed the join out");
        assertFalse(fixture.execution.isDone(),
                "the traversal is held, so it has neither completed nor timed out");

        fixture.close();
    }

    /**
     * After a resume the join receives the budget that remained at the pause boundary, and not a
     * second of the hold.
     */
    @Test
    void aResumeReArmsWithExactlyTheBudgetThatRemainedAtThePauseBoundary() throws Exception {
        var fixture = new Fixture(2, 2);
        fixture.start();
        fixture.arrive(0);
        fixture.awaitDeadlineArmed();

        clock.advance(Duration.ofSeconds(12));
        assertTrue(fixture.runner.pauseTraversal(fixture.traversalId()));
        clock.advance(LONG_HOLD);
        assertTrue(fixture.runner.resumeTraversal(fixture.traversalId()));

        assertEquals(List.of(BUDGET, Duration.ofSeconds(18)), fixture.delays(),
                "the resume must re-arm with the eighteen seconds that were left, not with a fresh "
                        + "thirty and not with the hour the traversal spent held");
        assertEquals(1, fixture.runner.liveJoinTimeoutCount(), "the deadline is running again");
        assertEquals(0, fixture.runner.suspendedJoinTimeoutCount(), "nothing is still suspended");

        fixture.close();
    }

    /** Repeated cycles charge the join only for the intervals it was actually running. */
    @Test
    void repeatedHoldsChargeOnlyTheIntervalsTheTraversalWasRunning() throws Exception {
        var fixture = new Fixture(2, 2);
        fixture.start();
        fixture.arrive(0);
        fixture.awaitDeadlineArmed();

        for (int cycle = 0; cycle < 3; cycle++) {
            clock.advance(Duration.ofSeconds(5));
            assertTrue(fixture.runner.pauseTraversal(fixture.traversalId()), "cycle " + cycle);
            clock.advance(LONG_HOLD);
            assertTrue(fixture.runner.resumeTraversal(fixture.traversalId()), "cycle " + cycle);
        }

        assertEquals(List.of(BUDGET, Duration.ofSeconds(25), Duration.ofSeconds(20),
                        Duration.ofSeconds(15)), fixture.delays(),
                "each cycle must subtract only the five running seconds before it, so the budget "
                        + "walks down by five and never drifts by the three hours spent held");

        fixture.close();
    }

    /**
     * A hold taken after the budget has already run out re-arms with nothing left, so the join times
     * out at the resume rather than during the hold.
     *
     * <p>This is the boundary case the {@code ZERO} clamp exists for, and it is also the one place a
     * negative delay could reach {@link ai.ravenroot.api.execution.Scheduler#schedule}, whose
     * contract does not accept one.</p>
     */
    @Test
    void aBudgetAlreadySpentAtThePauseBoundaryIsReArmedAsNothingRatherThanAsANegativeDelay()
            throws Exception {
        var fixture = new Fixture(2, 2);
        fixture.start();
        fixture.arrive(0);
        fixture.awaitDeadlineArmed();

        clock.advance(BUDGET.plusSeconds(45));
        assertTrue(fixture.runner.pauseTraversal(fixture.traversalId()));
        clock.advance(LONG_HOLD);
        assertTrue(fixture.runner.resumeTraversal(fixture.traversalId()));

        assertEquals(List.of(BUDGET, Duration.ZERO), fixture.delays(),
                "the join was owed no more budget, and a delay of minus forty-five seconds is not "
                        + "something a scheduler can be asked for");

        assertEquals(1, fixture.engine.manualScheduler().fireAll(), "the re-armed deadline fires");
        assertEquals(JoinFailureException.Reason.TIMEOUT, fixture.awaitJoinFailure().reason(),
                "a join whose budget was spent before the hold times out at the resume");
    }

    /**
     * A wall clock stepped backwards during a hold cannot hand the join more budget than its graph
     * gave it.
     */
    @Test
    void aBackwardsClockDuringAHoldCannotExtendTheBudgetPastTheConfiguredTimeout() throws Exception {
        var fixture = new Fixture(2, 2);
        fixture.start();
        fixture.arrive(0);
        fixture.awaitDeadlineArmed();

        // The remainder is measured at the pause boundary, so the step has to land between the
        // arming and the hold to be the thing under test. Ten seconds forward and five minutes back
        // leaves the clock two hundred and ninety seconds BEFORE the arming, and the arithmetic on
        // its own then says the join has three hundred and twenty seconds left of a thirty-second
        // budget.
        clock.advance(Duration.ofSeconds(10));
        clock.advance(Duration.ofMinutes(-5));
        assertTrue(fixture.runner.pauseTraversal(fixture.traversalId()));
        assertTrue(fixture.runner.resumeTraversal(fixture.traversalId()));

        assertEquals(List.of(BUDGET, BUDGET), fixture.delays(),
                "a clock stepped backwards may cost the join nothing, but it must never hand it "
                        + "more budget than the graph configured");

        fixture.close();
    }

    /**
     * A clock stepped backwards across two holds cannot restore budget the join has already spent.
     *
     * <p>The single-hold case above is bounded by the configured timeout, and for one interval that
     * is the same answer. Over a sequence it is not: this join is resumed with eighteen seconds and
     * the clock then steps back twenty before the next hold, so a ceiling of the configured thirty
     * would hand it back twelve seconds it had provably consumed — restored by a clock adjustment
     * rather than by any decision anyone took. The bound has to be what this deadline last had, not
     * what the graph first gave it.</p>
     */
    @Test
    void aBackwardsClockAcrossTwoHoldsCannotRestoreBudgetTheJoinHasAlreadySpent() throws Exception {
        var fixture = new Fixture(2, 2);
        fixture.start();
        fixture.arrive(0);
        fixture.awaitDeadlineArmed();

        clock.advance(Duration.ofSeconds(12));
        assertTrue(fixture.runner.pauseTraversal(fixture.traversalId()));
        assertTrue(fixture.runner.resumeTraversal(fixture.traversalId()));

        clock.advance(Duration.ofSeconds(-20));
        assertTrue(fixture.runner.pauseTraversal(fixture.traversalId()));
        assertTrue(fixture.runner.resumeTraversal(fixture.traversalId()));

        assertEquals(List.of(BUDGET, Duration.ofSeconds(18), Duration.ofSeconds(18)), fixture.delays(),
                "the second hold may cost the join nothing, but it must not give back the twelve "
                        + "seconds the first one had already charged");

        fixture.close();
    }

    /**
     * A branch that arrives at the join while the traversal is held opens its bucket and records a
     * budget, but arms nothing.
     *
     * <p>The interleaving is constructed, not raced. The branch's behaviour parks on a future the
     * test owns, so the hold is installed while the branch is provably inside the node and the
     * arrival provably happens afterwards. That is the only way a join can be first reached during a
     * hold, and it is reachable precisely because {@code dispatch} arrives at a join before
     * {@code run} would have parked the hop on the gate.</p>
     */
    @Test
    void aJoinFirstReachedWhileHeldArmsNothingAndIsGivenItsFullBudgetOnResume() throws Exception {
        var fixture = new Fixture(2, 2);
        fixture.start();
        fixture.awaitInsideBranch(0);

        assertTrue(fixture.runner.pauseTraversal(fixture.traversalId()), "held before b0 returns");
        assertEquals(0, fixture.runner.suspendedJoinTimeoutCount(), "no join has been reached yet");

        fixture.arrive(0);
        fixture.awaitSuspendedDeadline();

        assertEquals(0, fixture.runner.liveJoinTimeoutCount(),
                "a join first reached during a hold must not arm a live deadline");
        assertEquals(List.of(), fixture.delays(),
                "the scheduler must not have been asked for anything at all");

        assertTrue(fixture.runner.resumeTraversal(fixture.traversalId()));
        assertEquals(List.of(BUDGET), fixture.delays(),
                "this bucket never ran, so the resume owes it the whole configured budget");

        fixture.close();
    }

    /**
     * A hold taken before a human-task re-entry holds the join that re-entry dispatches into.
     *
     * <h2>Why this path can arm a deadline during a hold and the ordinary one cannot</h2>
     * <p>{@code GraphRunner.run} is where a hop parks on a hold, and it guards a hop that is about to
     * <em>start a node</em>. A branch entering a fan-in never starts one — {@code dispatch} hands it
     * to {@code JoinCoordinator.arrive} instead — so a fan-in is reached without passing the gate.
     * On an ordinary submission that costs nothing, because the traversal's first hop is the start
     * node's own dispatch and it does park. {@code executeAfterHumanTask} has no first hop to park:
     * it synthesises the re-entered node's completion and calls {@code dispatchSuccessors}
     * directly. So when that node's successor is a timed join, the re-entry arms a deadline
     * immediately, and the only thing that can stop it is the coordinator having been told, as it was
     * created, that its traversal is already held.</p>
     *
     * <h2>What is constructed, and what would be left to chance</h2>
     * <p>The hold is installed <em>before</em> {@code executeAfterHumanTask} is called, so it precedes
     * the coordinator's own existence: {@code pauseTraversal} finds no coordinator and suspends
     * nothing, which makes {@code beginPublishing} the only site that can mark this one held. The
     * traversal is left provably frozen — one branch arrives at a quorum of two, the other is never
     * dispatched at all because the re-entry starts below the fan-out — so the join is open, is owed
     * a deadline, and cannot settle while the assertion is taken.</p>
     *
     * <p>The wait is for the join to be <em>reached</em>, counting live and suspended together, and
     * the split is asserted afterwards. Waiting for the suspended count directly would turn a
     * regression into a timeout whose message names the wait rather than the defect; this way a
     * runtime that armed the deadline fails saying so.</p>
     */
    @Test
    void aHoldTakenBeforeAHumanTaskReEntryHoldsTheJoinItReEntersInto() throws Exception {
        engine = new JoinTestEngine();
        var registry = new BehaviorRegistry();
        registry.register("effect", message -> CompletableFuture.completedFuture(
                NodeResult.continueWith("unused: the re-entry synthesises this node's completion")));
        registry.register("other", message -> new CompletableFuture<>());

        var key = new ai.ravenroot.api.persistence.ExecutionKey("tenant-a", UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();

        try (var store = new ai.ravenroot.core.persistence.InMemoryExecutionStore();
             var manager = GraphManager.from(reEntryIntoTimedJoin())) {
            // Deliberately NOT closed, and this is the one test in this file that leaves a runner
            // open. Closing it here deadlocks the test thread, on a defect that predates this change
            // and is not part of it: GraphRunner#close terminates the coordinator, which completes
            // the branch parked at this join exceptionally, and that completion runs synchronously
            // into executeAfterHumanTask's terminal handler -- which blocks on
            // release(...).toCompletableFuture().join() while the first terminate() is still on the
            // stack below it. JoinCoordinator's `termination` javadoc names exactly this: "a consumer
            // that BLOCKS on this stage from inside a parked branch's continuation would deadlock
            // itself." #execute composes its cleanup into the returned stage and is unaffected; the
            // three re-entry paths block. Reported separately rather than fixed here, because fixing
            // it changes the teardown contract of three entry paths and belongs to its own change.
            //
            // What is leaked is heap the JVM reclaims: one coordinator, one parked future and this
            // traversal's admission entries. The engine's threads are closed by @AfterEach, and the
            // manual scheduler owns none.
            //
            // TO UNDO, ONCE THAT DEADLOCK IS FIXED -- and it must be undone, because an exception
            // like this one outlives the reason for it unless the reason is written down as an
            // instruction rather than as an explanation:
            //   1. put `runner` back into the try-with-resources above, beside `store` and `manager`;
            //   2. delete this entire comment.
            // Nothing else here changes. This test then becomes the regression test for the fix: it
            // is the only one in the suite that closes a runner while a branch is parked at a join on
            // a re-entry path, which is exactly the state that deadlocks today.
            var runner = new GraphRunner(manager, engine, registry, monitor,
                    ExecutionIdentitySource.randomUuids(), joinStore, clock);

            long revision = acceptReentryAtEffect(store, key, traversalId);
            try (var recorder = ExecutionRecorder.open(store, key, "human-task-join-hold",
                    Duration.ofSeconds(30), revision)) {

                assertTrue(runner.pauseTraversal(traversalId),
                        "the hold is taken before the coordinator exists, so nothing is suspended yet");
                assertEquals(0, runner.suspendedJoinTimeoutCount(),
                        "and there is nothing to suspend: no join has been reached on this runner");

                runner.executeAfterHumanTask(TestIdentities.TENANT_A, key.processInstanceId(),
                        traversalId, "effect", "v1", recorder, NodeResult.continueWith("resumed"));

                long deadline = System.nanoTime() + BOUND.toNanos();
                while (System.nanoTime() < deadline
                        && runner.liveJoinTimeoutCount() + runner.suspendedJoinTimeoutCount() == 0) {
                    Thread.sleep(2);
                }

                assertTrue(runner.isPaused(traversalId),
                        "the traversal is still held: nothing has released the hold");
                assertEquals(0, runner.liveJoinTimeoutCount(),
                        "a re-entry that dispatches into a fan-in must not arm a live deadline "
                                + "against a traversal an operator has already frozen");
                assertEquals(1, runner.suspendedJoinTimeoutCount(),
                        "the join was reached and its budget recorded rather than started");
                assertEquals(List.of(), engine.manualScheduler().requestedDelays(),
                        "and the scheduler was never asked for anything");

                assertTrue(runner.resumeTraversal(traversalId), "the hold is releasable");
                assertEquals(List.of(BUDGET), engine.manualScheduler().requestedDelays(),
                        "the resume owes this bucket its whole budget: it never ran");
            }
        }
    }

    /**
     * {@code start} fans out to {@code effect} and {@code other}, both feeding a timed fan-in.
     *
     * <p>A re-entry at {@code effect} dispatches into the join with {@code other} never having run,
     * so the join opens on one arrival of a quorum of two and stays open for the whole test.</p>
     */
    private static ai.ravenroot.core.graph.GraphDefinition reEntryIntoTimedJoin() {
        return new ai.ravenroot.core.graph.GraphDefinition(List.of(
                ai.ravenroot.core.graph.GraphNode.start("start"),
                ai.ravenroot.core.graph.GraphNode.behavior("effect", "effect"),
                ai.ravenroot.core.graph.GraphNode.behavior("other", "other"),
                new ai.ravenroot.core.graph.GraphNode("join",
                        ai.ravenroot.core.graph.NodeKind.PASSTHROUGH, null,
                        JoinMiniGraphs.quorumWithTimeout(2, BUDGET_ISO)),
                ai.ravenroot.core.graph.GraphNode.error("error"),
                ai.ravenroot.core.graph.GraphNode.end("end")), List.of(
                ai.ravenroot.core.graph.GraphEdge.to("start", "effect"),
                ai.ravenroot.core.graph.GraphEdge.to("start", "other"),
                ai.ravenroot.core.graph.GraphEdge.to("effect", "join"),
                ai.ravenroot.core.graph.GraphEdge.to("other", "join"),
                ai.ravenroot.core.graph.GraphEdge.to("join", "end")));
    }

    /** Stores a traversal accepted at {@code effect}, which is the state a re-entry resumes from. */
    private static long acceptReentryAtEffect(ai.ravenroot.api.persistence.ExecutionStore store,
                                              ai.ravenroot.api.persistence.ExecutionKey key,
                                              UUID traversalId) {
        var traversal = new ai.ravenroot.api.application.Traversal(traversalId, "effect",
                ai.ravenroot.api.application.TraversalStatus.ACCEPTED, java.util.Map.of());
        var created = store.apply(ai.ravenroot.api.persistence.ExecutionBatch.to(key)
                .expecting(ai.ravenroot.api.persistence.RevisionExpectation.notPresent())
                .apply(new ai.ravenroot.api.persistence.ExecutionTransition.ProcessCreated(
                        new ai.ravenroot.api.application.ProcessInstance(key.processInstanceId(),
                                ai.ravenroot.api.application.ProcessInstanceStatus.ACCEPTED,
                                java.util.Map.of(traversalId, traversal)),
                        new ai.ravenroot.api.persistence.GraphVersionPin("v1")))
                .build()).toCompletableFuture().join();
        return store.apply(ai.ravenroot.api.persistence.ExecutionBatch.to(key)
                .expecting(ai.ravenroot.api.persistence.RevisionExpectation.exactly(created.revision()))
                .apply(new ai.ravenroot.api.persistence.ExecutionTransition.ProcessTransitioned(
                        ai.ravenroot.api.application.ProcessInstanceStatus.RUNNING))
                .build()).toCompletableFuture().join().revision();
    }

    // ------------------------------------------------------------------ the races, constructed

    /**
     * A deadline that could not be cancelled when the hold landed is refused when it fires.
     *
     * <p>The interleaving is constructed by refusing cancellation outright: the scheduler's
     * cancellation mutant leaves every task live, so the task the hold tried to cancel is still
     * there to be fired afterwards. Firing it is then the exact instruction that would run against a
     * traversal an operator has frozen, and it must be refused by the arming's generation rather
     * than by the cancellation that did not happen.</p>
     *
     * <p>Without that refusal the cancellation is the only thing standing between a hold and a
     * timed-out join, and a cancellation is precisely what a real scheduler is allowed to fail at
     * for a task that has already begun to run.</p>
     */
    @Test
    void aDeadlineThatOutlivedItsCancellationIsRefusedWhenItFiresDuringAHold() throws Exception {
        var fixture = new Fixture(2, 2);
        fixture.start();
        fixture.arrive(0);
        fixture.awaitDeadlineArmed();

        fixture.engine.manualScheduler().refuseCancellation();
        assertTrue(fixture.runner.pauseTraversal(fixture.traversalId()));

        assertEquals(1, fixture.engine.manualScheduler().fireAll(),
                "the refused cancellation left the task live, which is the state under test");
        assertFalse(awaitDone(fixture.execution),
                "the firing belonged to an arming the hold superseded, so it must settle nothing");
        assertFalse(fixture.execution.isCompletedExceptionally(),
                "a held traversal must not be failed by a deadline it no longer owns");

        fixture.close();
    }

    /**
     * A join that settles while the traversal is held leaves no budget for the resume to re-arm.
     *
     * <p>Constructed rather than raced: arrivals are not gated by a hold, so completing the second
     * branch's future while held is a settlement that provably happens inside the hold. The hop the
     * firing releases then parks on the gate, which is how the traversal stays held afterwards.</p>
     */
    @Test
    void aJoinSatisfiedDuringAHoldIsNotReArmedByTheResume() throws Exception {
        var fixture = new Fixture(2, 2);
        fixture.start();
        fixture.arrive(0);
        fixture.awaitDeadlineArmed();

        assertTrue(fixture.runner.pauseTraversal(fixture.traversalId()));
        fixture.arrive(1);
        fixture.awaitNoSuspendedDeadline();

        assertTrue(fixture.runner.resumeTraversal(fixture.traversalId()));
        assertEquals(List.of(BUDGET), fixture.delays(),
                "the bucket fired during the hold, so its budget died with it and the resume must "
                        + "not hand a settled join a second deadline");
        assertEquals(0, fixture.engine.manualScheduler().fireAll(),
                "and no task may be left behind for it either");
        assertNotNull(fixture.execution.get(BOUND.toSeconds(), TimeUnit.SECONDS),
                "the traversal completed through the join that fired during the hold");
        assertEquals(0, fixture.runner.liveJoinTimeoutCount(), "no deadline outlived the traversal");
        assertEquals(0, fixture.runner.suspendedJoinTimeoutCount(), "and no budget did either");
    }

    /**
     * A cancellation while the traversal is held re-arms nothing and leaves no scheduled task.
     *
     * <p>A cancellation releases the same gate a resume does, so "release the gate" is not on its own
     * a licence to re-arm. If it were, a traversal on its way to a terminal state would acquire a
     * fresh deadline on the way out, and the join it guarded would be settled twice.</p>
     */
    @Test
    void aCancellationWhileHeldReArmsNothingAndLeavesNoScheduledTask() throws Exception {
        var fixture = new Fixture(2, 2);
        fixture.start();
        fixture.arrive(0);
        fixture.awaitDeadlineArmed();

        assertTrue(fixture.runner.pauseTraversal(fixture.traversalId()));
        assertTrue(fixture.runner.cancelTraversal(fixture.traversalId()));

        // Read the instant the gate is released and before anything unwinds. This is where a rule
        // that re-armed on every gate release, rather than only on a resume, would have asked the
        // scheduler for a second deadline -- and it would be a deadline against a traversal that is
        // already on its way to a terminal state.
        assertEquals(List.of(BUDGET), fixture.delays(),
                "a cancellation must not ask the scheduler for anything");

        // The outstanding branch is what the traversal's stage is still waiting on; releasing it is
        // what lets the teardown run, and the cancellation is what it then meets.
        fixture.arrive(1);
        // Awaited for its terminal state rather than for a particular outcome: whether the
        // cancellation surfaces as a failure or is absorbed by the graph's own error route is a
        // property of the fixture's topology, and pinning it here would make this test fail for a
        // reason that has nothing to do with deadlines.
        fixture.awaitTerminal();
        assertEquals(List.of(BUDGET), fixture.delays(),
                "and the teardown must not ask for one either");
        assertEquals(0, fixture.engine.manualScheduler().liveCount(),
                "no scheduled task remains once the traversal has reached a terminal state");
        assertEquals(0, fixture.runner.liveJoinTimeoutCount());
        assertEquals(0, fixture.runner.suspendedJoinTimeoutCount());
    }

    /**
     * A join proven unable to meet its quorum during a hold is not re-armed by the resume.
     *
     * <p>The third way a deadline can end while held, after a firing and a cancellation: the join
     * itself reaches a terminal verdict. A branch that fails is what makes a quorum of two out of two
     * unreachable, and it settles the join {@code FAILED} without the traversal being cancelled.</p>
     */
    @Test
    void aJoinThatFailsDuringAHoldIsNotReArmedByTheResume() throws Exception {
        var fixture = new Fixture(2, 2);
        fixture.start();
        fixture.arrive(0);
        fixture.awaitDeadlineArmed();

        assertTrue(fixture.runner.pauseTraversal(fixture.traversalId()));
        fixture.fail(1, new IllegalStateException("b1 refused"));

        var error = assertThrows(ExecutionException.class,
                () -> fixture.execution.get(BOUND.toSeconds(), TimeUnit.SECONDS));
        assertEquals(JoinFailureException.Reason.QUORUM_UNREACHABLE, joinFailure(error).reason(),
                "the join failed on its own verdict rather than on a deadline");
        assertEquals(List.of(BUDGET), fixture.delays(),
                "a join that gave its deadline up for good must not acquire another on the way out");
        assertEquals(0, fixture.engine.manualScheduler().liveCount(),
                "and must leave nothing at the scheduler");
        assertEquals(0, fixture.runner.liveJoinTimeoutCount());
        assertEquals(0, fixture.runner.suspendedJoinTimeoutCount());
    }

    /**
     * A resume that arrives after the coordinator has been terminated re-arms nothing.
     *
     * <p>Asserted against the coordinator directly, because the shape it excludes is not one a graph
     * can be made to produce: every path that terminates a traversal also drops its hold, so a
     * resume can never reach a runner-level terminated coordinator. It is the backstop for the rule
     * that giving a deadline up for good gives its budget up with it, and it is the only place that
     * rule can be shown to have teeth rather than being taken on trust.</p>
     */
    @Test
    void aResumeAfterTerminationReArmsNothing() throws Exception {
        engine = new JoinTestEngine();
        var coordinator = coordinator(new JoinSpec("join", List.of("b0", "b1"), 2, BUDGET));

        coordinator.arrive("join", new JoinArrival("b0", "p0", java.util.Map.of(), java.util.Set.of()));
        assertEquals(1, coordinator.liveTimeoutCount(), "the opening arrival armed a deadline");

        coordinator.suspendTimeouts();
        assertEquals(1, coordinator.suspendedTimeoutCount(), "and the hold suspended it");

        coordinator.terminate().toCompletableFuture().get(BOUND.toSeconds(), TimeUnit.SECONDS);
        assertEquals(0, coordinator.suspendedTimeoutCount(),
                "termination gives the budget up along with the deadline it belonged to");

        coordinator.resumeTimeouts();
        assertEquals(List.of(BUDGET), engine.manualScheduler().requestedDelays(),
                "a resume must not hand a terminated join a fresh deadline");
        assertEquals(0, coordinator.liveTimeoutCount());
        assertEquals(0, engine.manualScheduler().liveCount());
    }

    /**
     * Shutting the runner down while a traversal is held leaves nothing scheduled and nothing
     * suspended.
     */
    @Test
    void aShutdownWhileHeldLeavesNeitherAScheduledTaskNorASuspendedBudget() throws Exception {
        var fixture = new Fixture(2, 2);
        fixture.start();
        fixture.arrive(0);
        fixture.awaitDeadlineArmed();
        assertTrue(fixture.runner.pauseTraversal(fixture.traversalId()));

        fixture.runner.close();

        assertEquals(0, fixture.engine.manualScheduler().liveCount(),
                "the teardown must not leave a task at the scheduler");
        assertEquals(List.of(BUDGET), fixture.delays(),
                "and must not have asked for a new one on the way out");
    }

    /**
     * A join that reaches a terminal verdict during a hold gives its budget up with its deadline.
     *
     * <p>Asserted against the coordinator rather than through a graph, because the observation has to
     * be taken while the join still exists: at the runner level the traversal fails on this verdict
     * and its coordinator is released, so "no budget remains" and "no coordinator remains" become one
     * zero with two causes. Here the coordinator outlives the verdict and can be asked directly.</p>
     */
    @Test
    void aJoinThatFailsDuringAHoldGivesUpItsBudgetWithItsDeadline() throws Exception {
        engine = new JoinTestEngine();
        var coordinator = coordinator(new JoinSpec("join", List.of("b0", "b1"), 2, BUDGET));

        coordinator.arrive("join", new JoinArrival("b0", "p0", java.util.Map.of(), java.util.Set.of()));
        coordinator.suspendTimeouts();
        assertEquals(1, coordinator.suspendedTimeoutCount(), "the hold suspended the deadline");

        // Quorum of two out of two can no longer be met once b1 is proven dead, so the join reaches a
        // terminal verdict of its own without the traversal being cancelled.
        coordinator.notTaken("join", "b1").toCompletableFuture().get(BOUND.toSeconds(), TimeUnit.SECONDS);

        assertEquals(0, coordinator.suspendedTimeoutCount(),
                "a join that has given its deadline up for good is owed no budget");
        coordinator.resumeTimeouts();
        assertEquals(List.of(BUDGET), engine.manualScheduler().requestedDelays(),
                "and a resume must not hand a settled join a deadline it can only fail with");
        assertEquals(0, coordinator.liveTimeoutCount());
    }

    /**
     * Suspending an already-suspended deadline charges it nothing.
     *
     * <p>The two callers of the suspension — the sweep an operator's hold runs, and the publish step
     * of an arming that hold overtook — can run in either order, so it has to be idempotent in value
     * and not only in effect. If a second suspension charged the join for the interval it spent
     * suspended, the two orderings would produce different budgets for the same hold, and which one a
     * traversal got would be decided by a race.</p>
     */
    @Test
    void suspendingAnAlreadySuspendedDeadlineChargesItNothing() throws Exception {
        engine = new JoinTestEngine();
        var coordinator = coordinator(new JoinSpec("join", List.of("b0", "b1"), 2, BUDGET));

        coordinator.arrive("join", new JoinArrival("b0", "p0", java.util.Map.of(), java.util.Set.of()));
        clock.advance(Duration.ofSeconds(4));
        coordinator.suspendTimeouts();

        clock.advance(LONG_HOLD);
        coordinator.suspendTimeouts();

        coordinator.resumeTimeouts();
        assertEquals(List.of(BUDGET, Duration.ofSeconds(26)), engine.manualScheduler().requestedDelays(),
                "the second suspension must record the same twenty-six seconds as the first, not "
                        + "twenty-six minus the hour the join spent held");
    }

    private JoinCoordinator coordinator(JoinSpec spec) {
        var identity = new ExecutionMonitor.ExecutionIdentity(TestIdentities.TENANT_A, "join-test", "v1",
                UUID.randomUUID(), UUID.randomUUID());
        return new JoinCoordinator(joinStore, engine.scheduler(), monitor, identity,
                java.util.Map.of("join", spec), clock);
    }

    // ------------------------------------------------------------------ positive control

    /**
     * The same fixture without a hold really does time out, so every absence above is an absence
     * over an instrument that is known to report.
     */
    @Test
    void withoutAHoldTheSameJoinTimesOut() throws Exception {
        var fixture = new Fixture(2, 2);
        fixture.start();
        fixture.arrive(0);
        fixture.awaitDeadlineArmed();

        assertEquals(1, fixture.engine.manualScheduler().fireAll(), "the deadline was live");
        assertEquals(JoinFailureException.Reason.TIMEOUT, fixture.awaitJoinFailure().reason());
    }

    // ------------------------------------------------------------------ fixture

    private static boolean awaitDone(CompletableFuture<?> execution) throws InterruptedException {
        // A bounded wait for a completion that must NOT arrive: short enough not to dominate the
        // suite, and paired with the positive control above, which fails if the same wait would have
        // missed a completion that did arrive.
        try {
            execution.get(500, TimeUnit.MILLISECONDS);
            return true;
        } catch (ExecutionException failed) {
            return true;
        } catch (java.util.concurrent.TimeoutException stillRunning) {
            return false;
        }
    }

    private static JoinFailureException joinFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof JoinFailureException failure) {
                return failure;
            }
            current = current.getCause();
        }
        throw new AssertionError("expected a JoinFailureException, got " + error, error);
    }

    /**
     * One fan-in traversal whose branches return when the test says so.
     *
     * <p>Every branch's behaviour hands back a future the test holds, which is what makes each
     * arrival an instruction rather than a race. The engine is the real pooled one, so the branches
     * are genuinely concurrent with each other and with the control calls.</p>
     */
    private final class Fixture implements AutoCloseable {

        private final GraphManager manager;
        private final GraphRunner runner;
        private final JoinTestEngine engine;
        private final List<CompletableFuture<NodeResult>> branches;
        private final List<CountDownLatch> entered;
        private final AtomicReference<UUID> traversal = new AtomicReference<>();
        private final CountDownLatch started = new CountDownLatch(1);
        private final AutoCloseable subscription;

        private CompletableFuture<GraphExecutionResult> execution;

        private Fixture(int branchCount, int quorum) {
            JoinTimeoutPauseBudgetTest.this.engine = new JoinTestEngine();
            this.engine = JoinTimeoutPauseBudgetTest.this.engine;
            this.branches = new java.util.ArrayList<>();
            this.entered = new java.util.ArrayList<>();
            var registry = new BehaviorRegistry();
            for (int index = 0; index < branchCount; index++) {
                var gate = new CompletableFuture<NodeResult>();
                var arrival = new CountDownLatch(1);
                branches.add(gate);
                entered.add(arrival);
                registry.register("b" + index, message -> {
                    arrival.countDown();
                    return gate;
                });
            }
            this.manager = GraphManager.from(JoinMiniGraphs.fanIn(branchCount,
                    JoinMiniGraphs.quorumWithTimeout(quorum, BUDGET_ISO)));
            this.subscription = monitor.subscribe(event -> {
                if (event.type() == ExecutionEventType.EXECUTION_STARTED) {
                    traversal.set(event.traversalId());
                    started.countDown();
                }
            });
            this.runner = new GraphRunner(manager, engine, registry, monitor,
                    ExecutionIdentitySource.randomUuids(), joinStore, clock);
        }

        private void start() throws InterruptedException {
            execution = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture();
            assertTrue(started.await(BOUND.toSeconds(), TimeUnit.SECONDS), "the traversal started");
        }

        private UUID traversalId() {
            return traversal.get();
        }

        /** Blocks until branch {@code index} is provably inside its node and has not returned. */
        private void awaitInsideBranch(int index) throws InterruptedException {
            assertTrue(entered.get(index).await(BOUND.toSeconds(), TimeUnit.SECONDS),
                    "b" + index + " never entered its node");
        }

        private void arrive(int index) {
            branches.get(index).complete(NodeResult.continueWith("from-b" + index));
        }

        private void fail(int index, Throwable cause) {
            branches.get(index).completeExceptionally(cause);
        }

        private List<Duration> delays() {
            return engine.manualScheduler().requestedDelays();
        }

        private void awaitDeadlineArmed() throws InterruptedException {
            await(() -> runner.liveJoinTimeoutCount() == 1, "a deadline to be armed");
        }

        private void awaitSuspendedDeadline() throws InterruptedException {
            await(() -> runner.suspendedJoinTimeoutCount() == 1, "a deadline to be suspended");
        }

        private void awaitNoSuspendedDeadline() throws InterruptedException {
            await(() -> runner.suspendedJoinTimeoutCount() == 0, "the suspended budget to be given up");
        }

        /** Blocks until the traversal has reached a terminal state, whatever that state is. */
        private void awaitTerminal() throws InterruptedException {
            try {
                execution.get(BOUND.toSeconds(), TimeUnit.SECONDS);
            } catch (ExecutionException | java.util.concurrent.TimeoutException settled) {
                assertTrue(execution.isDone(), "the traversal never reached a terminal state");
            }
        }

        private JoinFailureException awaitJoinFailure() {
            var error = assertThrows(ExecutionException.class,
                    () -> execution.get(BOUND.toSeconds(), TimeUnit.SECONDS));
            return joinFailure(error);
        }

        /**
         * A bounded wait for a state the runtime reaches on a thread of its own.
         *
         * <p>Not a race the test is hoping to win: the state is reached by work the test has already
         * instructed, and the bound only covers the handoff to the engine's pool. Every assertion
         * that follows one of these is made after the state is observed, never after a fixed sleep.
         */
        private void await(java.util.function.BooleanSupplier condition, String what)
                throws InterruptedException {
            long deadline = System.nanoTime() + BOUND.toNanos();
            while (System.nanoTime() < deadline) {
                if (condition.getAsBoolean()) {
                    return;
                }
                Thread.sleep(2);
            }
            assertTrue(false, "timed out waiting for " + what);
        }

        @Override
        public void close() throws Exception {
            runner.close();
            subscription.close();
            manager.close();
        }
    }
}
