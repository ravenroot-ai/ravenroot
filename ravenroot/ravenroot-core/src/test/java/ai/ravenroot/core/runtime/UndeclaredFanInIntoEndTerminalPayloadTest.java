package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.JoinSemantics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the declared-join marker costs at the {@code END} terminal, pinned rather than left to be discovered.
 *
 * <h2>Observed behavior</h2>
 * <p>{@code JoinSemantics} carves {@code ERROR} out of the marker's effect and argues the case at
 * length. It does <strong>not</strong> carve out {@code END}. Measured here: in a marker-present document, two
 * branches converging on {@code END} with nothing declared make the traversal's result payload
 * <em>nondeterministic across identical runs</em>, and change its shape from the merged list a
 * coordinated fan-in produces to one branch's scalar.</p>
 *
 * <p>Probe measurements, 120 traversals of the same graph in each configuration (quoted to show how
 * the behaviour looks, not asserted — the same convention {@code CyclicTerminalPayloadTest} states
 * for its own figures):</p>
 * <pre>
 *   no marker                 {[PAYLOAD-B0, PAYLOAD-B1]=120}
 *   marker, undeclared END    {PAYLOAD-B1=85, PAYLOAD-B0=35}
 *   marker + joinPolicy=all   {[PAYLOAD-B0, PAYLOAD-B1]=120}
 * </pre>
 *
 * <p>The middle row is the only one that moves, and it moves per machine and per run: an independent
 * measurement of the same three configurations reported {@code {PAYLOAD-B0=38,
 * PAYLOAD-B1=82}} with the other two rows identical. Two runs disagreeing on the split and agreeing
 * on everything else isolates the effect, which is why the tests below assert the two stable rows exactly
 * and assert only the invariants of the middle one.</p>
 *
 * <h2>Why {@code END} could not get {@code ERROR}'s treatment</h2>
 * <p>The two look symmetrical and are not. {@code ERROR}'s implicit join is a quorum of
 * <em>one</em> — the current default — so keeping it under the marker is behaviour-<em>preserving</em>: a
 * quorum of one never waits, it fires on the first arrival, and the author observes nothing. {@code
 * END}'s inferred join is a quorum of <em>all</em>, and it merges. Keeping it would be exactly the
 * unrequested barrier the marker exists to remove, so the marker's rule applies to it and the
 * cost is real. That is a disclosure, not a defect: the escape hatch is one property, and this class
 * proves it still works.</p>
 *
 * <h2>What this falsifies upstream</h2>
 * <p>{@code GraphRunner.ExecutionState.endTerminalPayload} enumerates three mechanisms by which two
 * writers contend for that field and closes with a measured claim: that the three graphs it names
 * race with {@code joinPolicy=each} and do not without it. In a marker-present document that clause
 * is false — {@link #anUndeclaredFanInIntoEndRacesWithNoPropertyAnywhere()} reaches mechanism 1 with
 * no property anywhere, by default. This class supplies the measured marker-present instance.</p>
 *
 * <h2>Convention</h2>
 * <p>Asserted over {@link #REPEATS} runs, not one, for the reason the sibling race tests give: a
 * single green run cannot tell "deterministic" from "lucky this time". Where the outcome is a race
 * the assertions pin what is invariant and deliberately do not assert which payload wins.</p>
 */
class UndeclaredFanInIntoEndTerminalPayloadTest {

    private static final int REPEATS = 30;

    private static final Object MERGED = List.of("PAYLOAD-B0", "PAYLOAD-B1");

    private final JoinTestEngine engine = new JoinTestEngine();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    /** {@code start} fans out to {@code b0} and {@code b1}, both of which reach {@code end}. */
    private static GraphDefinition fanInToEnd(Map<String, Object> endProperties,
                                              Map<String, Object> graphProperties) {
        return new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.behavior("b0", "b0"),
                        GraphNode.behavior("b1", "b1"),
                        new GraphNode("end", ai.ravenroot.core.graph.NodeKind.END, null, endProperties)),
                List.of(GraphEdge.to("start", "b0"), GraphEdge.to("start", "b1"),
                        GraphEdge.to("b0", "end"), GraphEdge.to("b1", "end")),
                graphProperties);
    }

    private static Map<String, Object> marker() {
        return Map.of(JoinSemantics.MARKER_PROPERTY, JoinSemantics.DECLARED);
    }

    /**
     * The control: without the marker the inferred join
     * still merges both branches into one stable payload, run after run.
     */
    @Test
    void withoutTheMarkerTheInferredJoinStillMergesIntoOneStablePayload() throws Exception {
        var outcomes = distribution(fanInToEnd(Map.of(), Map.of()));
        assertEquals(Map.of(MERGED, REPEATS), outcomes,
                "the previous reading must be preserved exactly, payload shape included");
    }

    /**
     * With no property anywhere on the graph but the marker itself, the result payload
     * stops being a function of the drawing: two writers of {@code endTerminalPayload}, and whichever
     * lands last is reported.
     *
     * <p>Asserts what is invariant — the payload is one of the two branch scalars, and is never the
     * merged list a coordinated fan-in produces — rather than which one wins, which is the thing that
     * is genuinely not fixed.</p>
     */
    @Test
    void anUndeclaredFanInIntoEndRacesWithNoPropertyAnywhere() throws Exception {
        var outcomes = distribution(fanInToEnd(Map.of(), marker()));

        assertTrue(Set.of("PAYLOAD-B0", "PAYLOAD-B1").containsAll(outcomes.keySet()),
                "every observed payload must be one branch's own scalar, got " + outcomes);
        assertNotEquals(MERGED, outcomes.keySet().stream().findFirst().orElseThrow(),
                "an undeclared fan-in into END must not merge; if it does, the marker stopped "
                        + "applying to END and this class no longer measures anything");
        assertEquals(REPEATS, outcomes.values().stream().mapToInt(Integer::intValue).sum());
    }

    /**
     * One declared property restores the merged, stable payload exactly.
     */
    @Test
    void declaringTheJoinRestoresTheMergedStablePayload() throws Exception {
        var outcomes = distribution(
                fanInToEnd(Map.of(JoinSemantics.POLICY_PROPERTY, "all"), marker()));
        assertEquals(Map.of(MERGED, REPEATS), outcomes,
                "joinPolicy=all on END must produce exactly what the previous default produced");
    }

    /** How many distinct payloads {@link #REPEATS} identical traversals produced, and how often. */
    private Map<Object, Integer> distribution(GraphDefinition graph) throws Exception {
        var counts = new LinkedHashMap<Object, Integer>();
        for (int run = 0; run < REPEATS; run++) {
            counts.merge(payloadOf(graph), 1, Integer::sum);
        }
        return counts;
    }

    private Object payloadOf(GraphDefinition graph) throws Exception {
        var registry = new BehaviorRegistry();
        for (String node : List.of("b0", "b1")) {
            registry.register(node,
                    message -> CompletableFuture.completedFuture(
                            NodeResult.continueWith("PAYLOAD-" + node.toUpperCase(java.util.Locale.ROOT))));
        }
        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor())) {
            return runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).payload();
        }
    }
}
