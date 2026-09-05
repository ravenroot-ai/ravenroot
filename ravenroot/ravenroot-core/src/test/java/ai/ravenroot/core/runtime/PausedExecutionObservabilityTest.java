package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.LiveExecution;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pause is observable, and the transitions an observer is shown are the ones that happened.
 *
 * <h2>What this file is about, and why it is not the pause tests that already exist</h2>
 * <p>{@link PauseInStartupWindowTest} establishes that a pause <em>holds</em>, and
 * {@link PauseGateReleaseThreadTest} that releasing one does not steal the caller's thread. Neither
 * asks the question this file asks: what does somebody who did not issue the pause get to see, and
 * can they be shown a sequence that never occurred.</p>
 *
 * <p>Four paths remove a hold and only one of them is a resume. The other three — cancellation, the
 * traversal's own completion, and the runner's shutdown — end the traversal, so publishing
 * {@code EXECUTION_RESUMED} on any of them would tell a reader the execution went back to running
 * immediately before it stopped forever. At the other end, a hold can be installed before the
 * runtime has an identity to publish under, and again after the traversal has begun to close, and
 * both of those would place {@code EXECUTION_PAUSED} outside the traversal's own lifetime. Each
 * test below is one of those interleavings.</p>
 *
 * <h2>Determinism comes from the engine and from the listener, never from a sleep</h2>
 * <p>{@link SameThreadExecutionEngine} makes the submitting thread the only thread that can carry a
 * traversal forward, so a hop parked at a gate is a fact rather than a timing window. Two of the
 * races below are entered from inside a synchronous {@link ExecutionMonitor} listener, which is the
 * one place a test can stand at an exact point in the runtime's own sequence: the listener for a
 * terminal event runs while the runtime is publishing it, which is <em>after</em> the traversal has
 * begun to close. That is the window, entered rather than raced for.</p>
 *
 * <p>Every assertion about an absence is either an absence over a bound with a positive control
 * beside it, or a read of the recorded event sequence after a terminal event has been observed —
 * never an absence measured at an arbitrary moment.</p>
 */
final class PausedExecutionObservabilityTest {

    private static final Duration BOUND = Duration.ofSeconds(30);
    private static final Duration HELD_BOUND = Duration.ofSeconds(2);

    /**
     * The traversal-level lifecycle types, in the order a reader would see them.
     *
     * <p>Assertions below are made over this projection rather than over the whole event sequence,
     * and the reason is about what is being pinned. The claim under test is an ordering between
     * traversal-level transitions — a hold cannot precede the start, a release cannot precede the
     * hold, neither may follow a terminal. Interleaving that claim with the node and edge events of
     * whatever graph the fixture happens to use would pin the fixture's shape as well, so a later
     * change to the test graph would fail these tests for a reason that has nothing to do with
     * pausing. The node events are asserted where they are the subject, by counting effects inside
     * the behaviour.</p>
     *
     * @param events every event recorded for one traversal
     * @return the {@code EXECUTION_*} subsequence, order preserved
     */
    private static List<ExecutionEventType> lifecycle(List<ExecutionEventType> events) {
        synchronized (events) {
            return events.stream().filter(type -> type == ExecutionEventType.EXECUTION_STARTED
                    || type == ExecutionEventType.EXECUTION_PAUSED
                    || type == ExecutionEventType.EXECUTION_RESUMED
                    || type == ExecutionEventType.EXECUTION_COMPLETED
                    || type == ExecutionEventType.EXECUTION_FAILED
                    // A cancellation is a traversal-terminal event of its own now, so a filter that
                    // omitted it would drop the very event the cancel test asserts on and report the
                    // sequence as if the traversal had never ended.
                    || type == ExecutionEventType.EXECUTION_CANCELLED).toList();
        }
    }

