package ai.ravenroot.api.persistence;

/**
 * Authoritative actionable Human Task counts for one node before paging.
 *
 * @param nodeId exact graph node identity.
 * @param pending number of authorized waiting or escalated tasks at the node.
 * @param escalated number of those tasks currently escalated.
 */
public record HumanTaskNodeAttentionCounts(String nodeId, long pending, long escalated) {
    /** Validates the node identity and counts. */
    public HumanTaskNodeAttentionCounts {
        nodeId = HandlerRegistration.requireBoundedKey(nodeId, "nodeId");
        if (pending < 0 || escalated < 0 || escalated > pending) {
            throw new IllegalArgumentException("invalid node human-task attention counts");
        }
    }
}
