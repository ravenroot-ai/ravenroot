package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.JoinSemantics;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cycle that passes through a fan-in runs once per lap, not once.
 *
 * <p>This executes the defect end to end. With one terminal join record shared across laps, the first lap is correct —
 * {@code JOIN_SATISFIED quorum=3 arrived=3} — and every arrival of the second lap was answered
 * {@code JOIN_ARRIVAL_DISCARDED reason=LATE}: the join's record was terminal, so the node downstream
 * of it was never invoked again and the traversal reported {@code EXECUTION_COMPLETED} having
 * silently dropped two thirds of the work it was asked to do. A discarded arrival is documented as
 * "never an error", which is what made the loss invisible.</p>
 *
 * <p>The loop is bounded by a router node rather than by a wall-clock limit, so the assertion is
 * "exactly three" rather than "at least two": an implementation that fired the join an unbounded number of times
 * would be as wrong as one that fired it once, and only an exact count catches both.</p>
 */
class JoinIterationRearmTest {

    private static final int LAPS = 3;

    private final JoinTestEngine engine = new JoinTestEngine();
    private final ExecutionMonitor monitor = new ExecutionMonitor();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    @Test
    void runsTheJoinOncePerLapOfACycleThroughIt() throws Exception {
        var run = runCycle(LAPS);

        assertEquals(LAPS, run.eventCount(ExecutionEventType.JOIN_SATISFIED),
                "the join must fire once per lap; a one-shot join fires once and treats later arrivals as LATE");
        assertEquals(LAPS, run.router.get(), "the node downstream of the join must run once per firing");
        for (String branch : List.of("dosomething", "b0", "b1", "b2")) {
            assertEquals(LAPS, run.invocations.get(branch).get(), () -> branch + " must run once per lap");
        }

        // The exact symptom, asserted as an absence. Not "no failures" -- a LATE discard is not a
        // failure, which is precisely why the defect completed successfully and lost the work.
        assertEquals(List.of(), run.lateDiscards(), "no arrival of laps 2 and 3 may be discarded as late");

        assertEquals(1, run.eventCount(ExecutionEventType.EXECUTION_COMPLETED),
                "the traversal must complete, and complete having done all three laps");
        assertEquals(0, run.eventCount(ExecutionEventType.EXECUTION_FAILED));
    }

    /**
     * The iteration diagnostic, emitted by a graph rather than by hand-minted laps.
     *
     * <p>A diagnostic that counts iterations <em>waiting</em> cannot be reached by any execution. A join cannot begin an
     * iteration before firing the previous one, so that number is never more than one and a threshold
     * of sixteen over it was a public event constant nothing could emit — the shape of alarm an
     * operator builds and then never hears. What grows is what is <em>retained</em>: one bucket of
     * arrivals per lap, kept for the life of the traversal because ADR 0019's insert-only invariant
     * forbids compacting the buckets that have fired. That is the number now reported, and a cycle
     * that goes round far enough crosses it.</p>
     */
    @Test
    void reportsTheIterationBacklogFromARealCycle() throws Exception {
        var run = runCycle(JoinCoordinator.ITERATION_BACKLOG_THRESHOLD + 2);

        var backlog = run.events.stream()
                .filter(event -> event.type() == ExecutionEventType.JOIN_ITERATION_BACKLOG)
                .toList();
        assertEquals(1, backlog.size(), "once per crossing, and the count only ever grows within a traversal");
        assertTrue(backlog.getFirst().detail().startsWith("iterations="), backlog.getFirst().detail());
        assertTrue(backlog.getFirst().detail()
                        .contains("threshold=" + JoinCoordinator.ITERATION_BACKLOG_THRESHOLD),
                "the reader must not have to know the runtime's internal default to interpret the count");
    }

    /** And a cycle that stays well short of it says nothing. */
    @Test
    void reportsNoIterationBacklogForAnOrdinaryCycle() throws Exception {
        var run = runCycle(LAPS);
        assertEquals(0, run.eventCount(ExecutionEventType.JOIN_ITERATION_BACKLOG));
    }

    // ------------------------------------- a straggler must not arm a deadline for a bucket to come

