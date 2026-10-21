package ai.ravenroot.api.history;
/** Scalar counts across all actual visits of one node in an exact traversal.
 * @param nodeId immutable graph node identity
 * @param visitCount all actual visits, including loops
 * @param activeVisits scheduled, running and waiting visits
 * @param completedVisits completed visits, independent from their declared outcomes
 * @param failedVisits failed visits
 * @param attemptCount actual delivery attempts across those visits
 */
public record TraversalNodeSummary(String nodeId, long visitCount, long activeVisits,
        long completedVisits, long failedVisits, long attemptCount) {
    /** Rejects invalid or inconsistent counts. */
    public TraversalNodeSummary {
        if (nodeId == null || nodeId.isBlank() || visitCount < 0 || activeVisits < 0 || completedVisits < 0
                || failedVisits < 0 || attemptCount < 0 || activeVisits + completedVisits + failedVisits != visitCount)
            throw new IllegalArgumentException("invalid traversal node summary");
    }
}
