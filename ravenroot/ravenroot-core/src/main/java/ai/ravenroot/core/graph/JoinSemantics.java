package ai.ravenroot.core.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Whether a node is a synchronisation point, and which semantics version answered.
 *
 * <h2>The defect this exists to close</h2>
 * <p>A node became a join <em>because of how the graph was drawn</em>. Two or more distinct
 * predecessors was the whole test, so an author who drew a node looping back to itself to express
 * repetition made that node its own second predecessor: a join over {@code {previous, itself}}, and
 * it waited for itself. Nothing ran, nothing failed, no instance was created and no error was
 * raised, because from the runtime's point of view a join was waiting, which is what joins do.</p>
 *
 * <h2>Versioned by a graph-level marker, not by rewriting documents</h2>
 * <p>Inverting that default silently changes what every already-recorded fan-in does — from
 * <em>wait for both</em> to <em>run twice</em> — so the default is versioned per document rather
 * than flipped globally. The version lives in one well-known unreserved graph-level property,
 * {@value #MARKER_PROPERTY}, and the two readings are exact:</p>
 * <ul>
 *   <li><strong>Marker present</strong> ({@value #DECLARED}): a multi-predecessor node with no join
 *       property authored on it is <em>not</em> a join. Each arrival invokes it independently.
 *       Drawing two edges into a node means two ways to reach it, not a barrier.</li>
 *   <li><strong>Marker absent</strong>: inferred-join semantics remains unchanged. Meaning is
 *       preserved with zero diff — no stored byte is
 *       rewritten and no hash is re-addressed, because the encoding this marker adds to
 *       {@link GraphCanonicalForm} is emitted only when the marker is there.</li>
 * </ul>
 *
 * <p>The declaration itself needs no new vocabulary: {@code joinPolicy}, {@code joinQuorum} and
 * {@code joinTimeout} already exist and already say everything a join needs to say. A join exists
 * where the author wrote one of them.</p>
 *
 * <h2>Two exclusions that are not authored, and why the second one is a decision</h2>
 * <p>{@code START} is excluded in both semantics versions, unconditionally, before the predecessor
 * count is consulted: its first invocation is deliberately external, and a legacy state-machine
 * graph with feedback transitions into its entry point would otherwise stall before {@code START}
 * ever ran. That exclusion predates this class.</p>
 *
 * <p><strong>{@code ERROR} is excluded from the marker's effect, and that is a decision taken here
 * rather than one the ruling stated.</strong> Read literally, "an undeclared multi-predecessor node
 * is not a join" would remove the error terminal's coordination in every marker-present document
 * with two or more fallible nodes. That coordination is not a barrier an author drew: it is a
 * quorum of <em>one</em> whose only job is to keep
 * {@code GraphRunner.ExecutionState.errorTerminalPayload} — a field keyed by terminal
 * <em>kind</em>, so one field for the whole graph — from having two concurrent writers. Removing it
 * reinstates exactly the race that {@code joinPolicy=each} on a terminal was measured to produce:
 * two completions in 200 runs of 200, and a reported failure that varies between identical runs.</p>
 *
 * <p>The migration rule is what settles it. Migrating a legacy document materialises
 * {@code joinQuorum=1} on the error terminal — never {@code each} — precisely to preserve that
 * single firing. It would be incoherent to preserve it for a migrated document and lose it for a
 * document drawn from scratch tomorrow, so {@code ERROR} keeps its implicit quorum of one in both
 * versions. This costs the author nothing they can observe: a quorum of one never waits, it fires
 * on the first arrival, and the surplus arrival is recorded as {@code JOIN_ARRIVAL_DISCARDED}
 * rather than dropped in silence. An author who wants the terminal to wait for every fallible
 * branch still writes {@code joinPolicy=all} on it and gets exactly that.</p>
 *
 * <h2>What is refused, and the difference between statically and dynamically unsatisfiable</h2>
 * <p>{@link GraphDefinition} refuses two structures at load, in <em>every</em> semantics version,
 * without waiting for any document to migrate. Both are <em>statically</em> unsatisfiable — no
 * input, no schedule and no amount of waiting can make them proceed — which is what makes the
 * refusal safe by construction: a graph that depended on either never completed, so no working
 * recorded graph can be broken by naming it.</p>
 * <ol>
 *   <li><strong>A join whose branch set includes itself.</strong> It waits for an
 *       arrival that can only come from its own completion.</li>
 *   <li><strong>A self-loop with no other outgoing edge.</strong> Repetition with no way out is an
 *       infinite loop by construction; there is no schedule under which the traversal terminates.</li>
 * </ol>
 *
 * <p>A join that is merely <em>dynamically</em> starved — legitimately waiting while no live
 * instance can ever arrive — is not refused here and is not this class's business. Dynamic starvation
 * is a runtime liveness concern, and the two are separated by mechanism on purpose: one is decidable from the drawing alone before
 * anything runs, the other is a property of a run in progress. Folding them would either refuse
 * graphs that are fine or leave the trap above to be discovered by a watchdog minutes later.</p>
 *
 * <h2>{@code each} survives as a serialized name and retires as an idea</h2>
 * <p>Recorded graphs carry {@code joinPolicy=each}, and breaking a serialized name is a migration
 * for zero information. In a marker-present document it says exactly what the absence of any join
 * property already says, so it is legal, reported by {@link #redundancies(GraphDefinition)}, and
 * <strong>never refused</strong>.</p>
 */
public final class JoinSemantics {

    /**
     * The graph-level property that versions the undeclared case.
     *
     * <p>Graph-scoped, not node-scoped, because it is a statement about how the whole document is to
     * be read. Unreserved on purpose — {@link ReservedGraphProperties#PREFIX} is refused at ingest,
     * so a marker in that namespace could never be authored. It carries a dot for the same reason
     * every other multi-word property here does not: this one is a document-level declaration rather
     * than a node setting, and the shape difference is the cheapest available signal of that.</p>
     *
     * <p>It rides the format that already exists. GraphML declares a key with {@code for="graph"} and
     * hangs one {@code <data>} on the {@code <graph>} element; Ravenroot's compatibility layer
     * already validates that scope, already reports such a key as {@code PRESERVED}, and
     * {@code GraphManager.writeGraphMl} already writes an imported document back verbatim. So a
     * document that has the marker keeps it byte for byte, and a document that does not is not
     * touched.</p>
     */
    public static final String MARKER_PROPERTY = "join.semantics";

    /** The one value of {@link #MARKER_PROPERTY} that selects declared-only joins. */
    public static final String DECLARED = "declared";

    /** @see JoinSemantics */
    public static final String POLICY_PROPERTY = "joinPolicy";

    /** @see JoinSemantics */
    public static final String QUORUM_PROPERTY = "joinQuorum";

    /** @see JoinSemantics */
    public static final String TIMEOUT_PROPERTY = "joinTimeout";

    /** The legacy state-machine merge policy: legal, redundant under the marker, never refused. */
    public static final String EACH_POLICY = "each";

    /** The three properties whose presence declares a join. */
    public static final List<String> JOIN_PROPERTIES =
            List.of(POLICY_PROPERTY, QUORUM_PROPERTY, TIMEOUT_PROPERTY);

    private JoinSemantics() {
    }

    /**
     * Whether {@code graph} carries the marker, and therefore joins only where one is declared.
     *
     * <p>Only the exact value {@value #DECLARED} selects the new reading, compared without case.
     * Anything else — a typo, a value a newer Ravenroot writes — leaves the document on the legacy
     * reading rather than guessing, because the legacy reading is the one that cannot silently change
     * what an existing document does. A value that is neither is reported by
     * {@link #redundancies(GraphDefinition)} so it is not merely ignored in silence.</p>
     */
    public static boolean declaredJoinsOnly(GraphDefinition graph) {
        Objects.requireNonNull(graph, "graph");
        Object marker = graph.properties().get(MARKER_PROPERTY);
        return marker != null && DECLARED.equalsIgnoreCase(marker.toString().trim());
    }

    /** Whether the author wrote any join property on {@code node}. */
    public static boolean isDeclared(GraphNode node) {
        Objects.requireNonNull(node, "node");
        return JOIN_PROPERTIES.stream().anyMatch(property -> rawValue(node, property) != null);
    }

    /** Whether {@code node} carries {@code joinPolicy=each}. */
    public static boolean isEach(GraphNode node) {
        Objects.requireNonNull(node, "node");
        String raw = rawValue(node, POLICY_PROPERTY);
        return raw != null && raw.trim().equalsIgnoreCase(EACH_POLICY);
    }

    /**
     * A branch is a distinct predecessor node, not an incoming edge (CORE-03).
     *
     * <p>Computed from {@link GraphDefinition#edges()} rather than {@link GraphDefinition#previous}
     * so it can be called from inside {@link GraphDefinition}'s own constructor, where an edge naming
     * an undeclared endpoint has been collected as a violation but not yet thrown, and resolving that
     * endpoint to a node would fail with the wrong exception.</p>
     */
    public static List<String> distinctPredecessors(GraphDefinition graph, String nodeId) {
        var distinct = new TreeSet<String>();
        for (var edge : graph.edges()) {
            if (edge.target().equals(nodeId)) {
                distinct.add(edge.source());
            }
        }
        return List.copyOf(distinct);
    }

    /**
     * Whether {@code node} is a synchronisation point under {@code graph}'s semantics version.
     *
     * <p>The single answer both {@link GraphDefinition}'s load-time refusals and
     * {@code JoinSpec.validate} consult, so the structure that is refused and the structure that gets
     * a coordinator cannot drift into two different definitions of "join".</p>
     */
    public static boolean isJoin(GraphDefinition graph, GraphNode node, List<String> branches) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(node, "node");
        // START and `each` are excluded before the branch count is consulted, exactly as they were
        // before this class existed: both mean "never coordinate this node", not "coordinate it
        // differently", and both must keep answering the same way in a document with no marker.
        if (node.kind() == NodeKind.START || isEach(node)) {
            return false;
        }
        if (branches.size() < 2) {
            return false;
        }
        if (!declaredJoinsOnly(graph)) {
            return true;
        }
        // See the class comment for why ERROR is not subject to the marker.
        return node.kind() == NodeKind.ERROR || isDeclared(node);
    }

    /** {@link #isJoin(GraphDefinition, GraphNode, List)} against this node's own predecessors. */
    public static boolean isJoin(GraphDefinition graph, GraphNode node) {
        return isJoin(graph, node, distinctPredecessors(graph, node.id()));
    }

    /**
     * What the document says that says nothing, reported and never refused.
     *
     * <p>Two shapes. {@code joinPolicy=each} in a marker-present document is an explicit synonym of
     * the absence it already has, kept legal because recorded graphs carry the name and breaking a
     * serialized name is a migration for zero information. A {@value #MARKER_PROPERTY} whose value is
     * not {@value #DECLARED} selected nothing, and saying so is the difference between a typo the
     * author can see and a document that quietly kept the old semantics.</p>
     */
    public static List<String> redundancies(GraphDefinition graph) {
        Objects.requireNonNull(graph, "graph");
        var notices = new ArrayList<String>();
        Object marker = graph.properties().get(MARKER_PROPERTY);
        if (marker != null && !DECLARED.equalsIgnoreCase(marker.toString().trim())) {
            notices.add("Graph property '" + MARKER_PROPERTY + "' is '" + marker
                    + "', which selects nothing; the only value that declares explicit joins is '"
                    + DECLARED + "', and this document is read with inferred joins");
            return List.copyOf(notices);
        }
        if (!declaredJoinsOnly(graph)) {
            return List.of();
        }
        for (GraphNode node : graph.nodes()) {
            if (isEach(node)) {
                notices.add("Node '" + node.id() + "' declares " + POLICY_PROPERTY + "=" + EACH_POLICY
                        + ", which is redundant in a document declaring " + MARKER_PROPERTY + "="
                        + DECLARED + ": an undeclared node already runs once per arrival. It is legal "
                        + "and kept for documents recorded before that marker existed");
            }
        }
        return List.copyOf(notices);
    }

    /**
     * The legacy document rewritten so it means, under the marker, exactly what it meant without it.
     *
     * <p>Only an author runs this, and only deliberately: it is an ordinary authored edit that
     * produces a new version and a new hash, never something a load path does on the reader's behalf.
     * Materialising a default silently would re-address a recorded document, and there is no writer
     * to do it with — an imported document is exported verbatim or refused.</p>
     *
     * <p>Every node that is a join <em>today</em> gets its currently-effective policy written onto
     * it: {@code joinPolicy=all} on an ordinary fan-in, and {@code joinQuorum=1} on the error
     * terminal — never {@code each}, which would remove the coordination instead of recording it and
     * reinstate the race the error-terminal coordination closes. Nodes that already declare a policy are left exactly as they
     * are; the author's own words always win. Then the marker is stamped.</p>
     *
     * <p>A document that already carries the marker is returned unchanged, so running this twice
     * changes nothing the second time.</p>
     */
    public static GraphDefinition migrate(GraphDefinition graph) {
        Objects.requireNonNull(graph, "graph");
        if (declaredJoinsOnly(graph)) {
            return graph;
        }
        var nodes = new ArrayList<GraphNode>();
        for (GraphNode node : graph.nodes()) {
            List<String> branches = distinctPredecessors(graph, node.id());
            if (!isJoin(graph, node, branches) || isDeclared(node)) {
                nodes.add(node);
                continue;
            }
            var properties = new LinkedHashMap<String, Object>(node.properties());
            if (node.kind() == NodeKind.ERROR) {
                properties.put(QUORUM_PROPERTY, "1");
            } else {
                properties.put(POLICY_PROPERTY, "all");
            }
            nodes.add(new GraphNode(node.id(), node.kind(), node.behavior(), properties));
        }
        var properties = new LinkedHashMap<String, Object>(graph.properties());
        properties.put(MARKER_PROPERTY, DECLARED);
        return new GraphDefinition(nodes, List.copyOf(graph.edges()), properties);
    }

    private static String rawValue(GraphNode node, String property) {
        Object configured = node.properties().get(property);
        if (configured == null) {
            return null;
        }
        String text = configured.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /** Read-only view of {@code properties} with every value rendered as the document wrote it. */
    static Map<String, Object> copyOf(Map<String, ?> properties) {
        return properties == null ? Map.of() : Map.copyOf(properties);
    }
}
