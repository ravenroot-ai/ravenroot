package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.JoinSemantics;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime half: what a fan-in actually does under each semantics version.
 *
 * <p>Every assertion here counts <em>invocations of the merge node</em> rather than inspecting a
 * {@code JoinSpec} map, because that is the thing the author observes and the thing a mutant has to
 * get wrong. A join coordinates: the merge runs once. No join: the merge runs once per arrival. The
 * two are one integer apart, and the integer is produced by the real runner over the real
 * coordinator.</p>
 *
 * <p>The three tests are deliberately the same topology with one difference each, so a failure names
 * the difference. Restoring implicit inference in a marker-present document reds
 * {@link #anUndeclaredFanInUnderTheMarkerRunsOncePerArrival}; making declared-only unconditional reds
 * {@link #aMarkerAbsentDocumentKeepsTodaysInferredJoinExactly}; losing declared joins altogether reds
 * {@link #aDeclaredAllJoinStillCoordinates}. No single mutation can make all three pass.</p>
 */
class ExplicitJoinSemanticsTest {

    private final JoinTestEngine engine = new JoinTestEngine();
    private final ExecutionMonitor monitor = new ExecutionMonitor();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    /**
     * {@code start} fans out to {@code b0} and {@code b1}, both of which feed {@code merge}, which
     * reaches {@code end}. {@code merge} is a behavior so its invocations can be counted.
     */
    private static GraphDefinition fanIn(Map<String, Object> mergeProperties,
                                         Map<String, Object> graphProperties) {
        var nodes = new ArrayList<GraphNode>(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("b0", "b0"),
                GraphNode.behavior("b1", "b1"),
                new GraphNode("merge", NodeKind.BEHAVIOR, "merge", mergeProperties),
                GraphNode.end("end")));
        var edges = List.of(
                GraphEdge.to("start", "b0"), GraphEdge.to("start", "b1"),
                GraphEdge.to("b0", "merge"), GraphEdge.to("b1", "merge"),
                GraphEdge.to("merge", "end"));
        return new GraphDefinition(nodes, edges, graphProperties);
    }

    private static Map<String, Object> declared() {
        return Map.of(JoinSemantics.MARKER_PROPERTY, JoinSemantics.DECLARED);
    }

    // ------------------------------------------------------------------------ the inverted default

    /**
     * Two edges into {@code merge} with no declared join are two ways to
     * reach the node, not a barrier. A mutant that restores implicit inference makes this one run.
     */
    @Test
    void anUndeclaredFanInUnderTheMarkerRunsOncePerArrival() throws Exception {
        var merges = new AtomicInteger();
        run(fanIn(Map.of(), declared()), merges);
        assertEquals(2, merges.get(),
                "an undeclared multi-predecessor node in a document declaring "
                        + JoinSemantics.MARKER_PROPERTY + "=" + JoinSemantics.DECLARED
                        + " must run once per arrival; running once means a join was inferred");
    }

    /** The escape hatch is now the declaration, and it still does exactly what it did. */
    @Test
    void aDeclaredAllJoinStillCoordinates() throws Exception {
        var merges = new AtomicInteger();
        run(fanIn(Map.of(JoinSemantics.POLICY_PROPERTY, "all"), declared()), merges);
        assertEquals(1, merges.get(),
                "a declared join must still merge its branches into one invocation");
    }

    /**
     * The other direction, and the one that protects every graph already recorded: with no marker the
     * same drawing keeps the inferred join, unchanged. A mutant that applies declared-only semantics
     * unconditionally reds here.
     */
    @Test
    void aMarkerAbsentDocumentKeepsTodaysInferredJoinExactly() throws Exception {
        var merges = new AtomicInteger();
        run(fanIn(Map.of(), Map.of()), merges);
        assertEquals(1, merges.get(),
                "without the marker a fan-in is still inferred as a join, or an existing graph "
                        + "silently changed behaviour");
    }

    /** {@code joinQuorum} and {@code joinTimeout} declare a join just as {@code joinPolicy} does. */
    @Test
    void aQuorumAloneDeclaresAJoinUnderTheMarker() throws Exception {
        var merges = new AtomicInteger();
        run(fanIn(Map.of(JoinSemantics.QUORUM_PROPERTY, "2"), declared()), merges);
        assertEquals(1, merges.get());
    }

    /** Legal, redundant, and behaviourally identical to declaring nothing. */
    @Test
    void eachUnderTheMarkerIsAcceptedAndBehavesAsAbsenceDoes() throws Exception {
        var merges = new AtomicInteger();
        run(fanIn(Map.of(JoinSemantics.POLICY_PROPERTY, JoinSemantics.EACH_POLICY), declared()), merges);
        assertEquals(2, merges.get());
    }

    // ----------------------------------------------------------- the exclusion that is not authored

    /**
     * The decision this implementation took beyond the ruling, pinned so it is visible rather than
     * inferred from behaviour. Read literally, "an undeclared multi-predecessor node is not a join"
     * would strip the error terminal of its default quorum of one, and that
     * quorum is not a barrier an author drew: it is what stops
     * {@code ExecutionState.errorTerminalPayload} — one field for the whole graph, keyed by terminal
     * kind — from having two concurrent writers. The migration rule writes {@code joinQuorum=1} onto
     * that terminal for exactly this reason, so a document drawn from scratch must not lose what a
     * migrated one keeps.
     */
    @Test
    void theErrorTerminalKeepsItsImplicitQuorumOfOneUnderTheMarker() {
        var definition = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.behavior("b0", "b0"),
                        GraphNode.behavior("b1", "b1"), GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "b0"), GraphEdge.to("start", "b1"),
                        GraphEdge.to("b0", "end"), GraphEdge.to("b1", "end"),
                        new GraphEdge("b0", "error", "failed"), new GraphEdge("b1", "error", "failed")),
                declared());

        var specs = JoinSpec.validate(definition);
        assertTrue(specs.containsKey("error"),
                "the error terminal must stay coordinated in a marker-present document");
        assertEquals(1, specs.get("error").quorum());
        assertFalse(specs.containsKey("end"),
                "an ordinary terminal with two undeclared predecessors is not a join under the marker");
    }

    /** {@code START} is excluded in both versions, as it always was. */
    @Test
    void startIsNeverAJoinInEitherVersion() {
        var edges = List.of(GraphEdge.to("start", "worker"), new GraphEdge("worker", "start", "again"),
                GraphEdge.to("worker", "end"));
        var nodes = List.of(GraphNode.start("start"), GraphNode.behavior("worker", "worker"),
                GraphNode.end("end"));
        assertFalse(JoinSpec.validate(new GraphDefinition(nodes, edges)).containsKey("start"));
        assertFalse(JoinSpec.validate(new GraphDefinition(nodes, edges, declared())).containsKey("start"));
    }

    // ------------------------------------------------------------------------------------- harness

    private void run(GraphDefinition graph, AtomicInteger merges) throws Exception {
        var registry = new BehaviorRegistry();
        for (String node : List.of("b0", "b1")) {
            registry.register(node, message -> CompletableFuture.completedFuture(NodeResult.continueWith(node)));
        }
        registry.register("merge", message -> {
            merges.incrementAndGet();
            return CompletableFuture.completedFuture(NodeResult.continueWith("merged"));
        });
        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry, monitor)) {
            runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }
}
