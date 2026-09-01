package ai.ravenroot.core.runtime;

import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The specialised fan-in mini-graphs the CORE-03 evidence is built on.
 *
 * <p>Each one isolates exactly one property of a join, so a failure names the property rather than
 * "the big graph broke". They are shared between the semantics tests, the fault matrix and the
 * bound and race tests, so those three cannot drift onto subtly different topologies and disagree
 * about what they proved.</p>
 */
final class JoinMiniGraphs {

    private JoinMiniGraphs() {
    }

    /**
     * {@code start} fans out to {@code n} named branches which all feed {@code join}, then
     * {@code end}. The join carries whatever properties the caller configures.
     *
     * <p>Branch ids are {@code b0}, {@code b1}, ... so that branch-id ordering — which is what the
     * merged payload is sorted by — is also the natural reading order.</p>
     */
    static GraphDefinition fanIn(int branches, Map<String, Object> joinProperties) {
        var nodes = new ArrayList<GraphNode>();
        var edges = new ArrayList<GraphEdge>();
        nodes.add(GraphNode.start("start"));
        for (int index = 0; index < branches; index++) {
            String branch = "b" + index;
            nodes.add(GraphNode.behavior(branch, branch));
            edges.add(GraphEdge.to("start", branch));
            edges.add(GraphEdge.to(branch, "join"));
        }
        nodes.add(new GraphNode("join", NodeKind.PASSTHROUGH, null, joinProperties));
        nodes.add(GraphNode.error("error"));
        nodes.add(GraphNode.end("end"));
        edges.add(GraphEdge.to("join", "end"));
        return new GraphDefinition(nodes, edges);
    }

    /** {@link #fanIn(int, Map)} with no join properties, so the join defaults to {@code all}. */
    static GraphDefinition fanIn(int branches) {
        return fanIn(branches, Map.of());
    }

    static Map<String, Object> quorum(int quorum) {
        return Map.of(JoinSpec.QUORUM_PROPERTY, String.valueOf(quorum));
    }