    /**
     * An ordinary <em>acyclic</em> traversal must complete without a deadline for a nonexistent
     * iteration.
     *
     * <p>Arming "a deadline per iteration" from {@code firedThrough + 1} on every report, before
     * knowing which iteration the report belonged to, is unsafe. On a {@code k of n} join
     * with {@code k < n} the straggler — a branch that finishes after the quorum was already met, which
     * is the ordinary case such a join exists for — therefore armed a deadline for iteration 1 on a
     * graph that has no iteration 1. The record stays {@code OPEN} because the join re-arms, so the
     * timer could not tell the join was finished: it fired and failed a traversal whose join had
     * already succeeded.
     *
     * <p>Three ordinary things have to be true at once for it, and all three worked before this
     * branch: a declared {@code joinTimeout}, a quorum below the branch count, and a downstream longer
     * than the deadline. So the test constructs exactly that, and the load-bearing assertion is the
     * live scheduler count taken <em>after</em> the stragglers have been discarded — the traversal's
     * own success is the consequence, and it would still pass if the timer merely fired late.</p>
     */
    @Test
    void aStragglerOnAnAcyclicJoinArmsNoDeadlineAndTheTraversalCompletes() throws Exception {
        var released = new CompletableFuture<NodeResult>();
        var downstreamGate = new CompletableFuture<NodeResult>();
        var registry = new BehaviorRegistry();
        // Meets the quorum of one on its own and fires the join.
        registry.register("b0", message ->
                CompletableFuture.completedFuture(NodeResult.continueWith("from-b0")));
        // The stragglers: they reach the join only once the test lets them.
        for (String branch : List.of("b1", "b2")) {
            registry.register(branch, message -> released.thenApply(ignored -> NodeResult.continueWith(branch)));
        }
        // Longer than the deadline, so the traversal is still alive when the timer is fired.
        registry.register("downstream", message -> downstreamGate);

        try (var manager = GraphManager.from(anyOfThreeWithTimeout());
             var runner = new GraphRunner(manager, engine, registry, monitor)) {
            var execution = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture();

            awaitEvents(ExecutionEventType.JOIN_SATISFIED, 1);
            released.complete(NodeResult.continueWith("late"));
            awaitLateDiscards(2);

            assertEquals(0, engine.manualScheduler().liveCount(),
                    "a straggler of an iteration that already fired must arm no deadline: on an acyclic "
                            + "graph the iteration it would guard does not exist and never will");
            assertEquals(0, runner.liveJoinTimeoutCount());
            assertEquals(0, engine.manualScheduler().fireAll(), "there must be nothing left to fire");

            downstreamGate.complete(NodeResult.continueWith("done"));
            execution.get(10, TimeUnit.SECONDS);
        }

        var events = monitor.eventsAfter(0);
        assertEquals(0, events.stream().filter(event -> event.type() == ExecutionEventType.JOIN_FAILED).count(),
                "a join that met its quorum must not then be reported as failed");
        assertEquals(0, events.stream().filter(event -> event.type() == ExecutionEventType.EXECUTION_FAILED).count());
        assertEquals(1, events.stream().filter(event -> event.type() == ExecutionEventType.EXECUTION_COMPLETED).count());
    }

    // ----------------------------------------------------------------------------------- helpers

    private record CycleRun(List<ai.ravenroot.api.application.ExecutionEvent> events,
                            Map<String, AtomicInteger> invocations, AtomicInteger router) {
        private long eventCount(ExecutionEventType type) {
            return events.stream().filter(event -> event.type() == type).count();
        }

        private List<ai.ravenroot.api.application.ExecutionEvent> lateDiscards() {
            return events.stream()
                    .filter(event -> event.type() == ExecutionEventType.JOIN_ARRIVAL_DISCARDED)
                    .filter(event -> event.detail() != null && event.detail().contains("reason=LATE"))
                    .toList();
        }
    }

