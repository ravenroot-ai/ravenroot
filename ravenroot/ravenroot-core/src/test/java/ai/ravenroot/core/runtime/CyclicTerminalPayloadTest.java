package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.execution.NodeFailurePayload;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.FailureRouteEdgeProperty;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.GraphValidationException;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A terminal reached more than once, and why the result payload survives it.
 *
 * <h2>The premise this exists to check</h2>
 * <p>Determinism with one result field per terminal requires each field to have a single writer.
 * {@code END} is unique and {@code ERROR} is unique, but neither necessarily "fires once".
 * Nothing in {@code GraphDefinition} forbids
 * a cycle, and a cycle that passes back through a terminal reaches it again — so the field really is
 * written twice, and the premise as stated does not hold.</p>
 *
 * <p>A cycle alone does not establish that repeat writes are causally ordered, with the second arrival
 * existing only because the traversal continued from the first. That is true under the default join
 * configuration, and false in general — {@code joinPolicy=each} removes a node from fan-in detection
 * entirely, so its arrivals are neither coordinated nor causally chained, and two of them race.
 * {@link #eachOnATerminalReopensTwoRacingWritersAndLosesTheDiscardRecord()} and
 * {@link #aFanOutIntoAnEachMergeRacesOnAnUntouchedTerminal()} build that case; the counterexample sits
 * one fan-out away from the cycle these other tests use.</p>
 *
 * <p>The condition "every fan-in on a route into that terminal is coordinated" is insufficient. It is falsified by
 * {@link #startReEntryRacesWithNoConfigurationAnywhere()}, where {@code JoinSpec.validate} returns an
 * empty map and the condition therefore holds vacuously while the terminal is still entered twice.
 * {@code GraphRunner.ExecutionState.endTerminalPayload} now states the matter as a precondition
 * instead: a race requires the terminal to be invoked twice concurrently, which requires one of three
 * mechanisms — an uncoordinated fan-in on the route, {@code START} re-entered by a return transition,
 * or a node delivering twice to one successor (impossible today). The tests here are the instances of
 * the first two; the third is closed at the dispatch site.</p>
 *
 * <p>"Fan-in" throughout means <em>operationally</em> what {@code JoinSpec.validate} returns a spec
 * for, not structurally "a node with two or more predecessors". The two differ in exactly the two
 * ways the tests below build, and reading it structurally would make the condition unsatisfiable for
 * a re-entered {@code START} rather than false — which is not a useful thing to tell a reader.</p>
 *
 * <p>Each test pins that the terminal really is entered more than once, so the case is exercised
 * rather than merely described, and where the outcome is a race it pins what is invariant without
 * asserting which payload wins.</p>
 *
 * <h2>Convention: quoted figures are probe measurements, not assertions</h2>
 * <p>Every distribution quoted in the Javadoc of this class — {@code {lap-3=40, lap-2=10}},
 * {@code {B-lap=155, A-lap=45}} and the rest — comes from a probe run at the
 * traversal count named beside it, and is reported to show <em>how</em> a race behaves. None of them
 * is what a test asserts. What the tests verify is {@link #REPEATS} runs of each case and only the
 * invariants: how many times a terminal completed, which events were emitted and which failures were
 * journalled. The figures provide evidence about race behaviour but are not expected distributions.</p>
 *
 * <p>Everything here is asserted over repeats for the reason the sibling race test gives: a single
 * green run cannot distinguish "deterministic" from "lucky this time". Where the outcome is genuinely
 * a race, the tests pin what is invariant and deliberately do not assert which payload wins.</p>
 */
class CyclicTerminalPayloadTest {

    private static final int REPEATS = 30;

    private final JoinTestEngine engine = new JoinTestEngine();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    /**
     * {@code start -> probe -(retry)-> error -> probe}, with {@code probe -(done)-> end} present only
     * when {@code withEndRoute}.
     *
     * <p>{@code probe} carries {@code joinPolicy=each} because the cycle gives it two distinct
     * predecessors ({@code start} and {@code error}) and every such node is otherwise promoted to a
     * fan-in, which would make it wait for a branch that is not a branch. That is the documented
     * policy for state-machine merge points, not a workaround invented here.</p>
     */
    private static GraphDefinition cyclicThroughErrorTerminal(boolean withEndRoute) {
        var nodes = List.of(
                GraphNode.start("start"),
                new GraphNode("probe", NodeKind.BEHAVIOR, "probe", Map.of("joinPolicy", "each")),
                GraphNode.error("error"),
                GraphNode.end("end"));
        var edges = new java.util.ArrayList<GraphEdge>(List.of(
                GraphEdge.to("start", "probe"),
                new GraphEdge("probe", "error", "retry", Map.of()),
                GraphEdge.to("error", "probe")));
        if (withEndRoute) {
            edges.add(new GraphEdge("probe", "end", "done", Map.of()));
        }
        return new GraphDefinition(nodes, edges);
    }

    /** Fails twice with {@code retry}, then succeeds with {@code done}. */
    private static BehaviorRegistry twoLapsThenDone() {
        var calls = new AtomicInteger();
        return new BehaviorRegistry().register("probe", message -> {
            int call = calls.incrementAndGet();
            return CompletableFuture.completedFuture(call <= 2
                    ? new NodeResult("retry", "attempt-" + call, Map.of())
                    : new NodeResult("done", "final-" + call, Map.of()));
        });
    }

    @Test
    void aCycleReallyEntersTheErrorTerminalTwice() throws Exception {
        var monitor = new ExecutionMonitor();
        try (var manager = GraphManager.from(cyclicThroughErrorTerminal(true));
             var runner = new GraphRunner(manager, engine, twoLapsThenDone(), monitor)) {
            runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(2, monitor.eventsAfter(0).stream()
                            .filter(event -> event.type() == ExecutionEventType.NODE_COMPLETED
                                    && "error".equals(event.nodeId()))
                            .count(),
                    "the premise under test is that a terminal can be entered more than once; if this "
                            + "is 1 the graph stopped exercising the case and the tests below prove "
                            + "nothing about it");
        }
    }

    /**
     * Two laps through the error terminal and then {@code END}: the result is {@code END}'s payload,
     * on every run. Pins the precedence against a graph in which the error terminal wrote its own
     * field twice before {@code END} ever ran.
     */
    @Test
    void reportsTheEndPayloadOnEveryRunDespiteTwoPassesThroughTheErrorTerminal() throws Exception {
        var observed = new LinkedHashSet<String>();
        for (int run = 0; run < REPEATS; run++) {
            try (var manager = GraphManager.from(cyclicThroughErrorTerminal(true));
                 var runner = new GraphRunner(manager, engine, twoLapsThenDone(), new ExecutionMonitor())) {
                observed.add(String.valueOf(runner.execute(TestIdentities.TENANT_A, "in")
                        .toCompletableFuture().get(5, TimeUnit.SECONDS).payload()));
            }
        }
        assertEquals(java.util.Set.of("final-3"), observed,
                "END outranks the error terminal however many times the cycle passed through it");
    }

    /**
     * The same cycle with no route to {@code END}: the traversal ends having passed the error terminal
     * twice, and reports the <em>second</em> pass — the last write in program order — on every run.
     *
     * <p>This is the test that actually distinguishes "causally ordered" from "racy". Two writes land
     * on the same field with no {@code END} to mask them, so if their order were decided by the
     * scheduler this would sometimes report the first-pass payload.</p>
     */
    @Test
    void reportsTheLastPassThroughTheErrorTerminalOnEveryRunWhenEndIsUnreachable() throws Exception {
        var observed = new LinkedHashSet<String>();
        for (int run = 0; run < REPEATS; run++) {
            try (var manager = GraphManager.from(cyclicThroughErrorTerminal(false));
                 var runner = new GraphRunner(manager, engine, twoLapsThenDone(), new ExecutionMonitor())) {
                observed.add(String.valueOf(runner.execute(TestIdentities.TENANT_A, "in")
                        .toCompletableFuture().get(5, TimeUnit.SECONDS).payload()));
            }
        }
        assertTrue(observed.size() == 1,
                () -> "the payload varies between identical runs, so successive passes through a "
                        + "terminal are not serialised even under the default join configuration -- "
                        + "which is a narrower and more surprising claim than the one "
                        + "eachOnATerminalReopensTwoRacingWritersAndLosesTheDiscardRecord() already "
                        + "demonstrates, and would need separate analysis rather than a change here: "
                        + observed);
        assertEquals(java.util.Set.of("attempt-2"), observed,
                "the last pass in program order is the one reported");
    }

    /** {@code start -> first|second -(failure route)-> error}, with {@code each} on the terminal. */
    private static GraphDefinition eachOnTheTerminal() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("first", "first"),
                GraphNode.behavior("second", "second"),
                new GraphNode("error", NodeKind.ERROR, null, Map.of("joinPolicy", "each")),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "first"),
                GraphEdge.to("start", "second"),
                new GraphEdge("first", "error", null,
                        Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE)),
                new GraphEdge("second", "error", null,
                        Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE))));
    }

    /**
     * {@code start -> A|B -> merge -(routed)-> error}, with {@code each} on the ordinary merge. The
     * terminal itself carries no property and has a single predecessor, so nothing about it is unusual.
     *
     * <h2>Why {@code merge} declares an outcome, and why it is a behavior</h2>
     * <p>This edge used to be bare. A bare edge into an {@code ERROR}
     * node is the <em>unhandled-failure</em> route and is deliberately not taken on a success, so a
     * bare edge here would mean the terminal is never entered and this test would be asserting over an
     * empty run. Reaching the error terminal on a <em>success</em> is still expressible — that is the
     * half of the ruling that says "a handled error goes where it is routed" — and this is what it
     * looks like: an explicitly named outcome on the edge.</p>
     *
     * <p>{@code merge} therefore has to be able to produce a named outcome, which a
     * {@link NodeKind#PASSTHROUGH} node cannot: {@code GraphRunner.runtimeNode} answers {@code continue}
     * for every non-{@code BEHAVIOR} kind, and an explicit {@code continue} is collapsed into
     * {@link GraphEdge#DEFAULT_OUTCOME} by {@code GraphEdge}'s canonical constructor, so it would be
     * indistinguishable from the bare edge. The behavior below forwards payload and attributes
     * untouched and differs from the passthrough it replaces in exactly one respect, the outcome — so
     * the fan-in payload this test reads is the same object it read before.</p>
     */
    private static GraphDefinition eachOnAnOrdinaryMerge() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("A", "A"),
                GraphNode.behavior("B", "B"),
                new GraphNode("merge", NodeKind.BEHAVIOR, "merge", Map.of("joinPolicy", "each")),
                GraphNode.error("error"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "A"),
                GraphEdge.to("start", "B"),
                GraphEdge.to("A", "merge"),
                GraphEdge.to("B", "merge"),
                new GraphEdge("merge", "error", ROUTED, Map.of())));
    }

    /**
     * The outcome the merges below name on their edge into the error terminal. Any non-{@code continue}
     * token would do; it is named once so the graph and the behavior cannot drift apart.
     */
    private static final String ROUTED = "routed";

    /**
     * A merge that forwards what it received under {@link #ROUTED}. The passthrough-with-an-outcome
     * that {@link NodeKind#PASSTHROUGH} cannot express — see {@link #eachOnAnOrdinaryMerge()}.
     */
    private static BehaviorRegistry withRoutingMerge(BehaviorRegistry registry) {
        return registry.register("merge", message -> CompletableFuture.completedFuture(
                new NodeResult(ROUTED, message.payload(), message.attributes())));
    }

    private static BehaviorRegistry twoFailingBranches() {
        return withRoutingMerge(new BehaviorRegistry()
                .register("first", message -> CompletableFuture.failedFuture(
                        new IllegalStateException("boom-1")))
                .register("second", message -> CompletableFuture.failedFuture(
                        new IllegalStateException("boom-2"))));
    }

    private static BehaviorRegistry twoDistinguishableLaps() {
        return withRoutingMerge(new BehaviorRegistry()
                .register("A", message -> CompletableFuture.completedFuture(NodeResult.continueWith("A-lap")))
                .register("B", message -> CompletableFuture.completedFuture(NodeResult.continueWith("B-lap"))));
    }

    /**
     * The counterexample to causal ordering, and what it costs — stated as the delta between two
     * event streams rather than as the absence of one counted event.
     *
     * <p>{@code joinPolicy=each} does not choose a different quorum — it removes the node from fan-in
     * detection altogether, so {@code JoinSpec.defaultQuorum} never runs for it, no coordinator is
     * created, and each arrival invokes the terminal on its own. Two failures therefore run the
     * terminal twice, concurrently, and both write the same field.</p>
     *
     * <p><strong>What is lost is the discard record, not the failure record.</strong> Counting one
     * event type on one node yields zero and incorrectly suggests that nothing records the losing
     * failure. The whole stream instead shows {@code NODE_FAILED} for {@code first} and
     * for {@code second}, with node, error class and message, in 200 runs out of 200 both with
     * {@code each} and without it. The entire difference between the two streams is two events,
     * {@code JOIN_SATISFIED} and {@code JOIN_ARRIVAL_DISCARDED}. So a reader reconstructing the run
     * from its events still sees both faults; what nothing tells them is that one of the two was set
     * aside on the way to the result.
     * {@link #bothFailuresAreRecordedUnderEachEvenThoughOnlyOneReachesTheResult()} verifies that both
     * faults remain visible in {@code NODE_FAILED} events.</p>
     *
     * <p>Measured over 200 traversals: two completions in 200 of 200, zero discards in 200 of 200,
     * payload {@code {second=184, first=16}}. Which failure is reported is not asserted, for the same
     * reason it is not asserted in {@code ErrorTerminalPayloadTest}: it is a race, and pinning either
     * answer would be pinning a coin toss.</p>
     *
     * <p>This is reachable from the product, not only from a hand-written graph:
     * {@code ravenroot-ui/src/graph-document.js} stamps {@code joinPolicy=each} on every
     * non-{@code START} node with more than one predecessor when serialising a legacy state-machine
     * document, and terminals are not excluded. Whether that should change, on either side, is an
     * documented decision and is registered rather than taken.</p>
     */
    @Test
    void eachOnATerminalReopensTwoRacingWritersAndLosesTheDiscardRecord() throws Exception {
        var payloads = new LinkedHashSet<String>();
        for (int run = 0; run < REPEATS; run++) {
            var monitor = new ExecutionMonitor();
            try (var manager = GraphManager.from(eachOnTheTerminal());
                 var runner = new GraphRunner(manager, engine, twoFailingBranches(), monitor)) {
                var result = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                payloads.add(String.valueOf(result.payload()));

                var events = monitor.eventsAfter(0);
                assertEquals(2, events.stream()
                                .filter(event -> event.type() == ExecutionEventType.NODE_COMPLETED
                                        && "error".equals(event.nodeId()))
                                .count(),
                        "under joinPolicy=each the terminal is invoked once per arrival, so two failing "
                                + "branches must run it twice; one completion would mean the property "
                                + "stopped bypassing fan-in detection and this test no longer covers "
                                + "the case it was written for");
                assertEquals(0, events.stream()
                                .filter(event -> event.type() == ExecutionEventType.JOIN_ARRIVAL_DISCARDED
                                        && "error".equals(event.nodeId()))
                                .count(),
                        "there is no coordinator here, so there is no discarded arrival to record. "
                                + "Both failures are still journalled as NODE_FAILED -- what is absent "
                                + "is any record that one of them was set aside on the way to the "
                                + "result. If this ever becomes non-zero the gap has been closed and "
                                + "the register's account of it needs revisiting");
            }
        }
        assertTrue(payloads.size() >= 1, () -> "sanity: something was reported, got " + payloads);
    }

    /**
     * The premise the whole argument reduces through, pinned here rather than assumed.
     *
     * <p>{@code GraphRunner.ExecutionState} keys what reached a terminal by terminal <em>kind</em> —
     * one field for {@code END}, one for {@code ERROR} — so "two writers of that field" and "that
     * terminal invoked twice" are the same statement <strong>only while a graph holds at most one
     * node of each kind</strong>. Every mechanism enumerated in that class's Javadoc is a way for one
     * node to be invoked twice; none of them covers two <em>different</em> error terminals being
     * written concurrently, which a fan-out with two failing branches would do immediately.</p>
     *
     * <h2>The required bound is "at most one"</h2>
     * <p>The reduction needs a <strong>ceiling</strong>: two writers of one field is
     * what breaks it. A <strong>floor</strong> was never doing any work — zero nodes of a kind leave
     * the field with no writer at all, which cannot create a writer that was not there — so dropping
     * it moves the count in the safe direction.</p>
     *
     * <p>The test fails if the ceiling is raised and also if a floor is imposed, so the
     * premise is pinned from both sides instead of one. The safe direction is asserted rather than
     * merely permitted, because "we removed a rule and nothing broke" is not evidence unless something
     * is watching. What that argument is worth at runtime is measured separately, in
     * {@code AbsentErrorTerminalTest}, which runs three topologies with and without an error terminal
     * and compares the payload outcome spaces.</p>
     *
     * <p>This is deliberately not a restatement of {@code ErrorTerminalStructureTest}'s validation
     * tests, which own the rule as a rule: this one owns it as a <em>dependency</em>, from the side
     * that would break silently. If the cardinality changes to one or more error terminals, this test
     * fails and points at the reduction that has to be revisited
     * before the runner's field keying changes.</p>
     */
    @Test
    void theAtMostOneTerminalPerKindPremiseTheRaceArgumentReducesThroughStillHolds() {
        var twoErrorTerminals = assertThrows(GraphValidationException.class, () -> new GraphDefinition(
                List.of(GraphNode.start("start"),
                        GraphNode.error("error-a"),
                        GraphNode.error("error-b"),
                        GraphNode.end("end")),
                List.of()),
                "a second ERROR node would give the error payload field two writers that no mechanism "
                        + "in GraphRunner's enumeration covers, because they are different nodes rather "
                        + "than one node entered twice");
        assertTrue(twoErrorTerminals.violations().stream().anyMatch(v -> v.contains("error node")),
                () -> "refused, but not for the cardinality of ERROR: " + twoErrorTerminals.violations());

        var twoEndTerminals = assertThrows(GraphValidationException.class, () -> new GraphDefinition(
                List.of(GraphNode.start("start"),
                        GraphNode.error("error"),
                        GraphNode.end("end-a"),
                        GraphNode.end("end-b")),
                List.of()),
                "and the same for END, whose field is keyed the same way");
        assertTrue(twoEndTerminals.violations().stream().anyMatch(v -> v.contains("end node")),
                () -> "refused, but not for the cardinality of END: " + twoEndTerminals.violations());

        // The other side of the premise: zero is accepted. The surplus assertions above pin only the
        // ceiling, so this independent assertion pins the zero floor required by "at most one".
        var noErrorTerminal = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "end")));
        assertTrue(noErrorTerminal.nodes().stream().noneMatch(node -> node.kind() == NodeKind.ERROR),
                "the graph pinning the floor must genuinely declare no error terminal");
    }

    /**
     * {@code start -> A|B}, both returning to {@code start} on their first invocation, {@code A}
     * routing onward to {@code error} afterwards. No node declares any property.
     *
     * <p>{@code START} therefore has two distinct predecessors, and {@code error} has exactly one, so
     * neither is a fan-in in the structural sense that would matter — and {@code JoinSpec} coordinates
     * neither: {@code START} because it is excluded unconditionally, {@code error} because one
     * predecessor is not a fan-in at all.</p>
     */
    private static GraphDefinition startReEntry() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("A", "A"),
                GraphNode.behavior("B", "B"),
                GraphNode.error("error"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "A"),
                GraphEdge.to("start", "B"),
                new GraphEdge("A", "start", "lap", Map.of()),
                new GraphEdge("B", "start", "lap", Map.of()),
                new GraphEdge("A", "error", "done", Map.of())));
    }

    /** Each behavior returns to {@code start} once, then routes onward, so the laps terminate. */
    private static BehaviorRegistry oneReturnTransitionEach() {
        var lapsA = new AtomicInteger();
        var lapsB = new AtomicInteger();
        return new BehaviorRegistry()
                .register("A", message -> {
                    int lap = lapsA.incrementAndGet();
                    return CompletableFuture.completedFuture(lap == 1
                            ? new NodeResult("lap", "lap-" + lap, Map.of())
                            : new NodeResult("done", "lap-" + lap, Map.of()));
                })
                .register("B", message -> {
                    int lap = lapsB.incrementAndGet();
                    return CompletableFuture.completedFuture(lap == 1
                            ? new NodeResult("lap", "b-" + lap, Map.of())
                            : new NodeResult("stop", "b-" + lap, Map.of()));
                });
    }

    /**
     * The counterexample that no condition about coordination can accommodate: a terminal entered
     * twice, concurrently, by a graph that <strong>configures nothing at all</strong>.
     *
     * <p>{@code JoinSpec.validate} excludes {@code START} from fan-in detection
     * <em>unconditionally</em>, before the predecessor count is consulted — deliberately, because
     * legacy state-machine graphs route back into their entry point and treating those edges as a
     * join would stall the traversal before {@code START} ran. The consequence is that a re-entered
     * {@code START} is a structural fan-in that no property can coordinate, and everything downstream
     * inherits the concurrency: here {@code A} is invoked once per re-entry and each invocation
     * dispatches to {@code error}.</p>
     *
     * <p>The first assertion is the load-bearing one and is why this test exists in this shape: the
     * graph's fan-in set is <strong>empty</strong>, so any guarantee phrased as "every fan-in on the
     * route is coordinated" is satisfied <em>vacuously</em> — and the terminal races anyway. Measured
     * over 50 traversals: two completions of {@code error} in 50 of 50, payload
     * {@code {lap-3=40, lap-2=10}}. Which lap wins is not asserted, for the usual reason.</p>
     */
    @Test
    void startReEntryRacesWithNoConfigurationAnywhere() throws Exception {
        var definition = startReEntry();
        assertTrue(JoinSpec.validate(definition).isEmpty(),
                () -> "this test's point is that the race needs no fan-in and no property; if the "
                        + "graph now has a coordinated fan-in it is measuring something else: "
                        + JoinSpec.validate(definition).keySet());

        var payloads = new LinkedHashSet<String>();
        for (int run = 0; run < REPEATS; run++) {
            var monitor = new ExecutionMonitor();
            try (var manager = GraphManager.from(definition);
                 var runner = new GraphRunner(manager, engine, oneReturnTransitionEach(), monitor)) {
                payloads.add(String.valueOf(runner.execute(TestIdentities.TENANT_A, "in")
                        .toCompletableFuture().get(10, TimeUnit.SECONDS).payload()));

                assertTrue(monitor.eventsAfter(0).stream()
                                .filter(event -> event.type() == ExecutionEventType.NODE_COMPLETED
                                        && "error".equals(event.nodeId()))
                                .count() > 1,
                        "the terminal must be entered more than once, with nothing configured "
                                + "anywhere: that is what makes a coordination-based guarantee "
                                + "vacuously true and wrong at the same time");
            }
        }
        assertTrue(!payloads.isEmpty(), () -> "sanity: something was reported, got " + payloads);
    }

    /**
     * The invariant that makes "nothing records the losing failure" false, pinned so the prose cannot
     * drift back to it.
     *
     * <p>Both faults are journalled under {@code each} exactly as they are without it: one
     * {@code NODE_FAILED} per failing node, carrying the node id and the failure detail. Only one of
     * them reaches the result payload, which is the real cost and is asserted next door. Written as
     * its own test because counting a single event type on a single node invites the wrong
     * generalisation, so the correction has to be an assertion
     * about the <em>whole</em> stream rather than a rephrasing.</p>
     */
    @Test
    void bothFailuresAreRecordedUnderEachEvenThoughOnlyOneReachesTheResult() throws Exception {
        for (int run = 0; run < REPEATS; run++) {
            var monitor = new ExecutionMonitor();
            try (var manager = GraphManager.from(eachOnTheTerminal());
                 var runner = new GraphRunner(manager, engine, twoFailingBranches(), monitor)) {
                var result = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);

                var failed = monitor.eventsAfter(0).stream()
                        .filter(event -> event.type() == ExecutionEventType.NODE_FAILED)
                        .map(event -> String.valueOf(event.nodeId()))
                        .sorted()
                        .toList();
                assertEquals(List.of("first", "second"), failed,
                        "both faults must be journalled under joinPolicy=each; only the result "
                                + "payload narrows to one of them, so the losing "
                                + "failure still leaves a trace");

                var reported = assertInstanceOf(NodeFailurePayload.class, result.payload());
                assertTrue(Set.of("first", "second").contains(reported.nodeId()),
                        () -> "the result reports one of the two, got " + reported.nodeId());
            }
        }
    }

    /**
     * {@code start -> first|second -(failure route)-> merge -(routed)-> error}, both branches failing,
     * with {@code each} on the merge only when {@code each} is set.
     *
     * <p>{@code merge} succeeds — it is the handler the two failure routes lead to — so its edge into
     * the error terminal must name an outcome for the same reason {@link #eachOnAnOrdinaryMerge()}'s
     * does. The two shapes sit side by side in this graph and are worth reading together: the
     * edges <em>into</em> {@code merge} are failure routes because the branches threw, and the edge
     * <em>out</em> of it is an ordinary outcome edge because {@code merge} did not.</p>
     */
    private static GraphDefinition fanOutWhereBothBranchesFail(boolean each) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("first", "first"),
                GraphNode.behavior("second", "second"),
                new GraphNode("merge", NodeKind.BEHAVIOR, "merge",
                        each ? Map.of("joinPolicy", "each") : Map.of()),
                GraphNode.error("error"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "first"),
                GraphEdge.to("start", "second"),
                new GraphEdge("first", "merge", null,
                        Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE)),
                new GraphEdge("second", "merge", null,
                        Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE)),
                new GraphEdge("merge", "error", ROUTED, Map.of())));
    }

    /**
     * The fan-out shape with two branches that genuinely <strong>fail</strong>, against the same shape
     * without {@code each} — and this is where the property costs more than a missing discard record.
     *
     * <p>Why it needed measuring rather than inferring: the existing fan-out counterexample uses two
     * branches that <em>succeed</em>, so it demonstrates a race over the payload and says nothing
     * about a fault being set aside. Carrying the "a failure is lost" conclusion across from the
     * terminal shape to the fan-out shape was an untested transfer, and the two shapes turn out not to
     * behave the same way.</p>
     *
     * <p><strong>Without</strong> {@code each}, {@code merge} is an ordinary quorum-of-all join, both
     * failures arrive, and the result payload is the fan-in's list carrying <em>both</em>
     * {@code NodeFailurePayload}s. <strong>With</strong> {@code each}, the merge fires per arrival, the
     * terminal is entered twice, and the result payload is a single failure, varying between runs. So
     * here the property does not merely drop the record that something was set aside: it drops content
     * that the coordinated form actually delivered. Both faults remain journalled either way.</p>
     *
     * <p><strong>Both halves are looped.</strong> A single coordinated run cannot distinguish a
     * deterministic result from a lucky one. Probe figures,
     * 200 traversals each, <strong>re-measured with default failure routing</strong> on the graph
     * {@link #fanOutWhereBothBranchesFail(boolean)} builds today: without {@code each}, both failures
     * in 200 of 200; with {@code each}, one failure, {@code {first=27, second=173}}. The
     * {@code {first=63, second=137}} figure belongs to the {@code PASSTHROUGH} merge that default
     * failure routing replaced. The stable half stayed stable and the racing half stayed a race — which is the point,
     * and is why the figure had to be retaken rather than assumed to carry over.</p>
     *
     * <p>Which single failure survives under {@code each} is deliberately not asserted — same coin
     * toss, same reason.</p>
     */
    @Test
    void theCoordinatedFanOutCarriesBothFailuresAndEachNarrowsItToOne() throws Exception {
        for (int run = 0; run < REPEATS; run++) {
            try (var manager = GraphManager.from(fanOutWhereBothBranchesFail(false));
                 var runner = new GraphRunner(manager, engine, twoFailingBranches(), new ExecutionMonitor())) {
                Object payload = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).payload();

                var merged = assertInstanceOf(List.class, payload,
                        "a coordinated fan-in delivers every arrival, so both failures must reach the "
                                + "terminal together rather than one of them being chosen");
                assertEquals(List.of("first", "second"), merged.stream()
                                .map(entry -> ((NodeFailurePayload) entry).nodeId())
                                .sorted()
                                .toList(),
                        "both failures, by node, on every run -- this half is the one asserting a "
                                + "stable outcome, so a single execution of it would prove nothing");
            }
        }

        var reportedUnderEach = new LinkedHashSet<String>();
        for (int run = 0; run < REPEATS; run++) {
            var monitor = new ExecutionMonitor();
            try (var manager = GraphManager.from(fanOutWhereBothBranchesFail(true));
                 var runner = new GraphRunner(manager, engine, twoFailingBranches(), monitor)) {
                Object payload = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).payload();

                var single = assertInstanceOf(NodeFailurePayload.class, payload,
                        "under each there is no fan-in to merge the arrivals, so the result carries one "
                                + "failure rather than the pair the coordinated form delivers");
                reportedUnderEach.add(single.nodeId());

                assertEquals(List.of("first", "second"), monitor.eventsAfter(0).stream()
                                .filter(event -> event.type() == ExecutionEventType.NODE_FAILED)
                                .map(event -> String.valueOf(event.nodeId()))
                                .sorted()
                                .toList(),
                        "both faults stay journalled; it is the result payload that narrows");
            }
        }
        assertTrue(Set.of("first", "second").containsAll(reportedUnderEach),
                () -> "the surviving failure must be one of the two, got " + reportedUnderEach);
    }

    /**
     * The same loss of ordering with the terminal left entirely alone: {@code each} sits on an
     * ordinary merge and the {@code ERROR} node has one predecessor and no properties.
     *
     * <p>Worth its own test because it defeats the obvious containment. Reading only the previous
     * test, "do not put {@code each} on a terminal" sounds like a sufficient rule; it is not. Any
     * {@code each} node upstream of a terminal invokes its successors once per arrival, and the
     * terminal inherits the concurrency without carrying anything unusual itself. No cycle is
     * involved — a plain fan-out is enough.</p>
     *
     * <p><strong>Measured over 200 traversals: two completions in 200 of 200, payload
     * {@code {B-lap=151, A-lap=49}}.</strong> Re-measured with default failure routing, on the graph
     * {@link #eachOnAnOrdinaryMerge()} actually builds today. The {@code {B-lap=155, A-lap=45}}
     * figure belongs to the {@code PASSTHROUGH} merge that the current default replaced, so
     * it describes a graph no longer in this file and is not evidence for the current measurement.</p>
     */
    @Test
    void aFanOutIntoAnEachMergeRacesOnAnUntouchedTerminal() throws Exception {
        var payloads = new LinkedHashSet<String>();
        for (int run = 0; run < REPEATS; run++) {
            var monitor = new ExecutionMonitor();
            try (var manager = GraphManager.from(eachOnAnOrdinaryMerge());
                 var runner = new GraphRunner(manager, engine, twoDistinguishableLaps(), monitor)) {
                var result = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                payloads.add(String.valueOf(result.payload()));

                assertEquals(2, monitor.eventsAfter(0).stream()
                                .filter(event -> event.type() == ExecutionEventType.NODE_COMPLETED
                                        && "error".equals(event.nodeId()))
                                .count(),
                        "the terminal is entered once per arrival at the each merge upstream of it, "
                                + "even though the terminal itself declares nothing");
                assertTrue(Set.of("A-lap", "B-lap").contains(String.valueOf(result.payload())),
                        () -> "the payload must come from one of the two laps, got " + result.payload());
            }
        }
        assertTrue(payloads.size() >= 1, () -> "sanity: something was reported, got " + payloads);
    }
}