    /** One behaviour node between start and end, so a run that executes has something to count. */
    private static final String ONE_EFFECT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="node-behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="one-effect" edgedefault="directed">
                <node id="start"><data key="node-kind">START</data></node>
                <node id="effect">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">record-effect</data>
                </node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="e1" source="start" target="effect"><data key="edge-outcome">continue</data></edge>
                <edge id="e2" source="effect" target="end"><data key="edge-outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    /**
     * A pause that arrives while the traversal is already ending is refused, and nothing is
     * published for it.
     *
     * <h2>The window and how it is entered</h2>
     * <p>The runtime seals a traversal before it publishes that traversal's terminal event: the
     * completion path calls {@code ExecutionState.beginClosing} and its runner counterpart, then
     * releases the pause gate, and only afterwards publishes {@code EXECUTION_COMPLETED}. Between
     * the seal and the event there is a real interval, and the gate has already been dropped inside
     * it — so before this refusal existed a pause landing there would install a fresh hold, publish
     * {@code EXECUTION_PAUSED}, and be followed by the completion. "Paused, then completed" is a
     * sequence no execution can perform.</p>
     *
     * <p>A monitor listener is what stands in that interval exactly. Listener delivery is synchronous
     * on the publishing thread, so a listener for {@code EXECUTION_COMPLETED} runs while the runtime
     * is inside the publication — strictly after the seal. The pause is issued from there.</p>
     *
     * <h2>The control is the point</h2>
     * <p>A refusal that is always a refusal proves nothing, so the same runner is asked to pause the
     * same traversal <em>before</em> it runs, and must accept and publish. The two calls differ only
     * in when they arrive.</p>
     */
    @Test
    void aPauseArrivingOnceTheTraversalHasBegunToEndIsRefusedAndPublishesNothing() throws Exception {
        var monitor = new ExecutionMonitor();
        var effects = new AtomicInteger();
        var registry = new BehaviorRegistry().register("record-effect", message -> {
            effects.incrementAndGet();
            return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        });

        UUID heldId = UUID.randomUUID();
        UUID racingId = UUID.randomUUID();
        List<ExecutionEventType> heldEvents = Collections.synchronizedList(new ArrayList<>());
        List<ExecutionEventType> racingEvents = Collections.synchronizedList(new ArrayList<>());
        var pausedDuringClose = new AtomicBoolean(true);
        var heldDuringClose = new AtomicBoolean(true);

        try (var engine = new SameThreadExecutionEngine();
             var document = GraphManager.readGraphMlDocument(
                     new ByteArrayInputStream(ONE_EFFECT.getBytes(StandardCharsets.UTF_8)));
             var runner = new GraphRunner(document.manager(), engine, registry, monitor)) {

            try (AutoCloseable subscription = monitor.subscribe(event -> {
                if (heldId.equals(event.traversalId())) {
                    heldEvents.add(event.type());
                }
                if (!racingId.equals(event.traversalId())) {
                    return;
                }
                racingEvents.add(event.type());
                if (event.type() == ExecutionEventType.EXECUTION_COMPLETED) {
                    // Inside the terminal publication, so strictly after the traversal was sealed.
                    // Both reads are taken here rather than afterwards: once this returns, the
                    // completion stage settles and the moment under test is gone.
                    pausedDuringClose.set(runner.pauseTraversal(racingId));
                    heldDuringClose.set(runner.isPaused(racingId));
                }
            })) {

                // Control: the same call, on the same runner, before the traversal begins.
                assertTrue(runner.pauseTraversal(heldId),
                        "a pause on a traversal that has not begun to end must be accepted");
                var held = runner.execute(TestIdentities.TENANT_A, heldId, "payload", "v1")
                        .toCompletableFuture();
                assertFalse(held.isDone(), "the held traversal must be parked at its gate");
                assertEquals(0, effects.get(), "a held traversal must not have run its node");
                assertEquals(List.of(ExecutionEventType.EXECUTION_STARTED, ExecutionEventType.EXECUTION_PAUSED),
                        lifecycle(heldEvents),
                        "a hold accepted before the traversal starts is announced after the start "
                                + "event, never before it");

                // Subject: a clean traversal, paused from inside its own completion.
                runner.execute(TestIdentities.TENANT_A, racingId, "payload", "v1")
                        .toCompletableFuture().get(BOUND.toSeconds(), TimeUnit.SECONDS);

                assertFalse(pausedDuringClose.get(),
                        "a traversal that has begun to end must refuse a new hold, or EXECUTION_PAUSED "
                                + "would be published after its terminal event");
                assertFalse(heldDuringClose.get(),
                        "and no hold may be reported over a traversal that is completing");
                assertEquals(List.of(ExecutionEventType.EXECUTION_STARTED,
                                ExecutionEventType.EXECUTION_COMPLETED),
                        lifecycle(racingEvents),
                        "a clean execution keeps exactly its existing running and terminal "
                                + "representation: no pause, no resume, nothing added");
                assertTrue(racingEvents.contains(ExecutionEventType.NODE_COMPLETED),
                        "and its node events are unchanged: " + racingEvents);

                // Leave nothing parked for the runner's close to have to explain.
                assertTrue(runner.resumeTraversal(heldId), "the control hold must still be releasable");
                held.get(BOUND.toSeconds(), TimeUnit.SECONDS);
                assertEquals(2, effects.get(), "both traversals must have run their node exactly once");
            }
        }
    }

