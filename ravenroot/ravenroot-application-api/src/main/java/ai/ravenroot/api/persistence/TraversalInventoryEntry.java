package ai.ravenroot.api.persistence;

import ai.ravenroot.api.application.TraversalStatus;

import java.util.UUID;

/**
 * One traversal of one process instance, as the inventory reports it.
 *
 * <p>Returned in {@link #position()} order, which is the traversal's insertion order within its
 * instance. Position is <em>validated domain state</em> rather than presentation: the aggregate's
 * cross-traversal causal-parent rule is stated in terms of position, so a listing ordered any other
 * way would present a structure the domain would reject. That is also why there is no timestamp here
 * — position already totally orders the traversals of an instance, and a created-at column would be a
 * second ordering that nothing reads and that could only drift away from the first.</p>
 *
 * @param key                the containing instance's tenant-scoped identity, repeated on every row so
 *                           a traversal can never be handled without the tenant that owns it
 * @param traversalId        identity of this traversal, distinct from the instance's
 * @param position           insertion order within the instance, from zero
 * @param ingressNodeId      the node this traversal entered at
 * @param status             authoritative stored traversal status
 * @param disposition        recovery classification derived at read time from this traversal's own
 *                           status and parked attempts, against the <em>instance's</em> lease; see
 *                           {@link InventoryDisposition}
 * @param invocationCount    number of node invocations recorded under this traversal
 * @param parkedAttemptCount number of attempts in this traversal awaiting a human decision. Reported
 *                           as a count and not a flag because it is the size of the operator's
 *                           outstanding work, and one parked attempt and nine are not the same
 *                           situation
 */
public record TraversalInventoryEntry(ExecutionKey key, UUID traversalId, int position,
                                      String ingressNodeId, TraversalStatus status,
                                      InventoryDisposition disposition, int invocationCount,
                                      int parkedAttemptCount) {

    /** Rejects a traversal row that could not describe a real stored traversal. */
    public TraversalInventoryEntry {
        if (key == null) throw new IllegalArgumentException("key cannot be null");
        if (traversalId == null) throw new IllegalArgumentException("traversalId cannot be null");
        if (position < 0) throw new IllegalArgumentException("position cannot be negative");
        if (ingressNodeId == null || ingressNodeId.isBlank()) {
            throw new IllegalArgumentException("ingressNodeId cannot be blank");
        }
        if (status == null) throw new IllegalArgumentException("status cannot be null");
        if (disposition == null) throw new IllegalArgumentException("disposition cannot be null");
        if (invocationCount < 0) throw new IllegalArgumentException("invocationCount cannot be negative");
        if (parkedAttemptCount < 0) {
            throw new IllegalArgumentException("parkedAttemptCount cannot be negative");
        }
    }
}
