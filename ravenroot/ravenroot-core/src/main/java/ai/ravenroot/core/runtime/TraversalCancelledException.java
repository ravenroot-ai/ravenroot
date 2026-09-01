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
        super("Traversal " + traversalId + " was cancelled before node '" + refusedNodeId + "' was invoked");
        this.traversalId = traversalId;
        this.refusedNodeId = refusedNodeId;
    }

    public UUID traversalId() {
        return traversalId;
    }

    /** The first hop that did not run. Every node before it did, and its effects stand. */
    public String refusedNodeId() {
        return refusedNodeId;
    }
}