    private CycleRun runCycle(int laps) throws Exception {
        var invocations = new ConcurrentHashMap<String, AtomicInteger>();
        var router = new AtomicInteger();

        var registry = new BehaviorRegistry();
        for (String branch : List.of("dosomething", "b0", "b1", "b2")) {
            registry.register(branch, message -> {
                invocations.computeIfAbsent(branch, ignored -> new AtomicInteger()).incrementAndGet();
                return CompletableFuture.completedFuture(NodeResult.continueWith("from-" + branch));
            });
        }
        // The bound on the loop. "again" for the first laps-1 passes, "done" on the last, so the join
        // fires exactly `laps` times and the traversal terminates instead of running forever.
        registry.register("router", message -> {
            int pass = router.incrementAndGet();
            return CompletableFuture.completedFuture(
                    new NodeResult(pass < laps ? "again" : "done", message.payload(), message.attributes()));
        });

        try (var manager = GraphManager.from(cyclicFanIn());
             var runner = new GraphRunner(manager, engine, registry, monitor)) {
            var result = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                    .get(20, TimeUnit.SECONDS);
            assertTrue(result.visitedNodes().contains("join"), "the join must have been traversed");
        }
        return new CycleRun(monitor.eventsAfter(0), invocations, router);
    }

    private void awaitEvents(ExecutionEventType type, int count) throws InterruptedException {
        awaitCondition(() -> monitor.eventsAfter(0).stream().filter(event -> event.type() == type).count() >= count,
                () -> "expected " + count + " " + type + " event(s)");
    }

    private void awaitLateDiscards(int count) throws InterruptedException {
        awaitCondition(() -> monitor.eventsAfter(0).stream()
                        .filter(event -> event.type() == ExecutionEventType.JOIN_ARRIVAL_DISCARDED)
                        .filter(event -> event.detail() != null && event.detail().contains("reason=LATE"))
                        .count() >= count,
                () -> "expected " + count + " late discard(s)");
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition,
                                       java.util.function.Supplier<String> message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError(message.get() + " within 10s, and none arrived");
    }

    /**
     * The reported topology: a fan-out of three into an {@code all} join, whose successor either
     * routes back to the fan-out's source or on to the end.
     *
     * <p>Carries {@link JoinSemantics#MARKER_PROPERTY} because the feedback edge gives
     * {@code dosomething} two distinct predecessors, and under the older reading that alone would
     * make it a synchronisation point waiting for an arrival from {@code start} that can only ever
     * come once. That is a join-semantics question, so the graph answers it explicitly: only
     * the node that declares a join property is a join.</p>
     */
    private static GraphDefinition cyclicFanIn() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("dosomething", "dosomething"),
                GraphNode.behavior("b0", "b0"),
                GraphNode.behavior("b1", "b1"),
                GraphNode.behavior("b2", "b2"),
                new GraphNode("join", NodeKind.PASSTHROUGH, null,
                        Map.of(JoinSemantics.POLICY_PROPERTY, "all")),
                GraphNode.behavior("router", "router"),
                GraphNode.error("error"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "dosomething"),
                GraphEdge.to("dosomething", "b0"),
                GraphEdge.to("dosomething", "b1"),
                GraphEdge.to("dosomething", "b2"),
                GraphEdge.to("b0", "join"),
                GraphEdge.to("b1", "join"),
                GraphEdge.to("b2", "join"),
                GraphEdge.to("join", "router"),
                new GraphEdge("router", "dosomething", "again"),
                new GraphEdge("router", "end", "done")),
                Map.of(JoinSemantics.MARKER_PROPERTY, JoinSemantics.DECLARED));
    }

    /**
     * An ordinary acyclic fan-in with a quorum below its branch count and a declared deadline, plus a
     * downstream node that can be held open. Nothing about it is unusual: it is the ordinary
     * {@code any} case and does not depend on iteration re-arming.
     */
    private static GraphDefinition anyOfThreeWithTimeout() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("b0", "b0"),
                GraphNode.behavior("b1", "b1"),
                GraphNode.behavior("b2", "b2"),
                new GraphNode("join", NodeKind.PASSTHROUGH, null, Map.of(
                        JoinSemantics.POLICY_PROPERTY, "any",
                        JoinSemantics.TIMEOUT_PROPERTY, "PT30S")),
                GraphNode.behavior("downstream", "downstream"),
                GraphNode.error("error"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "b0"),
                GraphEdge.to("start", "b1"),
                GraphEdge.to("start", "b2"),
                GraphEdge.to("b0", "join"),
                GraphEdge.to("b1", "join"),
                GraphEdge.to("b2", "join"),
                GraphEdge.to("join", "downstream"),
                GraphEdge.to("downstream", "end")));
    }
}
