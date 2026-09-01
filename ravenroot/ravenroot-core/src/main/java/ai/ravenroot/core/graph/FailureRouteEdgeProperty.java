package ai.ravenroot.core.graph;

import ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty;

import java.util.Map;

/**
 * The well-known, unreserved edge property through which a graph author declares that an edge
 * carries a node's <strong>failure route</strong> rather than one of its outcomes. A failed
 * traversal follows that route to an error node when the author provides one, and continues
 * from there only along edges the author designed.
 *
 * <h2>Why this cannot be the {@code outcome} an edge already carries</h2>
 * <p>HTTP's {@code error} outcome is a <em>successful</em> result carrying a different outcome for a
 * non-2xx response — a response exists and flows. A crashed node has no result at all: what a failure
 * route carries is failure metadata the runtime produced, never a payload the node produced. Routing
 * both through the same {@code outcome} value would let one edge name carry two data shapes with
 * nothing marking the difference — an injectivity break that is already refused — and it would also
 * make failure routing indistinguishable from outcome routing in the one place that matters: the
 * editor, where an author cannot tell a node that never runs its {@code error} edge from one that
 * does. Closing that indistinguishability is the point of this property, not a side effect.</p>
 *
 * <h2>Why an ordinary property and not a reserved one, or a new record component</h2>
 * <p>{@link RecoveryRepeatabilityProperty} applies the same namespace distinction at node level:
 * this is an author assertion about the graph's own structure, not a platform-asserted fact, so it
 * belongs in the ordinary, author-writable property namespace rather than
 * {@link ReservedGraphProperties}'s prefix. It is not a new {@link GraphEdge} record component
 * either — that would re-address every
 * recorded graph's canonical hash for the reason documented by ADR 0024. And it is not a
 * runtime-selected magic token inside {@code outcome} — a reserved value in an author-writable string
 * namespace recreates exactly the reserved-content problem {@link ReservedGraphProperties} exists to
 * avoid.</p>
 *
 * <h2>Fail-closed at load: one kind of edge, never both</h2>
 * <p>{@link GraphDefinition}'s constructor refuses an edge that declares this property together with
 * an explicitly authored {@code outcome}. {@link GraphEdge}'s canonical constructor already collapses
 * "no outcome was authored" and "the author wrote {@code continue}" into the same value — that is the
 * one signal the record shape can actually carry, and the ruling that a new record component is not
 * available means the check is built on exactly that signal: a failure-route edge is refused unless
 * its {@code outcome} is still {@link GraphEdge#DEFAULT_OUTCOME}. An edge is a failure route or an
 * outcome edge, never both.</p>
 *
 * <h2>Declaring it is no longer the only way to get one</h2>
 * <p>An edge that declares <em>nothing</em> and points
 * at an {@link NodeKind#ERROR} node is a failure route by default. This class stays what it always
 * was — the reading of what the <em>author wrote</em> — and is deliberately not widened to answer that
 * question, because the default depends on the target node's kind, which an edge alone cannot see.
 * {@link GraphDefinition#failureRouted(GraphEdge)} is the resolved answer and the one routing consults;
 * {@link GraphDefinition#defaultedFailureRoute(GraphEdge)} tells the two apart for an inspector. That
 * split is also what keeps the refusal above reachable only by an author who wrote both.</p>
 */
public final class FailureRouteEdgeProperty {

    /** The well-known name. Unreserved, and deliberately so. */
    public static final String NAME = "failure.route";

    /** The only value that declares an edge as a failure route. */
    public static final String TRUE = "true";

    private FailureRouteEdgeProperty() {
    }

    /** Whether {@code edge} declares itself a failure route. */
    public static boolean declared(GraphEdge edge) {
        return edge != null && declared(edge.properties());
    }

    /**
     * The raw read, exposed so {@link GraphDefinition#validate()} can apply it before a {@link GraphEdge}
     * exists as well as after. Case-sensitive and exact, like {@link RecoveryRepeatabilityProperty}'s
     * own token match: an approximate match here would route a failure down an edge nobody declared for it.
     */
    public static boolean declared(Map<String, Object> properties) {
        if (properties == null) {
            return false;
        }
        Object value = properties.get(NAME);
        return value != null && TRUE.equals(value.toString().strip());
    }
}
