package ai.ravenroot.core.runtime;

import java.util.UUID;

/**
 * Why a traversal ended: it was asked to stop, and the hop named here was refused.
 *
 * <h2>A distinguishable cause, not a generic failure</h2>
 * <p>A cancelled traversal fails, because the runtime has exactly two traversal outcomes and
 * "stopped on request" is not a completion — no end node ran and there is no result payload. But a
 * reader that cannot tell a cancellation from a node that broke will read every operator stop as an
 * incident, so the cause carries the reason in its own type rather than only in a message a caller
 * would have to parse.</p>
 *
 * <p>It deliberately does not claim that nothing happened. Effects issued before the cancel was
 * observed are not undone and cannot be — the concession
 * {@code AuthorizedRavenrootApplication.cancelExecution} already states to its own callers — so the
 * node named here is the first hop that did <em>not</em> run, and every node before it did.</p>
 */
public final class TraversalCancelledException extends RuntimeException {

    private final UUID traversalId;
    private final String refusedNodeId;

    TraversalCancelledException(UUID traversalId, String refusedNodeId) {
        super(describe(traversalId, refusedNodeId));
        this.traversalId = traversalId;
        this.refusedNodeId = refusedNodeId;
    }

    /**
     * Says which hop was refused when one is known, and says nothing about a hop when none is.
     *
     * <p>A stop that reaches a traversal between two hops always names the one it refused. A stop
     * that lands on a traversal stalled with no hop to refuse — delivered by the forced teardown
     * rather than by the dispatch gate — has no such node, and rendering the absence as
     * {@code node 'null'} would put a node name that does not exist into an operator's incident
     * report. Stating the absence is the honest form of the same message.</p>
     */
    private static String describe(UUID traversalId, String refusedNodeId) {
        return refusedNodeId == null
                ? "Traversal " + traversalId + " was cancelled while no hop was pending"
                : "Traversal " + traversalId + " was cancelled before node '" + refusedNodeId
                        + "' was invoked";
    }

    public UUID traversalId() {
        return traversalId;
    }

    /**
     * The first hop that did not run. Every node before it did, and its effects stand.
     *
     * <p>{@code null} when the stop reached a traversal that had no pending hop to refuse — a
     * traversal stalled at a fan-in, ended by the forced teardown rather than by the dispatch gate.
     * The cancellation is no less real; there is simply no hop it can name.</p>
     */
    public String refusedNodeId() {
        return refusedNodeId;
    }
}