    static Map<String, Object> quorumWithTimeout(int quorum, String isoTimeout) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put(JoinSpec.QUORUM_PROPERTY, String.valueOf(quorum));
        properties.put(JoinSpec.TIMEOUT_PROPERTY, isoTimeout);
        return properties;
    }

    /**
     * A decision node wired to one join by two different outcomes, plus one independent branch.
     *
     * <p>Only one of the decision's two edges can ever be taken, so the join can receive at most two
     * arrivals — from {@code decision} and from {@code other} — even though it has three incoming
     * edges. This is the topology that made the pre-CORE-03 expectation, which counted edges, demand
     * an arrival that could never come.</p>
     */
    static GraphDefinition doubleEdgedDecision(Map<String, Object> joinProperties) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("decision", "decision"),
                GraphNode.behavior("other", "other"),
                new GraphNode("join", NodeKind.PASSTHROUGH, null, joinProperties),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "decision"),
                GraphEdge.to("start", "other"),
                new GraphEdge("decision", "join", "accepted"),
                new GraphEdge("decision", "join", "rejected"),
                GraphEdge.to("other", "join"),
                GraphEdge.to("join", "end")));
    }

    /**
     * A decision whose two outcomes rejoin one hop further out, through a node each.
     *
     * <p>The difference from {@link #doubleEdgedDecision(Map)} is the whole point: there the two
     * outcome edges land on the join itself, so the join's distinct predecessors are
     * {@code decision} and {@code other} and both genuinely run. Here they land on {@code x} and
     * {@code y}, so the join's branches are {@code {x, y}} and the decision can only ever take one
     * of them. The other branch is never dispatched at all — it is not a late arrival, not a
     * timeout and not a failure, and before the untaken-branch rule existed the join simply waited
     * for it forever.</p>
     */
    static GraphDefinition rejoiningDecision(Map<String, Object> joinProperties) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("decision", "decision"),
                GraphNode.behavior("x", "x"),
                GraphNode.behavior("y", "y"),
                new GraphNode("join", NodeKind.PASSTHROUGH, null, joinProperties),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "decision"),
                new GraphEdge("decision", "x", "accepted"),
                new GraphEdge("decision", "y", "rejected"),
                GraphEdge.to("x", "join"),
                GraphEdge.to("y", "join"),
                GraphEdge.to("join", "end")));
    }

    /**
     * A decision whose <em>untaken</em> side carries a join of its own.
     *
     * <p>{@code d -[a]-> x -> end} is the live path. {@code d -[b]-> y} feeds {@code p} and
     * {@code q}, which are the two branches of {@code J2}. {@code J2} is well formed: on outcome
     * {@code b} both of its branches run and it is satisfied. On outcome {@code a} nothing on that
     * side runs at all, so {@code J2} is not an unsatisfiable join — it is a node that is not part
     * of this execution, exactly like {@code y}, {@code p} and {@code q}.</p>
     *
     * <p>The distinction the runtime has to make here is between a join that <em>waits</em> for a
     * branch that will never come, which is a traversal failure, and a join that is itself dead,
     * whose impossibility belongs to the sub-graph the decision did not select and is not a
     * traversal outcome at all.</p>
     */
    static GraphDefinition deadSideJoin(Map<String, Object> joinProperties) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("d", "d"),
                GraphNode.behavior("x", "x"),
                GraphNode.behavior("y", "y"),
                GraphNode.behavior("p", "p"),
                GraphNode.behavior("q", "q"),
                new GraphNode("J2", NodeKind.PASSTHROUGH, null, joinProperties),
                GraphNode.behavior("tail", "tail"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "d"),
                new GraphEdge("d", "x", "a"),
                new GraphEdge("d", "y", "b"),
                GraphEdge.to("x", "end"),
                GraphEdge.to("y", "p"),
                GraphEdge.to("y", "q"),
                GraphEdge.to("p", "J2"),
                GraphEdge.to("q", "J2"),
                GraphEdge.to("J2", "tail")));
    }

    /**
     * {@link #deadSideJoin(Map)} with the dead side reconverging onto the live one.
     *
     * <p>{@code J3} is a {@code quorum=1} join over {@code x} and {@code tail}, and {@code end} sits
     * behind it. The live branch alone satisfies it, so the traversal has a correct answer — which
     * is what makes this the sharper graph of the two: a runtime that mishandles the dead join here
     * does not merely fail, it can complete with the end node never run and no result at all.</p>
     */
    static GraphDefinition deadSideJoinReconverging(Map<String, Object> joinProperties) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("d", "d"),
                GraphNode.behavior("x", "x"),
                GraphNode.behavior("y", "y"),
                GraphNode.behavior("p", "p"),
                GraphNode.behavior("q", "q"),
                new GraphNode("J2", NodeKind.PASSTHROUGH, null, joinProperties),
                GraphNode.behavior("tail", "tail"),
                new GraphNode("J3", NodeKind.PASSTHROUGH, null, quorum(1)),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "d"),
                new GraphEdge("d", "x", "a"),
                new GraphEdge("d", "y", "b"),
                GraphEdge.to("x", "J3"),
                GraphEdge.to("y", "p"),
                GraphEdge.to("y", "q"),
                GraphEdge.to("p", "J2"),
                GraphEdge.to("q", "J2"),
                GraphEdge.to("J2", "tail"),
                GraphEdge.to("tail", "J3"),
                GraphEdge.to("J3", "end")));
    }

    /**
     * A failure two hops upstream of the join, so branch attribution cannot simply read the join's
     * immediate predecessor: {@code deep} feeds {@code slow-branch} which feeds the join.
     */
    static GraphDefinition deepBranch(Map<String, Object> joinProperties) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("deep", "deep"),
                GraphNode.behavior("b0", "b0"),
                GraphNode.behavior("b1", "b1"),
                new GraphNode("join", NodeKind.PASSTHROUGH, null, joinProperties),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "deep"),
                GraphEdge.to("start", "b1"),
                GraphEdge.to("deep", "b0"),
                GraphEdge.to("b0", "join"),
                GraphEdge.to("b1", "join"),
                GraphEdge.to("join", "end")));
    }
}
