package ai.ravenroot.core.graph;

/** Describes a node's role without coupling the graph to an execution runtime. */
public enum NodeKind {
    START,
    PASSTHROUGH,
    BEHAVIOR,
    END,
    /**
     * The graph's error terminal: a node kind of its own,
     * not an {@link #END} carrying an error marker.
     *
     * <p>The alternative was refused for a structural reason rather than a stylistic one. A graph may
     * hold exactly one {@code END}, so "an {@code END} with error semantics" would have to mark
     * <em>the</em> terminal — and then a node cannot draw one edge to the ordinary terminal and
     * another to the error terminal, because they would be the same node. The minimal graph shape is
     * only expressible with two distinct kinds.</p>
     *
     * <p>{@code ERROR} is a terminal by intent, not by rule: nothing here forbids outgoing edges, and
     * error-terminal routing contract describes continuing from it into logging, alerting, retry or remediation. The
     * runtime therefore treats it as {@link #END}'s peer for the traversal's result payload
     * ({@code GraphRunner}) and as an ordinary node for successor dispatch.</p>
     */
    ERROR
}