    /**
     * Cancelling a held traversal releases the hold and publishes no resume.
     *
     * <p>The cancellation has to release the gate — that is what makes cancel reach a paused
     * execution at all, and {@code TraversalPauseResumeTest} already pins it. What is pinned here is
     * what the release is allowed to say: the traversal is being ended, not continued, so an
     * {@code EXECUTION_RESUMED} on this path would report a transition that did not occur, and
     * anything counting resumptions would count cancellations among them.</p>
     *
     * <p>Non-vacuous by construction: the hold is announced first — the assertion on
     * {@code EXECUTION_PAUSED} proves the stream was live and the hold was visible — so the absence
     * of the resume afterwards is an absence in a sequence that was demonstrably being recorded.</p>
     */
    @Test
    void cancellingAHeldTraversalReleasesItWithoutPublishingAResume() throws Exception {
        var monitor = new ExecutionMonitor();
        var effects = new AtomicInteger();
        var registry = new BehaviorRegistry().register("record-effect", message -> {
            effects.incrementAndGet();
            return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        });

        UUID traversalId = UUID.randomUUID();
        List<ExecutionEventType> events = Collections.synchronizedList(new ArrayList<>());
        var terminal = new CountDownLatch(1);

        try (var engine = new SameThreadExecutionEngine();
             var document = GraphManager.readGraphMlDocument(
                     new ByteArrayInputStream(ONE_EFFECT.getBytes(StandardCharsets.UTF_8)));
             var runner = new GraphRunner(document.manager(), engine, registry, monitor);
             AutoCloseable subscription = monitor.subscribe(event -> {
                 if (!traversalId.equals(event.traversalId())) {
                     return;
                 }
                 events.add(event.type());
                 if (event.type() == ExecutionEventType.EXECUTION_COMPLETED
                         || event.type() == ExecutionEventType.EXECUTION_FAILED
                         || event.type() == ExecutionEventType.EXECUTION_CANCELLED) {
                     terminal.countDown();
                 }
             })) {

            assertTrue(runner.pauseTraversal(traversalId), "the hold must be accepted");
            var execution = runner.execute(TestIdentities.TENANT_A, traversalId, "payload", "v1")
                    .toCompletableFuture();
            assertFalse(execution.isDone(), "the traversal must be parked at its gate");
            assertTrue(events.contains(ExecutionEventType.EXECUTION_PAUSED),
                    "the hold must be announced before this test can claim anything about its "
                            + "release: " + events);
            assertTrue(runner.isPaused(traversalId), "and it must be readable as held");

            assertTrue(runner.cancelTraversal(traversalId), "cancelling a held traversal must succeed");
            assertTrue(terminal.await(BOUND.toSeconds(), TimeUnit.SECONDS),
                    "a cancelled hold must end the traversal rather than leave it parked");

            assertFalse(runner.isPaused(traversalId), "the hold must be gone once the traversal ends");
            assertFalse(events.contains(ExecutionEventType.EXECUTION_RESUMED),
                    "cancelling a held traversal is not a resumption and must publish none: " + events);
            assertEquals(List.of(ExecutionEventType.EXECUTION_STARTED, ExecutionEventType.EXECUTION_PAUSED,
                            ExecutionEventType.EXECUTION_CANCELLED),
                    lifecycle(events),
                    "a cancelled hold's whole traversal-level sequence is start, pause, cancellation "
                            + "-- EXECUTION_CANCELLED and not EXECUTION_FAILED, because this stream is "
                            + "labelled by event type alone, so publishing the failure here would count "
                            + "an operator's deliberate stop in the failure rate");
            assertEquals(0, effects.get(), "the cancelled traversal must never have run its node");
        }
    }

