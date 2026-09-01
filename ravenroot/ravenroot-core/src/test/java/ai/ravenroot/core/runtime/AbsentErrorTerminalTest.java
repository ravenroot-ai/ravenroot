package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.core.graph.FailureRouteEdgeProperty;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.api.execution.NodeResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A graph with <strong>no</strong> error terminal, and what that does to the deterministic result payload.
 *
 * <h2>The question, and why it had to be measured rather than argued</h2>
 * <p>{@code ERROR} was previously mandatory and is now optional, with a ceiling of one. So the
 * cardinality goes from "exactly one" to "zero or one", and the determinism argument in
 * {@code GraphRunner.ExecutionState.endTerminalPayload} reduces "two writers of this field" to "this
 * terminal was invoked twice" through a premise it names explicitly: that a graph holds
 * <em>exactly</em> one node of each terminal kind.</p>
 *
 * <p>The premise the reduction actually needs is <strong>at most</strong> one, not exactly one. Two
 * nodes of a kind give one field two writers that none of the three enumerated mechanisms covers —
 * that is the direction that breaks it, and it stays refused. Zero nodes of a kind give the field
 * <em>no</em> writer, which cannot introduce a writer that was not there. Because that conclusion
 * depends on the terminal-cardinality premise, the tests below measure it
 * instead of restating it.</p>
 *
 * <h2>What is measured here</h2>
 * <ul>
 *   <li>{@link #theErrorPayloadFieldHasNoWriterWhenNoErrorTerminalExists()} — the mechanical claim.
 *       A traversal that reaches neither terminal reports {@code null} on every run, with and without
 *       an error terminal in the document. A field with a writer nobody accounted for would surface
 *       as a non-null payload here.</li>
 *   <li>{@link #removingTheErrorTerminalDoesNotWidenTheResultPayloadOutcomeSpace()} — the claim that
 *       matters. Three topologies, each built twice, once with an error terminal and once without,
 *       each run {@link #REPEATS} times; the set of payloads observed is compared against a declared
 *       outcome space per topology. Determinism is asserted where the topology has one writer of
 *       {@code endTerminalPayload}. Where it has two, what is asserted is <em>the writer count
 *       itself</em> — per run, in both variants — and the outcome space; <strong>not</strong> that
 *       the race was observed to manifest, which is a property of the sample rather than of the
 *       graph. That is why the distinction is now drawn; see
 *       {@link #assertOutcomeSpace(String, java.util.function.Function, java.util.function.Supplier,
 *       Set, int)}.</li>
 * </ul>
 *
 * <p>The residual race that shows up in the second and third topologies is {@code END}'s own,
 * documented on {@code endTerminalPayload} and unchanged here: it belongs to
 * {@code joinPolicy=each} and to {@code START} re-entry, neither of which mentions the error
 * terminal. Its presence in <em>both</em> variants is the evidence that it is not something zero
 * error terminals introduced.</p>
 *
 * <h2>The measurement, at 200 runs per cell</h2>
 * <p>From a throwaway probe of the same three topologies, run at 200 traversals per variant rather
 * than the {@link #REPEATS} the assertions use. Quoted to show <em>how</em> each cell behaves; none
 * of these figures is asserted, for the reason {@code CyclicTerminalPayloadTest} states once for the
 * whole family — a distribution is evidence, an assertion on it would be pinning a coin toss.</p>
 * <pre>
 * coordinated fan-in at END   no error terminal  {[A-value, B-value]=200}   END completions {1=200}
 *                             with               {[A-value, B-value]=200}   END completions {1=200}
 * each at END                 no error terminal  {B-value=163, A-value=37}  END completions {2=200}
 *                             with               {B-value=173, A-value=27}  END completions {2=200}
 * START re-entry into END     no error terminal  {lap-3=159, lap-2=41}      END completions {2=200}
 *                             with               {lap-3=156, lap-2=44}      END completions {2=200}
 * </pre>
 *
 * <p><strong>The conclusion, stated plainly: dropping to zero is safe.</strong>
 * Cell by cell the two variants are indistinguishable — the deterministic topology is deterministic
 * in both at 200 of 200, and the two racy ones race in both, over the same two payloads, in
 * comparable proportions. Removing the error terminal neither introduced a writer nor widened an
 * existing race, which is what the reduction needed and is now measured rather than argued.</p>
 *
 * <p>{@code CyclicTerminalPayloadTest.theAtMostOneTerminalPerKindPremiseTheRaceArgumentReducesThroughStillHolds()}
 * owns the premise as a validation rule. This class owns it as a runtime measurement.</p>
 */
class AbsentErrorTerminalTest {

    /**
     * Runs per configuration. Thirty is what {@code CyclicTerminalPayloadTest} uses for the same
     * class of question, and both races quoted in the runner's Javadoc split well inside it — the
     * narrowest measured there was {@code {first=21, second=179}} in 200, which at thirty is not a
     * coin toss this many repeats would plausibly miss in one direction only.
     */
    private static final int REPEATS = 30;

    private final JoinTestEngine engine = new JoinTestEngine();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    /**
     * The one test in this class that exercises the minimum of zero:
     * a document with no error terminal is refused by {@code GraphDefinition.validate()}, so it never
     * reaches execution at all. A minimum of one reports
     * {@code A graph must contain exactly one error node, and none is declared}.
     *
     * <p>Goes through {@code GraphML} rather than building a {@link GraphDefinition} directly,
     * because the rule being lifted applied "everywhere a definition is materialised, loading
     * included" — so loading is where a leftover copy of it would still bite.</p>
     */
    @Test
    void aDocumentWithNoErrorTerminalLoadsAndExecutes() throws Exception {
        byte[] document = ("""
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                    <node id="start"><data key="kind">START</data></node>
                    <node id="work"><data key="kind">BEHAVIOR</data><data key="behavior">work</data></node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge id="e1" source="start" target="work"/>
                    <edge id="e2" source="work" target="end"/>
                  </graph>
                </graphml>
                """).getBytes(StandardCharsets.UTF_8);
        var registry = new BehaviorRegistry().register("work",
                message -> CompletableFuture.completedFuture(NodeResult.continueWith("worked")));

        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(document))) {
            assertEquals(Set.of(), manager.definition().nodes().stream()
                            .filter(node -> node.kind() == NodeKind.ERROR)
                            .map(GraphNode::id).collect(java.util.stream.Collectors.toSet()),
                    "the document under test must genuinely have no error terminal");

            try (var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor())) {
                var result = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                assertEquals("worked", result.payload());
                assertEquals(Set.of("start", "work", "end"), result.visitedNodes());
            }
        }
    }

    /**
     * The mechanical half of the measurement: with no error terminal, nothing writes the error
     * payload field.
     *
     * <p>The traversal below reaches neither declared terminal — {@code gate} routes onto
     * {@code aside}, a passthrough with no outgoing edges. Run in both variants: without an error
     * terminal, and with an unreached one. An unaccounted writer of the error field
     * to account for would show up as the two variants <em>disagreeing</em>, which is the assertion
     * below.</p>
     *
     * <h2>Why the payload is non-null without weakening the zero-terminal invariant</h2>
     * <p>A traversal reaching neither declared terminal still has the payload from the branch that
     * ran out of edges. Asserting {@code null} would discard {@code aside}'s payload merely because
     * the node it ended on was not called {@code END}. The third terminal rank
     * (docs/architecture/traversal-verdict.md, graft 3) is what fills it, so {@code null} is no
     * provides that payload.</p>
     *
     * <p>The zero-error-terminal property is untouched, and is asserted here in a stronger form
     * than {@code null} ever was. {@code null} was only <em>circumstantial</em> evidence that the
     * error field had no unaccounted writer: any absence of a value would have satisfied it,
     * including one caused by a bug elsewhere. Asserting instead that both variants produce
     * <em>the same</em> value, and that the value is the one the dangling branch itself produced,
     * says directly what matters — the presence or absence of an error terminal changes
     * nothing about what this graph reports, and the payload demonstrably came from the branch
     * rather than from either terminal field.</p>
     */
    @Test
    void theErrorPayloadFieldHasNoWriterWhenNoErrorTerminalExists() throws Exception {
        var observed = new LinkedHashSet<Object>();
        for (boolean withErrorTerminal : new boolean[] {false, true}) {
            for (int run = 0; run < REPEATS; run++) {
                var registry = new BehaviorRegistry().register("gate",
                        message -> CompletableFuture.completedFuture(new NodeResult("aside", "gated", Map.of())));
                try (var manager = GraphManager.from(neitherTerminalReached(withErrorTerminal));
                     var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor())) {
                    var result = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
                    observed.add(result.payload());
                    assertEquals("gated", result.payload(),
                            "the branch ran out of edges on `aside`, so the result is that branch's "
                                    + "own payload and never either terminal field's (error terminal "
                                    + "present: " + withErrorTerminal + "). A different value here "
                                    + "means a field is written outside the enumerated terminal "
                                    + "assignment sites");
                }
            }
        }
        assertEquals(Set.of("gated"), observed,
                "and the two variants must be indistinguishable: an error terminal that is never "
                        + "reached contributes no writer, which is the whole claim");
    }

    /**
     * The measurement is stated as an outcome space rather than as a
     * single expected value, because one of the three topologies genuinely races and pinning a winner
     * there would be pinning a coin toss.
     *
     * <p>Each topology is built twice — with an error terminal reachable only by a failure route that
     * never fires, and without one at all — and run {@link #REPEATS} times. The payloads observed must
     * fall inside the topology's declared outcome space, and that space is <strong>the same for both
     * variants</strong>. Where the space is a singleton, that is an assertion of determinism, held to
     * the declared value on every run of both variants. Where it is a pair, containment is all the
     * payloads can be asked for, and the claim that the two variants behave alike is carried by the
     * per-run writer count instead — for the reason set out on
     * {@link #assertOutcomeSpace(String, java.util.function.Function, java.util.function.Supplier,
     * Set, int)}.</p>
     *
     * <p>The third topology is the counterexample kept deliberately: {@code START} re-entry enters
     * {@code END} more than once with nothing configured anywhere, so {@code endTerminalPayload} has
     * two racing writers <em>in both variants</em>. That residual race is documented on
     * {@code GraphRunner.ExecutionState}, and its presence with zero error terminals is the point —
     * it is not something the absent terminal caused, and the absent terminal did not make it worse.
     * The re-entry count is asserted per run, so a topology that quietly stopped exercising the case
     * fails instead of passing vacuously.</p>
     */
    @Test
    void removingTheErrorTerminalDoesNotWidenTheResultPayloadOutcomeSpace() throws Exception {
        assertOutcomeSpace("coordinated fan-in at END", AbsentErrorTerminalTest::coordinatedFanIn,
                AbsentErrorTerminalTest::twoDistinguishableBranches, Set.of("[A-value, B-value]"), 1);
        assertOutcomeSpace("uncoordinated fan-in at END (joinPolicy=each)",
                AbsentErrorTerminalTest::eachAtEnd,
                AbsentErrorTerminalTest::twoDistinguishableBranches, Set.of("A-value", "B-value"), 2);
        assertOutcomeSpace("START re-entry", AbsentErrorTerminalTest::startReEntryIntoEnd,
                AbsentErrorTerminalTest::oneReturnTransitionEach, Set.of("lap-2", "lap-3"), 2);
    }

    /**
     * Runs one topology in both variants and reports the two observed sets together on failure, so a
     * divergence is read as a comparison rather than as one half of one.
     *
     * <h2>Why sample cardinality is not determinism</h2>
     * <p>Comparing the two variants on whether each produced a single sampled payload —
     * {@code assertEquals(observed.get(true).size() == 1, observed.get(false).size() == 1)}, failing
     * with "one variant is deterministic and the other is not". <strong>That sentence is not a
     * statement the sample can support.</strong> {@code size() == 1} over {@link #REPEATS} runs says
     * the minority payload did not turn up in thirty draws; determinism says it cannot turn up at
     * all. That comparison would read the first and report the second, and on a topology that races at
     * roughly one draw in six, thirty draws miss the minority often enough to matter.</p>
     *
     * <p><strong>What settles it is not the arithmetic but the direction of the failures.</strong>
     * Reproduced twice in forty isolated runs under CPU load, the same assertion failed <em>both
     * ways</em> on the same topology: once with {@code [B-value]} for the variant without the error
     * terminal against {@code [B-value, A-value]} for the variant with it, and once with those two
     * exchanged. No property of a graph can make the error-terminal-free variant the deterministic
     * one in one run and the deterministic one the other in the next. Only sampling does that. A
     * 4800-traversal probe agrees from the other side: in every cell, at every load level tried,
     * both variants produced both payloads — neither is ever deterministic in the population.</p>
     *
     * <p><strong>The guard is not dropped, it is moved to where it is a fact rather than a draw.</strong>
     * The question is whether removing the error terminal adds or removes a writer
     * of {@code endTerminalPayload}. {@code GraphRunner} writes that field on exactly the END
     * completion it reports to the monitor, so <em>the END completion count is the writer count</em>,
     * and that count is already asserted here on every run of both variants — deterministically, with
     * no distribution anywhere near it. If the absent terminal changed the number of writers, that
     * assertion fails outright rather than one time in twelve.</p>
     *
     * <p>What is genuinely given up is the other half: that a racy topology is still <em>observed</em>
     * to race with the terminal removed. That is a property of a distribution, and this family already
     * has a rule for those — {@code CyclicTerminalPayloadTest} states it once for all of them, and the
     * 200-run figures in this class's own Javadoc are its instrument here. The removed assertion was
     * that rule's only violation, one level of indirection down: it asserted not a figure but a
     * boolean derived from one, which is the same coin toss wearing a different type.</p>
     *
     * @param endEntries how many times {@code END} must complete per run — the structural fact that
     *                   makes the topology the case it claims to be, and, because one completion is
     *                   one write, the number of writers of {@code endTerminalPayload}
     */
    private void assertOutcomeSpace(String topology,
                                    java.util.function.Function<Boolean, GraphDefinition> graph,
                                    java.util.function.Supplier<BehaviorRegistry> behaviors,
                                    Set<String> outcomeSpace,
                                    int endEntries) throws Exception {
        var observed = new java.util.LinkedHashMap<Boolean, Set<String>>();
        for (boolean withErrorTerminal : new boolean[] {false, true}) {
            var payloads = new LinkedHashSet<String>();
            for (int run = 0; run < REPEATS; run++) {
                var monitor = new ExecutionMonitor();
                try (var manager = GraphManager.from(graph.apply(withErrorTerminal));
                     var runner = new GraphRunner(manager, engine, behaviors.get(), monitor)) {
                    payloads.add(String.valueOf(runner.execute(TestIdentities.TENANT_A, "in")
                            .toCompletableFuture().get(10, TimeUnit.SECONDS).payload()));
                    assertEquals(endEntries, monitor.eventsAfter(0).stream()
                                    .filter(event -> event.type() == ExecutionEventType.NODE_COMPLETED
                                            && "end".equals(event.nodeId()))
                                    .count(),
                            topology + " (error terminal: " + withErrorTerminal + ") no longer enters "
                                    + "END the number of times that makes it the case under test, so "
                                    + "this measurement is about a different graph than it claims");
                }
            }
            observed.put(withErrorTerminal, payloads);
        }
        for (boolean withErrorTerminal : new boolean[] {false, true}) {
            assertTrue(outcomeSpace.containsAll(observed.get(withErrorTerminal)),
                    () -> topology + ": a payload outside the declared outcome space " + outcomeSpace
                            + ". Without the error terminal: " + observed.get(false)
                            + "; with it: " + observed.get(true)
                            + ". Either the topology changed or the result payload has a writer "
                            + "outside the enumerated assignment sites");
        }
        // The declared outcome space and the declared writer count are two statements of one
        // fact, so a topology may not change one and keep the other. This has no teeth against the
        // engine -- both sides are the test's own constants -- and it is here only to catch the edit
        // that widens a space and forgets the count, which would make the assertion below vacuous.
        assertEquals(endEntries == 1, outcomeSpace.size() == 1,
                topology + ": enters END " + endEntries + " times but declares an outcome space of "
                        + outcomeSpace.size() + ". One END completion is one write of "
                        + "endTerminalPayload, so one entry is one writer, which is the whole of what "
                        + "makes the payload a function of the graph");
        if (outcomeSpace.size() == 1) {
            // The determinism half of the guard, and the half a sample can carry: one writer means
            // one payload, on every run, in BOTH variants. It fails only if the topology genuinely
            // races -- measured at zero minority payloads in 4800 traversals -- never because thirty
            // draws happened to agree. The racy topologies deliberately get no counterpart here: see
            // the Javadoc above for what carries their half instead.
            for (boolean withErrorTerminal : new boolean[] {false, true}) {
                assertEquals(outcomeSpace, observed.get(withErrorTerminal),
                        topology + " (error terminal: " + withErrorTerminal + "): a topology with a "
                                + "single writer of endTerminalPayload did not report the declared "
                                + "payload on all " + REPEATS + " runs. Without the error terminal: "
                                + observed.get(false) + "; with it: " + observed.get(true));
            }
        }
    }

    /**
     * {@code start -> gate -(aside)-> aside}, plus an {@code END} that {@code gate} reaches only on
     * the {@code continue} outcome it never produces. Neither terminal runs.
     */
    private static GraphDefinition neitherTerminalReached(boolean withErrorTerminal) {
        var nodes = new ArrayList<>(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("gate", "gate"),
                GraphNode.passthrough("aside"),
                GraphNode.end("end")));
        var edges = new ArrayList<>(List.of(
                GraphEdge.to("start", "gate"),
                GraphEdge.to("gate", "end"),
                new GraphEdge("gate", "aside", "aside", Map.of())));
        if (withErrorTerminal) {
            nodes.add(GraphNode.error("error"));
            edges.add(deadFailureRoute("gate"));
        }
        return new GraphDefinition(nodes, edges);
    }

    /** {@code start -> A|B -> end}, with {@code END} left to the default fan-in coordination. */
    private static GraphDefinition coordinatedFanIn(boolean withErrorTerminal) {
        return fanOut(withErrorTerminal, Map.<String, Object>of());
    }

    /**
     * The same fan-out with {@code joinPolicy=each} on {@code END}, which removes it from fan-in
     * detection so each arrival invokes it on its own — the first of the three race mechanisms the
     * runner enumerates, and one that names no error terminal.
     */
    private static GraphDefinition eachAtEnd(boolean withErrorTerminal) {
        return fanOut(withErrorTerminal, Map.<String, Object>of("joinPolicy", "each"));
    }

    private static GraphDefinition fanOut(boolean withErrorTerminal, Map<String, Object> endProperties) {
        var nodes = new ArrayList<>(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("A", "A"),
                GraphNode.behavior("B", "B"),
                new GraphNode("end", NodeKind.END, null, endProperties)));
        var edges = new ArrayList<>(List.of(
                GraphEdge.to("start", "A"),
                GraphEdge.to("start", "B"),
                GraphEdge.to("A", "end"),
                GraphEdge.to("B", "end")));
        if (withErrorTerminal) {
            nodes.add(GraphNode.error("error"));
            edges.add(deadFailureRoute("A"));
        }
        return new GraphDefinition(nodes, edges);
    }

    /**
     * {@code CyclicTerminalPayloadTest.startReEntry()} with {@code A}'s onward edge pointed at
     * {@code END} instead of the error terminal, so the same uncoordinated re-entry races on the
     * field this variant actually has.
     */
    private static GraphDefinition startReEntryIntoEnd(boolean withErrorTerminal) {
        var nodes = new ArrayList<>(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("A", "A"),
                GraphNode.behavior("B", "B"),
                GraphNode.end("end")));
        var edges = new ArrayList<>(List.of(
                GraphEdge.to("start", "A"),
                GraphEdge.to("start", "B"),
                new GraphEdge("A", "start", "lap", Map.of()),
                new GraphEdge("B", "start", "lap", Map.of()),
                new GraphEdge("A", "end", "done", Map.of())));
        if (withErrorTerminal) {
            nodes.add(GraphNode.error("error"));
            edges.add(deadFailureRoute("B"));
        }
        return new GraphDefinition(nodes, edges);
    }

    /**
     * A failure route from a node that never fails: it wires the error terminal into the graph
     * without changing what the traversal does, so the two variants of each topology differ in the
     * terminal's presence and in nothing else.
     */
    private static GraphEdge deadFailureRoute(String source) {
        return new GraphEdge(source, "error", null,
                Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE));
    }

    private static BehaviorRegistry twoDistinguishableBranches() {
        return new BehaviorRegistry()
                .register("A", message -> CompletableFuture.completedFuture(NodeResult.continueWith("A-value")))
                .register("B", message -> CompletableFuture.completedFuture(NodeResult.continueWith("B-value")));
    }

    /** @see CyclicTerminalPayloadTest */
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
}
