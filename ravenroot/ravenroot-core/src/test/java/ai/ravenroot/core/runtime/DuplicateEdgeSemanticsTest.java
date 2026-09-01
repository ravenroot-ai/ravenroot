package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Duplicated GraphML edges are not multiplicity (ADR 0024 §4).
 *
 * <h2>Why this exists before any multiplicity does</h2>
 * <p>Data-parallel fan-out means one upstream result creates N child invocations of the
 * same logical successor. Until it lands, "two edges from A to B" means one delivery to B, and it must
 * go on meaning that afterwards — ADR 0024 rejects duplicated edges as the authoring mechanism for
 * multiplicity by name, because a duplicated edge loses child identity and makes a downstream join's
 * cardinality unknowable.
 *
 * <p>That property holds today, and <b>held by accident</b>. Nothing asserted it: it fell out of three
 * independent data-structure choices in three different places, none of which mentions the others, and
 * no test anywhere built a graph containing a duplicated edge. Any multiplicity implementation must
 * edit all three paths. A
 * property with no test, sitting directly under code that can rewrite it, is the shape that
 * regresses without anyone noticing — so it is pinned here first, while there is still no multiplicity
 * in the runtime to confuse what a second delivery would mean.
 *
 * <h2>Three cases because there are three mechanisms</h2>
 * <p>Each case fails against a different mutation, and each was checked separately rather than
 * assumed. They are not redundant:
 *
 * <ul>
 *   <li>{@link #aDuplicatedEdgeDeliversOnce()} — {@code GraphRunner.targetDeliveries} collapses edges
 *       into a map keyed by target. Without it, B is invoked twice.</li>
 *   <li>{@link #aDuplicatedEdgeDoesNotMakeItsTargetAFanIn()} — {@code JoinSpec.distinctPredecessors}
 *       collects into a {@code TreeSet}. Without it, a node with one real predecessor and a duplicated
 *       edge is classified as a two-branch join, and both branches carry the same branch id, so the
 *       second arrival is discarded as a duplicate and the quorum can never be met.</li>
 *   <li>{@link #aDuplicatedEdgeDoesNotInflateTheLivenessCount()} — {@code
 *       GraphRunner.precomputeDistinctPredecessorCount} collects into a {@code LinkedHashSet}. Without
 *       it, a node needs two resolutions to be proven dead when only one predecessor can ever resolve,
 *       so a branch that is genuinely dead is never reported and its join waits for it forever.</li>
 * </ul>
 */
class DuplicateEdgeSemanticsTest {

    private static final Duration BOUND = Duration.ofSeconds(10);
    private static final Duration NEVER_HANGS = Duration.ofSeconds(30);

    /** Two identical edges are one route: the target runs once, not twice. */
    @Test
    void aDuplicatedEdgeDeliversOnce() throws Exception {
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.passthrough("work"), GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "work"),
                        GraphEdge.to("start", "work"),
                        GraphEdge.to("work", "end")));

        List<String> started = run(graph);

        assertEquals(1, started.stream().filter("work"::equals).count(),
                "two identical edges are one route, not two invocations. ADR 0024 rejects duplicated "
                        + "edges as the way to express multiplicity precisely because a second "
                        + "delivery here would carry no child identity. Observed: " + started);
    }

    /**
     * A duplicated edge must not turn its target into a fan-in.
     *
     * <p>The failure this guards against is not a wrong answer but a hang, which is why the assertion
     * is wrapped rather than merely timed: a node wrongly classified as a two-branch join receives two
     * arrivals that share one branch id, the second is discarded as a duplicate, and the quorum is
     * unreachable for the life of the process.
     */
    @Test
    void aDuplicatedEdgeDoesNotMakeItsTargetAFanIn() {
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.passthrough("converge"), GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "converge"),
                        GraphEdge.to("start", "converge"),
                        GraphEdge.to("converge", "end")));

        assertTimeoutPreemptively(NEVER_HANGS, () -> {
            List<String> started = run(graph);
            assertEquals(1, started.stream().filter("converge"::equals).count(),
                    "a node with one real predecessor is not a fan-in however many edges connect "
                            + "them. Observed: " + started);
            assertEquals(1, started.stream().filter("end"::equals).count(),
                    "the traversal must reach its end rather than park at a fabricated join");
        });
    }

    /**
     * A duplicated edge must not inflate the count that proves a node dead.
     *
     * <p>{@code decide} routes to {@code taken} and declines {@code declined}, which is reached only
     * from {@code decide} — so {@code declined} is dead, and the join downstream must be told. The
     * liveness count for {@code declined} is seeded from its distinct predecessors; a duplicated edge
     * that inflated it to two would leave one resolution outstanding forever, the report would never
     * be emitted, and the join would wait on a branch that can never arrive.
     *
     * <p>So the correct outcome here is a prompt <em>failure</em> — the join learns it is impossible —
     * and the defect's signature is a hang instead. Asserting the failure is what distinguishes them;
     * asserting merely "not success" would pass on both.
     */
    @Test
    void aDuplicatedEdgeDoesNotInflateTheLivenessCount() {
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.passthrough("decide"),
                        GraphNode.passthrough("taken"), GraphNode.passthrough("declined"),
                        GraphNode.passthrough("join"), GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "decide"),
                        GraphEdge.to("decide", "taken"),
                        // Declined on every run, and duplicated: one real predecessor, two edges.
                        new GraphEdge("decide", "declined", "never"),
                        new GraphEdge("decide", "declined", "never"),
                        GraphEdge.to("taken", "join"),
                        GraphEdge.to("declined", "join"),
                        GraphEdge.to("join", "end")));

        assertTimeoutPreemptively(NEVER_HANGS, () -> {
            var failure = assertThrows(ExecutionException.class, () -> run(graph));
            assertInstanceOf(JoinFailureException.class, rootCause(failure),
                    "the declined branch is dead and its join must be told promptly. A hang here means "
                            + "the duplicated edge inflated the liveness count, leaving a resolution "
                            + "outstanding that nothing can ever supply. Got: " + rootCause(failure));
        });
    }

    // ------------------------------------------------------------------ fixtures

    private static List<String> run(GraphDefinition graph) throws Exception {
        var engine = new JoinTestEngine();
        var monitor = new ExecutionMonitor();
        var started = new CopyOnWriteArrayList<String>();
        try (var subscription = monitor.subscribe(event -> {
                 if (event.type() == ExecutionEventType.NODE_STARTED) {
                     started.add(event.nodeId());
                 }
             });
             var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, new BehaviorRegistry(), monitor,
                     ExecutionIdentitySource.randomUuids(), BOUND)) {
            runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture()
                    .get(20, TimeUnit.SECONDS);
            return List.copyOf(started);
        } finally {
            engine.close();
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
