package ai.ravenroot.core.runtime;

import java.util.Set;
import java.util.UUID;

/**
 * What one traversal produced. A result only ever exists for a traversal that completed: a traversal
 * that failed surfaces its failure through the read instead, so every field here describes a success.
 *
 * <p>{@code handledFailureNodes} is the one field that describes a success in which something went
 * wrong. See its accessor.</p>
 *
 * <p>{@code untakenEdges} is the odd one out in a different way: every other field describes
 * what the traversal DID; this one describes what a bypassed node's edges could never have done,
 * because a bypassed node's outcome is hardcoded to {@code "continue"} regardless of what its own
 * logic would have returned. See its accessor for the three ways a node comes to be bypassed — the
 * cause is deliberately not recorded in this record, and {@code TEST_PASSTHROUGH} is only one of
 * them.</p>
 */
public record GraphExecutionResult(UUID processInstanceId, UUID traversalId, Object payload, Set<String> visitedNodes,
                                   Set<String> defaultedNodes, Set<String> bypassedNodes,
                                   Set<String> handledFailureNodes, Set<String> untakenEdges) {
    public GraphExecutionResult {
        if (processInstanceId == null) throw new IllegalArgumentException("processInstanceId cannot be null");
        if (traversalId == null) throw new IllegalArgumentException("traversalId cannot be null");
        visitedNodes = Set.copyOf(visitedNodes);
        defaultedNodes = Set.copyOf(defaultedNodes);
        bypassedNodes = Set.copyOf(bypassedNodes == null ? Set.of() : bypassedNodes);
        handledFailureNodes = Set.copyOf(handledFailureNodes == null ? Set.of() : handledFailureNodes);
        untakenEdges = Set.copyOf(untakenEdges == null ? Set.of() : untakenEdges);
    }

    public GraphExecutionResult(UUID executionId, Object payload, Set<String> visitedNodes,
                                Set<String> defaultedNodes) {
        this(executionId, executionId, payload, visitedNodes, defaultedNodes, Set.of(), Set.of(), Set.of());
    }

    /** Compatibility constructor preserving the pre-command canonical shape. */
    public GraphExecutionResult(UUID processInstanceId, UUID traversalId, Object payload, Set<String> visitedNodes,
                                Set<String> defaultedNodes) {
        this(processInstanceId, traversalId, payload, visitedNodes, defaultedNodes, Set.of(), Set.of(), Set.of());
    }

    /** Compatibility constructor preserving the canonical shape without handled failures. */
    public GraphExecutionResult(UUID processInstanceId, UUID traversalId, Object payload, Set<String> visitedNodes,
                                Set<String> defaultedNodes, Set<String> bypassedNodes) {
        this(processInstanceId, traversalId, payload, visitedNodes, defaultedNodes, bypassedNodes, Set.of(),
                Set.of());
    }

    /** Compatibility constructor preserving the canonical shape without untaken edges. */
    public GraphExecutionResult(UUID processInstanceId, UUID traversalId, Object payload, Set<String> visitedNodes,
                                Set<String> defaultedNodes, Set<String> bypassedNodes,
                                Set<String> handledFailureNodes) {
        this(processInstanceId, traversalId, payload, visitedNodes, defaultedNodes, bypassedNodes,
                handledFailureNodes, Set.of());
    }

    /**
     * Which nodes this traversal reached — <strong>not how many times it reached each of them</strong>.
     *
     * <p>Stated because the type does not state it and the difference is observable. A node can
     * be entered more than once in one traversal: by a cycle that passes back through it, or by any
     * {@code joinPolicy=each} node upstream of it invoking its successors once per arrival. The second
     * route needs no cycle and no property on the node itself, and the editor stamps {@code each} onto
     * every non-{@code START} node with more than one predecessor <em>that does not already declare a
     * join policy of its own</em> when it serialises a legacy state-machine document, so the shape
     * arrives on imported graphs rather than only on authored ones. This is a {@link Set}, so every
     * repeat collapses to one entry and no reader can recover the difference from here — including the
     * CLI's {@code visited-nodes=} line, which sorts these ids but neither adds nor drops one, and the
     * HTTP result's {@code visitedNodes} array, which passes them through unchanged. That array is a
     * JSON list, which carries no set semantics to a client, and {@code docs/api/openapi.json} does not
     * describe the field at all: this paragraph reaches a Java caller and nobody else, so an HTTP
     * consumer who counted its entries to learn how many times a node ran would be wrong with nothing
     * on their surface to warn them.</p>
     *
     * <p>That collapse is the right shape for the question this field answers, and nothing is being
     * widened here. The surface that answers "how many times" is the durable diary: one
     * {@code NodeInvocation} per <em>visit</em>, each with its own identity, status, attempts and causal
     * parent, which is what makes a repeated entry legible there and illegible here.
     * {@code RepeatedTerminalDurableDiaryTest} pins the two halves against one traversal, so this
     * paragraph cannot quietly stop being true.</p>
     */
    @Override
    public Set<String> visitedNodes() {
        return visitedNodes;
    }

    /**
     * The nodes whose invocation was recorded {@code FAILED} inside a traversal that nonetheless
     * completed. Empty on a clean run; that is the whole distinction a caller reads.
     *
     * <p>This is a <em>derivation</em>, not a second record of the fault: the runner reads the
     * invocations its lifecycle aggregate already holds, at the moment it builds this result, and
     * keeps the node ids of those in {@code FAILED}. Nothing new is captured, journalled or
     * persisted, and no status widens — {@code TraversalStatus} stays {@code COMPLETED}, which is
     * true, because the traversal did reach its end.</p>
     *
     * <p>Two mechanisms populate it, and both are the same fact — a fault occurred and the graph
     * survived it by the author's design. An author-declared failure route carried the traversal past
     * a crashed node, or a {@code k of n} fan-in met its quorum over a branch that failed
     * (CORE-03). An <em>unhandled</em> failure never appears here, because it produces no result at
     * all.</p>
     */
    public Set<String> handledFailureNodes() {
        return handledFailureNodes;
    }

    /**
     * Whether this execution recovered from at least one node failure — the single unambiguous
     * statement a caller reads to tell a handled failure from a clean run.
     *
     * <p>Derived from {@link #handledFailureNodes()} rather than stored, so the two can never
     * disagree.</p>
     */
    public boolean handledFailure() {
        return !handledFailureNodes.isEmpty();
    }

    public UUID executionId() {
        return traversalId;
    }

    /**
     * Outgoing edges of a bypassed node that its own hardcoded {@code "continue"} outcome could
     * never select, regardless of what its real behavior would have returned.
     *
     * <p><b>Keyed on whether the node was bypassed, not on the submission's policy.</b> Three shapes
     * populate this field identically. A {@code TEST_PASSTHROUGH} run hardcodes every node's command
     * to the bypass directive from {@code start}. An individual edge naming the {@code passthrough}
     * command bypasses its target the same way under {@code STANDARD} ({@code NodeCommand}) — a graph
     * author's own choice, not a test artifact. The author can also switch one node off in
     * the document with {@code execution.bypass}, which is neither of the other two: it is not sticky,
     * and it fires inside a run that executes every other node for real.</p>
     *
     * <p>Two tests keep that sentence from narrowing back to "test only", one per non-test shape:
     * {@code NodeCommandRoutingTest#untakenEdgesFireUnderStandardPolicyWhenAnEdgeIndividuallyBypassesItsTarget}
     * for the edge command, and {@code
     * AuthoredNodeBypassTest#aSwitchedOffDecisionNodeTakesTheDefaultEdgeAndNamesTheBranchesItDidNotTake}
     * for the authored flag. Empty only when no bypassed node in the traversal — of any of the three
     * shapes — has any edge but the default {@code "continue"} one, never merely because the policy
     * was {@code STANDARD}.</p>
     *
     * <p>Each entry is {@code "<source>-><target> [outcome=<outcome>]"} for one edge that was never a
     * routing candidate on this traversal — not merely unselected this time, structurally unreachable
     * for as long as the edge keeps that outcome and its source stays bypassed. This is what lets an
     * author who ran the passthrough test see a branch point the traversal went silently blind past, instead
     * of having to infer it from {@link #visitedNodes()} stopping early.</p>
     *
     * <p>This does not generalize past the bypass gap it was measured against: an ordinary run's
     * untaken sibling of a taken outcome edge, on a node that was NOT bypassed, is not reported here,
     * because that is the normal shape of any if/else branch and not this bypass-specific blind spot.
     * A broader, execution-independent reachability answer is outside this field's contract.</p>
     */
    public Set<String> untakenEdges() {
        return untakenEdges;
    }
}
