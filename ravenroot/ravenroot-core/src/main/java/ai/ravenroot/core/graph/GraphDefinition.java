package ai.ravenroot.core.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GraphDefinition {

    private static final String SYNTHESIZED_EDGE_ID_PREFIX = "ravenroot-synthesized-edge-";

    private final Map<String, GraphNode> nodes;
    private final List<GraphEdge> edges;
    private final Map<String, Object> properties;

    /**
     * A definition with no graph-level properties, preserving the shape of documents recorded without them.
     *
     * <p>Kept as the primary shape rather than deprecated: a graph built in Java declares nothing
     * about how it is to be read, and forcing every such call site to pass an empty map would say
     * that it does.</p>
     */
    public GraphDefinition(Collection<GraphNode> nodes, Collection<GraphEdge> edges) {
        this(nodes, edges, Map.of());
    }

    /**
     * A definition and what the document declared about itself.
     *
     * <p>Graph-level properties are the document's statements about the whole graph rather than
     * about any node in it. Today exactly one is interpreted — {@link JoinSemantics#MARKER_PROPERTY}
     * — and every other one is carried untouched, for the same reason node and edge properties are:
     * this class is not the authority on which properties a document may contain.</p>
     */
    public GraphDefinition(Collection<GraphNode> nodes, Collection<GraphEdge> edges,
                           Map<String, Object> properties) {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(edges, "edges");
        this.properties = JoinSemantics.copyOf(properties);
        var indexedNodes = new LinkedHashMap<String, GraphNode>();
        var duplicateIds = new ArrayList<String>();
        for (var node : nodes) {
            if (indexedNodes.putIfAbsent(node.id(), node) != null) {
                duplicateIds.add(node.id());
            }
        }
        if (!duplicateIds.isEmpty()) {
            throw new GraphValidationException(
                    duplicateIds.stream().map(id -> "Duplicate node id: " + id).toList());
        }
        this.nodes = Map.copyOf(indexedNodes);
        this.edges = identifyEdges(edges);
        validate();
    }

    /**
     * Gives Java-authored definitions the same deterministic identity guarantee GraphML imports have.
     * Explicit ids are preserved; missing ids are assigned in collection order while skipping every
     * explicit id. Edge ids deliberately remain outside the canonical semantic hash: they are handles,
     * not routing semantics, exactly as they are in GraphML.
     */
    private static List<GraphEdge> identifyEdges(Collection<GraphEdge> edges) {
        var declared = new java.util.LinkedHashSet<String>();
        for (GraphEdge edge : edges) {
            if (edge.id() != null && !declared.add(edge.id())) {
                throw new GraphValidationException(List.of("Duplicate edge id: " + edge.id()));
            }
        }
        var identified = new ArrayList<GraphEdge>(edges.size());
        int candidate = 0;
        for (GraphEdge edge : edges) {
            if (edge.id() != null) {
                identified.add(edge);
                continue;
            }
            String synthesized;
            do {
                synthesized = SYNTHESIZED_EDGE_ID_PREFIX + candidate++;
            } while (declared.contains(synthesized));
            declared.add(synthesized);
            identified.add(edge.identifiedAs(synthesized));
        }
        return List.copyOf(identified);
    }

    public Collection<GraphNode> nodes() {
        return nodes.values();
    }

    public List<GraphEdge> edges() {
        return edges;
    }

    /** What the document declared about the graph as a whole; empty for a graph built in Java. */
    public Map<String, Object> properties() {
        return properties;
    }

    public GraphNode node(String id) {
        var node = nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException("Unknown graph node: " + id);
        }
        return node;
    }

    public List<GraphNode> next(String source, String outcome) {
        return nextEdges(source, outcome).stream()
                .map(edge -> node(edge.target()))
                .toList();
    }

    /**
     * Matching edges, retained because target command is an edge-level delivery instruction.
     *
     * <p>A failure-route edge is excluded here regardless of {@code outcome}.
     * It carries {@link GraphEdge#DEFAULT_OUTCOME} — the same value an ordinary "no outcome authored"
     * edge carries — so without this exclusion an ordinary successful {@code continue} would select it
     * too and fire it on success, which is exactly the converse the mechanism must never do: a failure
     * route fires only through {@link #failureEdges}, on a failed attempt.</p>
     */
    public List<GraphEdge> nextEdges(String source, String outcome) {
        return edges.stream()
                .filter(edge -> edge.source().equals(source))
                .filter(edge -> !failureRouted(edge))
                .filter(edge -> edge.outcome().equals(outcome))
                .toList();
    }

    /**
     * {@code source}'s failure route: the edges a runner selects when
     * {@code source}'s attempt fails, instead of the outcome-matched edges {@link #nextEdges} selects
     * when it completes. Never outcome-filtered — a crashed node produced no outcome to filter on.
     */
    public List<GraphEdge> failureEdges(String source) {
        return edges.stream()
                .filter(edge -> edge.source().equals(source))
                .filter(this::failureRouted)
                .toList();
    }

    /**
     * Whether {@code edge} carries its source's <strong>failure</strong> rather than one of its
     * outcomes — either because its author declared {@link FailureRouteEdgeProperty#NAME}, or because
     * this graph resolves it as one by default.
     *
     * <h2>Failure-route precedence</h2>
     * <p>An unhandled error goes directly to an Error node unless the author routes it differently.
     * Therefore:</p>
     * <ol>
     *   <li>an edge that <em>declares an outcome</em> stays an outcome edge and behaves exactly as it
     *       did previously, whatever it points at — including an {@link NodeKind#ERROR} node;</li>
     *   <li>an edge that declares nothing and points at an {@code ERROR} node is a failure route.</li>
     * </ol>
     * <p>The default fills a vacuum; it never overrides an explicit routing decision. A handled error
     * still goes where its author sent it.</p>
     *
     * <h2>"Declares nothing" is {@link GraphEdge#DEFAULT_OUTCOME}, and deliberately so</h2>
     * <p>{@link GraphEdge}'s canonical constructor collapses "no outcome was authored" and "the author
     * wrote {@code continue}" into the same value, and ADR 0024 rejects a new record component
     * that would separate them because it re-addresses every recorded graph's canonical hash. So the
     * two are indistinguishable here, exactly as they are indistinguishable to {@link #validate()}'s
     * refusal: the default is built on precisely the signal that refusal is built on, rather than
     * on a second, differently-drawn line that could disagree with it.</p>
     *
     * <h2>Why this cannot make a previously loadable graph fail to load</h2>
     * <p>The refusal that a failure route may not also declare an outcome reads
     * {@link FailureRouteEdgeProperty#declared(GraphEdge)}, which inspects the <em>author's</em>
     * property map. An edge selected by this rule declares nothing, so it is invisible to that check by
     * construction, and the check itself is left untouched. The refusal also cannot be reached from
     * the other side: this method only ever defaults an edge whose outcome already <em>is</em>
     * {@code DEFAULT_OUTCOME}, which is the one value the refusal permits.</p>
     *
     * <h2>Hooked to the target's kind and to nothing else</h2>
     * <p>Not to the target's id, its name, its behavior, or to what the source's other edges declare.
     * A node with a bare edge to {@code ERROR} and a declared {@code failure.route} elsewhere gets
     * both on failure, the same fan-out two declared routes would produce; the default does not
     * consult siblings, because a rule that did would make one edge's meaning depend on another edge's
     * text and stop being readable off the drawing.</p>
     */
    public boolean failureRouted(GraphEdge edge) {
        return edge != null && (edge.failureRoute() || defaultedFailureRoute(edge));
    }

    /**
     * The {@link #failureRouted} half that the author did not write, exposed separately so that an
     * inspector can tell the two apart instead of re-deriving the rule: a declared route is a
     * property to show and let the author clear, a defaulted one is a consequence of where the edge
     * points and there is nothing to clear. Nothing in main code calls this yet.
     * Returns {@code false} for an edge that declares the property outright.
     */
    public boolean defaultedFailureRoute(GraphEdge edge) {
        if (edge == null || edge.failureRoute() || !GraphEdge.DEFAULT_OUTCOME.equals(edge.outcome())) {
            return false;
        }
        var target = nodes.get(edge.target());
        return target != null && target.kind() == NodeKind.ERROR;
    }

    public List<GraphNode> previous(String target) {
        return edges.stream()
                .filter(edge -> edge.target().equals(target))
                .map(edge -> node(edge.source()))
                .toList();
    }

    public GraphNode start() {
        return nodes.values().stream()
                .filter(node -> node.kind() == NodeKind.START)
                .findFirst()
                .orElseThrow();
    }

    /**
     * How many error terminals a graph may declare.
     *
     * <h2>Zero or one, and the two halves of that have different reasons</h2>
     * <p><strong>The floor is zero.</strong> Previously it was one — {@code ERROR} was part
     * of a graph's minimal structure and a document without it was refused. The current contract does
     * not invalidate a graph for lack of {@code Error}. The
     * node kind, the default new document that carries one, and the recommendation all survive; only
     * the refusal is gone. What is deliberately <em>not</em> weakened is the tracing correction whose
     * reason — that a traversal continues past {@code Error} — never depended on the
     * obligation.</p>
     *
     * <p><strong>The ceiling stays one</strong>, and that is not a leftover from the obligation: it is
     * load-bearing for a correctness property that lives in another class, as the section below
     * explains. Multiple error terminals remain unsupported.</p>
     *
     * <h2>Widening to (c) is not a local constant edit</h2>
     * <p>Raising {@link #MAX_ERROR_NODES} to {@link Integer#MAX_VALUE} would silently reopen the race
     * the ceiling exists to close.</p>
     *
     * <p>{@code GraphRunner.ExecutionState} records what reached a terminal in one field
     * <em>per terminal kind</em>, not per node — {@code endTerminalPayload} and
     * {@code errorTerminalPayload}. Its argument that no two writers race for either field reduces
     * "two writers" to "the terminal was invoked twice concurrently", and that reduction holds only
     * because a graph has <strong>at most</strong> one node of each kind, which is enforced right
     * here. With two error terminals, a fan-out whose branches fail into different ones produces two
     * concurrent writers of the same field through <em>none</em> of the three mechanisms that argument
     * enumerates: no fan-in, no re-entry, no double delivery. The ceiling below is therefore
     * load-bearing for a correctness property that lives in another class, which is exactly the kind
     * of coupling a "one line" note invites someone to miss.</p>
     *
     * <p><strong>"At most one", not "exactly one", is what that reduction needs.</strong> Only the
     * ceiling is load-bearing. Zero nodes of a kind leave the field with
     * <em>no</em> writer, so the traversal reports the other terminal's arrival or none — the
     * dangerous direction is up, never down. That is an argument, and this codebase has had several
     * such arguments turn out wrong, so it is measured rather than trusted: {@code
     * AbsentErrorTerminalTest} runs three topologies with and without an error terminal and compares
     * the payload outcome spaces.</p>
     *
     * <p>So (c) is one line in this class plus a decision about how the runner keys those fields.
     * {@code CyclicTerminalPayloadTest} pins this rule as the premise it depends on, so changing the
     * bound invalidates an executable check of the runner's field keying.</p>
     */
    private static final int MIN_ERROR_NODES = 0;

    /** @see #MIN_ERROR_NODES */
    private static final int MAX_ERROR_NODES = 1;

    private void validate() {
        var violations = new ArrayList<String>();
        var starts = nodes.values().stream().filter(n -> n.kind() == NodeKind.START).toList();
        var ends = nodes.values().stream().filter(n -> n.kind() == NodeKind.END).toList();
        var errorTerminals = nodes.values().stream().filter(n -> n.kind() == NodeKind.ERROR).toList();
        if (starts.size() != 1) {
            violations.add("A graph must contain exactly one start node");
        }
        if (ends.size() != 1) {
            violations.add("A graph must contain exactly one end node");
        }
        // The error terminal is bounded, not required. The rule sits beside
        // the two above rather than in place of either, and like them it applies everywhere a
        // definition is materialised — every load path runs through this constructor — but what it
        // refuses is now only a *surplus*. A document that declares no error terminal is a design
        // choice the author is entitled to make; a document that
        // declares two would give GraphRunner's per-kind payload field two writers, which is a
        // correctness matter and stays refused. See MIN_ERROR_NODES for why the two ends of this
        // range have unrelated reasons.
        if (errorTerminals.size() < MIN_ERROR_NODES || errorTerminals.size() > MAX_ERROR_NODES) {
            violations.add(errorTerminalViolation(errorTerminals));
        }
        for (var edge : edges) {
            if (!nodes.containsKey(edge.source())) {
                violations.add("Edge source does not exist: " + edge.source());
            }
            if (!nodes.containsKey(edge.target())) {
                violations.add("Edge target does not exist: " + edge.target());
            }
            // An edge is a failure route or an outcome edge, never both.
            // GraphEdge's canonical constructor already collapses "no outcome authored" into
            // DEFAULT_OUTCOME, which is the one signal available to tell the two apart; anything else
            // is an outcome the author explicitly chose, and declaring both is refused at load rather
            // than silently picked between.
            //
            // This deliberately does NOT widen to failureRouted(edge). The refusal is about what
            // the author WROTE -- two contradictory declarations on one edge -- and a defaulted route
            // declares nothing to contradict. Reading it through failureRouted() would refuse every
            // `outcome=failed` edge pointing at an ERROR node -- a shape recorded documents in this
            // repository still have -- so graphs that load today would stop loading. Those documents
            // are wrong, and the defaulting rule corrects the shipped ones, but they are wrong in a way their author
            // has to fix by choosing, not in a way the loader may decide for them.
            if (edge.failureRoute() && !GraphEdge.DEFAULT_OUTCOME.equals(edge.outcome())) {
                violations.add("Edge from " + edge.source() + " to " + edge.target() + " declares '"
                        + FailureRouteEdgeProperty.NAME + "=" + FailureRouteEdgeProperty.TRUE
                        + "' together with an explicit outcome '" + edge.outcome()
                        + "'; an edge is a failure route or an outcome edge, never both");
            }
        }
        violations.addAll(unsatisfiableStructures());
        if (!violations.isEmpty()) {
            throw new GraphValidationException(violations);
        }
    }

    /**
     * The two structures that can never proceed, refused before anything runs.
     *
     * <h2>Retroactive, and safe by construction</h2>
     * <p>These apply in every semantics version — with the {@link JoinSemantics#MARKER_PROPERTY}
     * marker and without it — rather than only to documents that have migrated, and that is
     * deliberate: the unsatisfiable shape is identical in unmigrated documents, and leaving it live
     * would mean the defect survives in every graph recorded so far until
     * someone edits it.</p>
     *
     * <p>Widening a refusal retroactively is normally the thing not to do, and it is safe here for
     * one reason that has to hold for both rules: each names a structure that is
     * <strong>statically</strong> unsatisfiable. No input, no schedule and no amount of waiting makes
     * either proceed, so a recorded graph containing one never completed, and no working graph can be
     * broken by naming it. The rules do not generalise past that — a join that merely happens to be
     * starved on some runs is a live traversal that no drawing can convict, so it remains a runtime
     * liveness concern rather than this method's responsibility.</p>
     *
     * <h2>Refused here, which is what "at load" means</h2>
     * <p>Beside the terminal-cardinality rules rather than in {@code JoinSpec}, because
     * {@code JoinSpec.validate} runs when a {@code GraphRunner} is constructed — after the document
     * was accepted, hashed and recorded. This class is the one place every path that materialises a
     * definition passes through, so a refusal here is what {@code GraphManager.semanticViolations},
     * {@code validateGraphMl} and {@code POST /v1/graphs/inspect} all report, and the author sees the
     * name of the problem when they submit the file rather than when a run silently fails to
     * start.</p>
     */
    private List<String> unsatisfiableStructures() {
        var violations = new ArrayList<String>();
        for (var node : nodes.values()) {
            // A join waiting on itself. The arrival it is missing can only be produced by its own
            // completion, and its completion is what the wait is blocking: the observed symptom was
            // no instance, no error and a traversal that never terminated, so Test and Run stayed
            // disabled with no way out. Which nodes count as joins depends on the document's
            // semantics version, so the question is asked of JoinSemantics rather than answered again
            // here -- under the marker a self-loop on an undeclared node is repetition, and is not
            // caught by this rule at all.
            List<String> branches = JoinSemantics.distinctPredecessors(this, node.id());
            if (branches.contains(node.id()) && JoinSemantics.isJoin(this, node, branches)) {
                violations.add("Node '" + node.id() + "' is a join whose branches include itself ("
                        + String.join(", ", branches) + "), so it waits for its own completion and can "
                        + "never proceed; a node that loops back to itself expresses repetition, which "
                        + "needs the graph property '" + JoinSemantics.MARKER_PROPERTY + "="
                        + JoinSemantics.DECLARED + "' and no join declared on the node");
            }
            // Repetition with no way out. Legal as a shape only while some edge leaves the node for
            // somewhere else; without one the traversal has no reachable successor and no terminal,
            // in any semantics version.
            boolean loops = false;
            boolean escapes = false;
            for (var edge : edges) {
                if (!edge.source().equals(node.id())) {
                    continue;
                }
                if (edge.target().equals(node.id())) {
                    loops = true;
                } else {
                    escapes = true;
                }
            }
            if (loops && !escapes) {
                violations.add("Node '" + node.id() + "' loops back to itself and has no other outgoing "
                        + "edge, so the repetition it declares can never end; a repeating node needs an "
                        + "edge that leaves it");
            }
        }
        return violations;
    }

    /**
     * States the rule in the form the bounds actually take, and then how the document broke it.
     *
     * <p>Three forms, because the message has to be readable rather than parametric-looking: an exact
     * count when the bounds coincide, a ceiling when the floor is zero — which is the current shape —
     * and a range otherwise. Deriving the wording from {@link #MIN_ERROR_NODES} and
     * {@link #MAX_ERROR_NODES} rather than hard-coding today's sentence is what stopped this message
     * from going stale when the floor dropped.</p>
     *
     * <p>The "none is declared" branch is unreachable while the floor is zero and is kept anyway: it
     * is the counterpart of the surplus branch, the two call for opposite repairs — a node to draw
     * versus a node to remove or merge — and deleting it would leave a message that silently assumes
     * a floor of zero rather than reading it.</p>
     */
    private static String errorTerminalViolation(List<GraphNode> errorTerminals) {
        String rule;
        if (MIN_ERROR_NODES == MAX_ERROR_NODES) {
            rule = "A graph must contain exactly " + count(MIN_ERROR_NODES);
        } else if (MIN_ERROR_NODES == 0) {
            rule = "A graph must contain at most " + count(MAX_ERROR_NODES);
        } else {
            rule = "A graph must contain between " + MIN_ERROR_NODES + " and " + count(MAX_ERROR_NODES);
        }
        if (errorTerminals.isEmpty()) {
            return rule + ", and none is declared";
        }
        // Sorted, not in the order they were declared: this class indexes its nodes into a
        // Map.copyOf, whose iteration order is unspecified and in practice varies per run, so the
        // declaration order is not available here and an unsorted list would make the same document
        // produce different messages on different runs.
        return rule + ", and " + errorTerminals.size() + " are declared: "
                + errorTerminals.stream().map(GraphNode::id).sorted()
                        .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * {@code "one error node"} / {@code "3 error nodes"}. Spelling out one and pluralising other
     * values keeps the diagnostic grammatical when the bounds change.
     */
    private static String count(int errorNodes) {
        return errorNodes == 1 ? "one error node" : errorNodes + " error nodes";
    }
}
