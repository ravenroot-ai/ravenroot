package ai.ravenroot.api.history;
/** Actual stable edge dispatch count, retained independently of activity content and journal expiry.
 * @param edgeId original stable graph edge identity from EDGE_TRAVERSED
 * @param traversalCount number of actual dispatches, including loop repeats
 */
public record TraversalEdgeSummary(String edgeId, long traversalCount) {
    /** Rejects absent identities and nonpositive actual counts. */
    public TraversalEdgeSummary {
        if (edgeId == null || edgeId.isBlank() || traversalCount < 1) throw new IllegalArgumentException("invalid traversal edge summary");
    }
}
