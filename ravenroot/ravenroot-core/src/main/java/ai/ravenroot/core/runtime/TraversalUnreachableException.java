package ai.ravenroot.core.runtime;

import java.util.UUID;

/**
 * Why a traversal ended: it could no longer reach any outcome of its own, and reconciliation ended
 * it rather than leaving it holding capacity.
 *
 * <h2>A distinguishable cause, not a generic failure</h2>
 * <p>An unreachable traversal fails, because the runtime has exactly two traversal outcomes and
 * "could never settle" is not a completion — no end node ran and there is no result payload. It is
 * also not the same fault as a node that broke: nothing raised, no dependency refused, no policy was
 * exhausted. What happened is that the last thing capable of settling the traversal disappeared,
 * leaving a branch parked at a join with nothing running, nothing scheduled, and no arrival that can
 * ever come. A reader that cannot tell the two apart looks for a failing node that does not exist,
 * so the cause carries the verdict in its own type rather than only in a message a caller would have
 * to parse.</p>
 *
 * <h2>Why it is not a cancellation, and why the distinction is load-bearing</h2>
 * <p>{@link TraversalCancelledException} says somebody with the authority to stop the work stopped
 * it. This says nobody did, and the work stopped being able to continue anyway. Collapsing the two
 * would either report an incident as an operator action — hiding a real defect from every dashboard
 * that filters cancellations out — or report an operator action as this, which fabricates a runtime
 * fault. {@link ExecutionTermination} keeps them apart from one throwable, and a traversal that is
 * both cancelled and unreachable is reported as cancelled: the operator's stop is the stronger
 * statement about provenance, and it is the one a reader is entitled to see.</p>
 *
 * <h2>What it claims, and what it does not</h2>
 * <p>It claims that at the instant reconciliation ran, this traversal held no running node, no armed
 * join deadline and at least one branch parked at a join. It does not claim that nothing happened:
 * every node that ran before the traversal became unreachable ran, and its effects stand and are not
 * undone. Nor does it name a culprit — the verdict is about the traversal's own reachability, which
 * is the only thing the runtime can observe without guessing.</p>
 *
 * <p>The message carries the traversal id and how many branches were parked, and nothing else. No
 * payload, no attribute and no operator text reaches it, so it is safe to publish on an execution
 * event and to record beside a terminal status.</p>
 */
public final class TraversalUnreachableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID traversalId;
    private final int parkedBranches;

    TraversalUnreachableException(UUID traversalId, int parkedBranches) {
        super("Traversal " + traversalId + " can no longer reach an outcome: no node is running, no "
                + "join deadline is armed, and " + parkedBranches + " branch(es) are parked at a join "
                + "that nothing left can settle");
        this.traversalId = traversalId;
        this.parkedBranches = parkedBranches;
    }

    public UUID traversalId() {
        return traversalId;
    }

    /**
     * Branches parked at a join when the traversal was found unreachable.
     *
     * <p>Always greater than zero: a parked branch is the positive evidence the criterion requires,
     * not an incidental detail, so a verdict without one is not this verdict.</p>
     */
    public int parkedBranches() {
        return parkedBranches;
    }
}