    /**
     * The runner's shutdown releases a held traversal and publishes no resume either.
     *
     * <p>This is the deployment-stop case at the boundary where it is actually decided.
     * {@code DefaultGraphDeployment.doStop} tears a deployment down by closing the runner that owns
     * its traversals, and {@code GraphRunner.close()} drains every pause gate on its way out — so
     * "a deployment was stopped while an execution was held" reduces to this call. Constraining it
     * here rather than through the deployment keeps the assertion on the thing that decides, exactly
     * as {@code PauseInStartupWindowTest} constrains {@code close()} rather than the composition
     * that reaches it.</p>
     *
     * <p>Note what a shutdown does <em>not</em> do: it does not pretend the traversal resumed, and it
     * does not pretend it completed. The hold simply ends with the runner that held it.</p>
     */
    @Test
    void theRunnersShutdownReleasesAHeldTraversalWithoutPublishingAResume() throws Exception {
        var monitor = new ExecutionMonitor();
        var registry = new BehaviorRegistry().register("record-effect", message ->
                CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())));

        UUID traversalId = UUID.randomUUID();
        List<ExecutionEventType> events = Collections.synchronizedList(new ArrayList<>());

        try (var engine = new SameThreadExecutionEngine();
             var document = GraphManager.readGraphMlDocument(
                     new ByteArrayInputStream(ONE_EFFECT.getBytes(StandardCharsets.UTF_8)));
             AutoCloseable subscription = monitor.subscribe(event -> {
                 if (traversalId.equals(event.traversalId())) {
                     events.add(event.type());
                 }
             })) {

            var runner = new GraphRunner(document.manager(), engine, registry, monitor);
            assertTrue(runner.pauseTraversal(traversalId), "the hold must be accepted");
            var execution = runner.execute(TestIdentities.TENANT_A, traversalId, "payload", "v1")
                    .toCompletableFuture();
            assertFalse(execution.isDone(), "the traversal must be parked at its gate");
            assertTrue(events.contains(ExecutionEventType.EXECUTION_PAUSED),
                    "the hold must be announced first, or the absence below is vacuous: " + events);

            runner.close();

            assertFalse(runner.isPaused(traversalId),
                    "no hold may outlive the runner that owns it");
            assertFalse(events.contains(ExecutionEventType.EXECUTION_RESUMED),
                    "a shutdown that drains a gate is not a resumption: " + events);
        }
    }

    /**
     * A pause landing between a release's removal and its publication must be impossible.
     *
     * <h2>The interleaving, and why code shape alone did not pin it</h2>
     * <p>{@code releasePauseGate} removes the hold from {@code pausedTraversals} and publishes
     * {@code EXECUTION_RESUMED} inside one {@code synchronized} block. Move the removal out of that
     * block — which is how this was first written — and every other test in this file still passes,
     * because each drives one control operation at a time and the two halves of a release are only
     * separable when a pause lands between them.</p>
     *
     * <p>That is the sequence: a resume removes the hold, a pause immediately installs a fresh one
     * and announces it, and only then does the resume publish. The stream reads
     * {@code PAUSED, PAUSED, RESUMED} while the traversal is <em>still holding</em> the second hold,
     * and every reader of that sequence concludes the execution is running. It is not a lost event or
     * a duplicate: each event is individually true, and the order makes them collectively false.</p>
     *
     * <h2>The interleaving is constructed, not raced for</h2>
     * <p>Two threads calling pause and resume against each other do reach it, but only about once in
     * three hundred rounds: with the removal outside the lock the gap between it and the monitor is a
     * couple of instructions wide, so a test built that way kills the defect perhaps one run in three.
     * A mutation killer that usually does not is not a guard.</p>
     *
     * <p>So the window is held open instead of aimed at. {@code announceLocked} publishes
     * {@code EXECUTION_PAUSED} <em>while holding</em> the traversal's control monitor, and listener
     * delivery is synchronous, so a listener for that event runs inside the critical section. This
     * test stands there and, from inside it:</p>
     * <ol>
     *   <li>starts a resume on another thread and gives it a window to act, then <b>asserts that the
     *       hold is still there.</b> That assertion is the discriminator, and it is asserted rather
     *       than merely used: the hold can only vanish while this thread owns the monitor if
     *       something that belongs inside the monitor is outside it, so this one line fails on the
     *       misplaced removal <em>and</em> on a publication reordered around the monitor — the
     *       realistic refactor, same thread, publish just after the release. That second case used to
     *       slip through: the resumer would be unblocked, the window would exit early, and the test
     *       would decay into an ordinary race that failed only sometimes;</li>
     *   <li>then pauses again on this thread, which already owns the monitor and so cannot be
     *       excluded by anything. This is what constructs the defective sequence for the invariant
     *       below, which stands as a second line of defence behind the discriminator.</li>
     * </ol>
     *
     * <p>With the removal misplaced that second pause finds an empty map, installs a hold and
     * announces it, and the resume publishes afterwards — the defective sequence, produced on demand
     * rather than by luck. With the removal in the right place the same second pause loses its
     * {@code putIfAbsent} to the hold that is still there, publishes nothing, and the resume's
     * release is the last word — which is the truth, because nothing is holding. Both outcomes are
     * decided by the lock rather than by timing, so this test is neither flaky nor probabilistic in
     * either direction.</p>
     *
     * <h2>What is asserted, and why it is this and not an event tally</h2>
     * <p>Counting will not catch it. The defective run publishes two pauses and one resume, so
     * "pauses minus resumes equals one while held" is <strong>satisfied</strong> by the defect. Only
     * the order separates them, so the invariant here is positional: while a traversal has not
     * reached a terminal event, it is holding if and only if the last pause-or-resume event published
     * for it was the pause.</p>
     *
     * <p>Whether the traversal goes on to terminate is left free, and does not need constraining. The
     * only way a terminal event could mislead here is by dropping a hold silently, and that needs a
     * <em>second</em> hold to exist — which happens only when the removal is misplaced, and which then
     * parks the traversal at its gate so it cannot terminate at all. With the removal in the right
     * place the second pause loses its {@code putIfAbsent}, no hold survives, and the traversal is
     * free to run on and complete: {@code held == false} with the release last, which is exactly what
     * the invariant expects.</p>
     */
    @Test
    void aPauseLandingBetweenAReleasesRemovalAndItsPublicationIsImpossible() throws Exception {
        int rounds = 5;
        var monitor = new ExecutionMonitor();
        // Returns at once. A behaviour that parked would park the *submitting* thread under
        // SameThreadExecutionEngine, which is the thread the choreography below runs on.
        var registry = new BehaviorRegistry().register("record-effect", message ->
                CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())));

        var live = new java.util.concurrent.ConcurrentHashMap<UUID, Round>();
        try (var engine = new SameThreadExecutionEngine();
             var document = GraphManager.readGraphMlDocument(
                     new ByteArrayInputStream(ONE_EFFECT.getBytes(StandardCharsets.UTF_8)));
             var runner = new GraphRunner(document.manager(), engine, registry, monitor);
             AutoCloseable subscription = monitor.subscribe(event -> {
                 Round round = live.get(event.traversalId());
                 if (round == null) {
                     return;
                 }
                 round.events.add(event.type());
                 // Only the first announcement arms the choreography: the second pause below
                 // publishes another EXECUTION_PAUSED straight back into this listener on this same
                 // thread, and without the guard that would recurse without end.
                 if (event.type() != ExecutionEventType.EXECUTION_PAUSED
                         || !round.armed.compareAndSet(true, false)) {
                     return;
                 }
                 // Reached while execute() owns this traversal's control monitor.
                 UUID traversalId = event.traversalId();
                 var releasing = new CountDownLatch(1);
                 round.resumer = new Thread(() -> {
                     releasing.countDown();
                     runner.resumeTraversal(traversalId);
                 }, "paused-observability-resumer");
                 round.resumer.start();
                 try {
                     assertTrue(releasing.await(BOUND.toSeconds(), TimeUnit.SECONDS),
                             "the resuming thread must have started");
                 } catch (InterruptedException interrupted) {
                     Thread.currentThread().interrupt();
                     throw new IllegalStateException(interrupted);
                 }
                 // The discriminator. A hold cannot disappear while this thread owns the traversal's
                 // control monitor unless something that should be inside that monitor is outside it.
                 // When everything is where it belongs the resuming thread is parked on the monitor,
                 // the hold stays, and this window simply expires.
                 //
                 // Parked rather than spun: under correct code this loop always runs to its deadline,
                 // so a busy wait would burn a core on every green build of a module three lanes
                 // contend for. A millisecond poll is just as prompt against the defect, which needs
                 // only microseconds to become visible.
                 long deadline = System.nanoTime() + REMOVAL_WINDOW.toNanos();
                 while (runner.isPaused(traversalId) && System.nanoTime() < deadline) {
                     try {
                         TimeUnit.MILLISECONDS.sleep(1);
                     } catch (InterruptedException interrupted) {
                         Thread.currentThread().interrupt();
                         break;
                     }
                 }
                 // Recorded here and asserted in the test body rather than asserted here. An
                 // assertion thrown from a listener survives today only because AssertionError is an
                 // Error and this monitor swallows RuntimeException -- a distinction no test should
                 // depend on, because widening that catch to Throwable would delete this check
                 // without a single build going red.
                 round.heldWhileMonitorOwned = runner.isPaused(traversalId);
                 // Reentrant: this thread already owns the monitor, so nothing can exclude this pause.
                 runner.pauseTraversal(traversalId);
             })) {
            for (int index = 0; index < rounds; index++) {
                UUID traversalId = UUID.randomUUID();
                var round = new Round();
                live.put(traversalId, round);

                assertTrue(runner.pauseTraversal(traversalId),
                        "round " + index + ": the initial hold must be accepted");
                runner.execute(TestIdentities.TENANT_A, traversalId, "payload", "v1");
                assertFalse(round.armed.get(),
                        "round " + index + ": the choreography never ran, so this round asserted "
                                + "nothing about the case it exists for");
                assertEquals(Boolean.TRUE, round.heldWhileMonitorOwned,
                        "round " + index + ": while this traversal's control monitor was held, its "
                                + "hold must still have been in place. That it was not means a step "
                                + "that belongs inside that monitor happened outside it -- either the "
                                + "removal, or the publication that must not be reordered around it.");
                round.resumer.join(BOUND.toMillis());

                List<ExecutionEventType> lifecycle = lifecycle(round.events);
                assertEquals(List.of(ExecutionEventType.EXECUTION_STARTED,
                                ExecutionEventType.EXECUTION_PAUSED),
                        lifecycle.subList(0, 2),
                        "round " + index + ": the case starts from one announced hold: " + lifecycle);

                    ExecutionEventType last = null;
                for (ExecutionEventType type : lifecycle) {
                    if (type == ExecutionEventType.EXECUTION_PAUSED
                            || type == ExecutionEventType.EXECUTION_RESUMED) {
                        last = type;
                    }
                }
                boolean held = runner.isPaused(traversalId);
                assertEquals(held, last == ExecutionEventType.EXECUTION_PAUSED,
                        "round " + index + ": a traversal that is "
                                + (held ? "holding" : "not holding")
                                + " must not have published " + last + " last. Removing a hold and "
                                + "publishing its release have to be one step, or a pause lands "
                                + "between them: " + lifecycle);
            }
        }
    }

    /**
     * How long the choreography waits for a misplaced step to become visible.
     *
     * <p>Not a timing assumption about the product, and deliberately <strong>one-sided</strong>. Under
     * correct code the verdict does not depend on it at all: the resuming thread is provably parked on
     * a monitor this thread owns, so the hold is still there for any window, including a zero-length
     * one. Under the defect it bounds nothing but a wait for an already-started thread to perform a
     * single map removal — microseconds of work with orders of magnitude of headroom here.</p>
     *
     * <p>So a window that is too short can only cost detection, never produce a red build on correct
     * code: the failure direction is a silent pass. That asymmetry is what makes it safe to keep this
     * short, and short is worth having, because under correct code the loop always runs to the
     * deadline and this is therefore the test's own cost on every green build.</p>
     */
    private static final Duration REMOVAL_WINDOW = Duration.ofMillis(100);

    /** One round's recorded events, its re-entrancy guard, and the thread performing its resume. */
    private static final class Round {
        private final List<ExecutionEventType> events = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean armed = new AtomicBoolean(true);
        private volatile Thread resumer;
        /**
         * Whether the hold was still in place at the end of the window, observed while this
         * traversal's control monitor was held. {@code null} until the choreography has run, so a
         * round that never reached it is distinguishable from one that observed {@code false}.
         */
        private volatile Boolean heldWhileMonitorOwned;
    }

    /**
     * A traversal resumed after a human task announces its hold like every other traversal.
     *
     * <h2>Why a third near-identical test earns its place</h2>
     * <p>The runtime has three entry paths — an ordinary submission, tool-approval re-entry, and
     * human-task re-entry — and each builds its own {@code ExecutionIdentity}. The pause machinery
     * only becomes observable on a path that hands that identity to the traversal's control record,
     * so a path that forgets to is not partly wired: it holds normally, reports {@code paused}
     * normally, and publishes <em>nothing</em>. The failure is silent on exactly the surface the
     * events exist to fill, which is why it is worth a test per path rather than a comment saying the
     * three are the same.</p>
     *
     * <p>This path is the one that could not be covered when the other two were, because it did not
     * exist yet. It is currently unreachable from the application — its continuation executor builds
     * a runner of its own and never publishes into the live-execution bookkeeping a pause command
     * reads — so the assertion is made at the runner, which is where the wiring lives and where the
     * other two are constrained as well.</p>
     */
    @Test
    void aTraversalResumedAfterAHumanTaskAnnouncesItsHoldAfterTheStartEvent() throws Exception {
        var monitor = new ExecutionMonitor();
        var registry = new BehaviorRegistry().register("record-effect", message ->
                CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())));

        var key = new ai.ravenroot.api.persistence.ExecutionKey("tenant-a", UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        List<ExecutionEventType> events = Collections.synchronizedList(new ArrayList<>());
        var terminal = new CountDownLatch(1);

        try (var store = new InMemoryExecutionStore();
             var engine = new SameThreadExecutionEngine();
             var document = GraphManager.readGraphMlDocument(
                     new ByteArrayInputStream(ONE_EFFECT.getBytes(StandardCharsets.UTF_8)));
             var runner = new GraphRunner(document.manager(), engine, registry, monitor);
             AutoCloseable subscription = monitor.subscribe(event -> {
                 if (!traversalId.equals(event.traversalId())) {
                     return;
                 }
                 events.add(event.type());
                 if (event.type() == ExecutionEventType.EXECUTION_COMPLETED
                         || event.type() == ExecutionEventType.EXECUTION_FAILED
                         || event.type() == ExecutionEventType.EXECUTION_CANCELLED) {
                     terminal.countDown();
                 }
             })) {
            long revision = acceptReentry(store, key, traversalId);
            try (var recorder = ExecutionRecorder.open(store, key, "human-task-pause", LEASE_TTL, revision)) {

                // The hold is taken before the re-entry runs, which is the case that distinguishes a
                // wired path from an unwired one: it can only be announced by the entry path itself.
                assertTrue(runner.pauseTraversal(traversalId), "the hold must be accepted");

                var execution = runner.executeAfterHumanTask(TestIdentities.TENANT_A,
                        key.processInstanceId(), traversalId, "effect", "v1", recorder,
                        NodeResult.continueWith("resumed"),
                        new GraphExecutionBudgetSnapshot(1, 0, 0, 1, 0)).toCompletableFuture();

                assertFalse(execution.isDone(), "the re-entered traversal must be parked at its gate");
                assertEquals(List.of(ExecutionEventType.EXECUTION_STARTED,
                                ExecutionEventType.EXECUTION_PAUSED),
                        lifecycle(events),
                        "a hold on a human-task re-entry is announced, and after the start event: "
                                + events);
                assertTrue(runner.isPaused(traversalId), "and the traversal is readable as holding");

                assertTrue(runner.resumeTraversal(traversalId), "the announced hold must be releasable");
                assertTrue(terminal.await(BOUND.toSeconds(), TimeUnit.SECONDS),
                        "the released re-entry must reach its terminal event");
                assertEquals(List.of(ExecutionEventType.EXECUTION_STARTED,
                                ExecutionEventType.EXECUTION_PAUSED,
                                ExecutionEventType.EXECUTION_RESUMED,
                                ExecutionEventType.EXECUTION_COMPLETED),
                        lifecycle(events),
                        "the release pairs with the hold on this path exactly as on the other two");
                assertFalse(runner.isPaused(traversalId), "a finished traversal holds nothing");
            }
        }
    }

    /** Lease bound for the re-entry recorder; long enough that it cannot expire mid-test. */
    private static final Duration LEASE_TTL = Duration.ofSeconds(30);

    /**
     * Creates the accepted, running process instance a human-task re-entry resumes into, and returns
     * the revision its recorder must open against.
     *
     * @param store the execution store backing the re-entry
     * @param key the tenant and process instance being resumed
     * @param traversalId the re-entry traversal
     * @return the revision after the instance has been marked running
     */
    private static long acceptReentry(ai.ravenroot.api.persistence.ExecutionStore store,
                                      ai.ravenroot.api.persistence.ExecutionKey key, UUID traversalId) {
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

    /**
     * A hold that is withdrawn before it was ever announced publishes neither event.
     *
     * <h2>The interleaving, and why it is not merely tidy</h2>
     * <p>A traversal becomes pausable the moment the application lists it live, which is before the
     * runtime has built the identity every event is published under. A pause accepted in that window
     * therefore cannot be published yet, and is announced once the traversal starts. A resume that
     * arrives while it is still unannounced removes a hold nobody was ever told about.</p>
     *
     * <p>Publishing the pair anyway would put two events into the stream for a hold that was never
     * visible, and — worse — leaves the ordering to whichever thread got there first, so a reader
     * could see the resume ahead of the pause it released. Publishing neither is the only answer
     * that is true from every observer's position: nothing was shown, so nothing was withdrawn.</p>
     *
     * <p>The traversal must still complete: withdrawing an unannounced hold has to open the gate,
     * not merely forget it.</p>
     */
    @Test
    void aHoldWithdrawnBeforeItWasAnnouncedPublishesNeitherEvent() throws Exception {
        var monitor = new ExecutionMonitor();
        var store = new SuspendFirstWriteExecutionStore(new InMemoryExecutionStore());
        var effects = new AtomicInteger();
        var effectRan = new CountDownLatch(1);
        var registry = new BehaviorRegistry().register("record-effect", message -> {
            effects.incrementAndGet();
            effectRan.countDown();
            return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        });

        UUID traversalId = UUID.randomUUID();
        List<ExecutionEventType> events = Collections.synchronizedList(new ArrayList<>());
        var terminal = new CountDownLatch(1);

        try (var engine = new SameThreadExecutionEngine();
             var application = new DefaultRavenrootApplication(engine, monitor, registry,
                     new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                     ExecutionIdentitySource.randomUuids(), store);
             AutoCloseable subscription = monitor.subscribe(event -> {
                 if (!traversalId.equals(event.traversalId())) {
                     return;
                 }
                 events.add(event.type());
                 if (event.type() == ExecutionEventType.EXECUTION_COMPLETED
                         || event.type() == ExecutionEventType.EXECUTION_FAILED
                         || event.type() == ExecutionEventType.EXECUTION_CANCELLED) {
                     terminal.countDown();
                 }
             })) {

            var submitter = new Thread(() -> application.startGraphMl(TestIdentities.TENANT_A, traversalId,
                    new ByteArrayInputStream(ONE_EFFECT.getBytes(StandardCharsets.UTF_8)), "payload"),
                    "withdrawn-hold-submitter");
            submitter.start();

            assertTrue(store.awaitFirstWrite(BOUND), "the submission must reach the startup window");
            assertTrue(application.pauseTraversal(traversalId),
                    "a pause inside the startup window must still be accepted");
            assertTrue(application.executionPaused(traversalId),
                    "and it must be readable as a hold even before it can be announced");
            assertTrue(application.resumeTraversal(traversalId),
                    "and the resume must find and remove that same hold");
            assertFalse(application.executionPaused(traversalId), "leaving nothing held");
            store.releaseFirstWrite();
            submitter.join(BOUND.toMillis());
            assertTrue(store.firstWriteWasHeldOpen(),
                    "the window must have been entered, or this test raced instead of standing in it");

            assertTrue(effectRan.await(BOUND.toSeconds(), TimeUnit.SECONDS),
                    "withdrawing an unannounced hold must open the gate, not forget it");
            assertTrue(terminal.await(BOUND.toSeconds(), TimeUnit.SECONDS),
                    "and the traversal must reach its terminal event");

            assertEquals(1, effects.get(), "the released traversal runs its node exactly once");
            assertFalse(events.contains(ExecutionEventType.EXECUTION_PAUSED),
                    "a hold nobody was told about must not be announced after it is gone: " + events);
            assertFalse(events.contains(ExecutionEventType.EXECUTION_RESUMED),
                    "and its withdrawal must not be announced either: " + events);
        }
    }

    /**
     * A hold accepted inside the startup window is announced once, after the start event, and its
     * resume follows it.
     *
     * <p>The companion of the test above: the same window, and the same hold, but nobody withdraws
     * it before the traversal starts. What is pinned is the <em>order</em> — a pause that was
     * accepted before the runtime had an identity must not be published ahead of
     * {@code EXECUTION_STARTED}, because an execution that is held before it has started is a
     * sequence no consumer can reconcile — and that the pair is published exactly once each.</p>
     *
     * <p>The whole visible sequence is asserted rather than the presence of two types, so a
     * duplicate announcement or an extra release would fail here rather than pass unnoticed.</p>
     */
    @Test
    void aHoldFromTheStartupWindowIsAnnouncedAfterTheStartEventAndItsResumeFollowsIt() throws Exception {
        var monitor = new ExecutionMonitor();
        var store = new SuspendFirstWriteExecutionStore(new InMemoryExecutionStore());
        var effectRan = new CountDownLatch(1);
        var registry = new BehaviorRegistry().register("record-effect", message -> {
            effectRan.countDown();
            return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        });

        UUID traversalId = UUID.randomUUID();
        List<ExecutionEventType> events = Collections.synchronizedList(new ArrayList<>());
        var paused = new CountDownLatch(1);
        var terminal = new CountDownLatch(1);

        try (var engine = new SameThreadExecutionEngine();
             var application = new DefaultRavenrootApplication(engine, monitor, registry,
                     new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                     ExecutionIdentitySource.randomUuids(), store);
             AutoCloseable subscription = monitor.subscribe(event -> {
                 if (!traversalId.equals(event.traversalId())) {
                     return;
                 }
                 events.add(event.type());
                 if (event.type() == ExecutionEventType.EXECUTION_PAUSED) {
                     paused.countDown();
                 }
                 if (event.type() == ExecutionEventType.EXECUTION_COMPLETED
                         || event.type() == ExecutionEventType.EXECUTION_FAILED
                         || event.type() == ExecutionEventType.EXECUTION_CANCELLED) {
                     terminal.countDown();
                 }
             })) {

            var submitter = new Thread(() -> application.startGraphMl(TestIdentities.TENANT_A, traversalId,
                    new ByteArrayInputStream(ONE_EFFECT.getBytes(StandardCharsets.UTF_8)), "payload"),
                    "announced-hold-submitter");
            submitter.start();

            assertTrue(store.awaitFirstWrite(BOUND), "the submission must reach the startup window");
            assertTrue(application.pauseTraversal(traversalId), "the hold must be accepted");
            assertTrue(events.isEmpty(),
                    "nothing may be published for a traversal whose identity does not exist yet: "
                            + events);
            store.releaseFirstWrite();
            submitter.join(BOUND.toMillis());

            assertTrue(paused.await(BOUND.toSeconds(), TimeUnit.SECONDS),
                    "a hold carried through the startup window must be announced once the traversal "
                            + "starts");
            assertFalse(effectRan.await(HELD_BOUND.toSeconds(), TimeUnit.SECONDS),
                    "and the traversal must still be holding: no node may run before the resume");
            assertEquals(List.of(ExecutionEventType.EXECUTION_STARTED, ExecutionEventType.EXECUTION_PAUSED),
                    lifecycle(events),
                    "the hold is announced after the start event and exactly once");
            assertEquals(List.of(traversalId),
                    application.liveExecutions("tenant-a").stream().map(LiveExecution::traversalId).toList(),
                    "a held traversal is still live");
            assertTrue(application.liveExecutions("tenant-a").stream().allMatch(LiveExecution::paused),
                    "and the listing must say it is holding, which is what separates it from a stall");

            assertTrue(application.resumeTraversal(traversalId), "the announced hold must be releasable");
            assertTrue(effectRan.await(BOUND.toSeconds(), TimeUnit.SECONDS),
                    "the resume must let the held hop run");
            assertTrue(terminal.await(BOUND.toSeconds(), TimeUnit.SECONDS),
                    "and the traversal must reach its terminal event");

            assertEquals(List.of(ExecutionEventType.EXECUTION_STARTED, ExecutionEventType.EXECUTION_PAUSED,
                            ExecutionEventType.EXECUTION_RESUMED, ExecutionEventType.EXECUTION_COMPLETED),
                    lifecycle(events),
                    "the resume follows the pause it releases, and the run continues from there");
            assertTrue(events.indexOf(ExecutionEventType.NODE_STARTED)
                            > events.indexOf(ExecutionEventType.EXECUTION_RESUMED),
                    "and the held node started only after the release: " + events);
            assertFalse(application.executionPaused(traversalId),
                    "a finished traversal holds nothing");
        }
    }
}
