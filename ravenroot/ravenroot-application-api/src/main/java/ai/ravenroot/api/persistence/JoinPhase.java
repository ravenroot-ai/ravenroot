package ai.ravenroot.api.persistence;

/** Lifecycle of one fan-in join (CORE-03). {@link #SATISFIED} and {@link #FAILED} are terminal. */
public enum JoinPhase {
    /** Still accepting arrivals: the quorum is neither met nor proven unreachable. */
    OPEN,

    /**
     * The quorum was met and the join fired, and <strong>this join will never fire again</strong>.
     *
     * <p>The record is <em>kept</em> in this phase rather than deleted, because deleting it would
     * make a late arrival indistinguishable from a first arrival and re-open the join. Deletion
     * happens when the traversal ends, which is when no further arrival is possible.</p>
     *
     * <h4>Circumscribed by re-arming joins</h4>
     * <p>This used to be the destiny of every join that succeeded, and that premise is what made a
     * cycle through a fan-in unexecutable: the second lap's arrivals found a terminal record and were
     * discarded as {@code LATE}, so the node downstream of the join was never invoked again and the
     * traversal reported success having silently dropped a whole iteration.</p>
     *
     * <p>A join that re-arms — the default — therefore never reaches this phase at all. It
     * stays {@link #OPEN} for the whole traversal and records how far it has fired in
     * {@link JoinRecord#firedThrough()}, which is what lets a bucket that has already fired refuse a
     * redelivery without also refusing the next lap. Two kinds of record are still in this phase, and
     * a reader must treat both as "fired at iteration 0, never again":</p>
     * <ul>
     *   <li>a join whose re-arming is switched off — no property currently selects this behavior, so
     *       nothing writes this today;</li>
     *   <li>a record written by a runtime that predates re-arming joins, where every join was one-shot.</li>
     * </ul>
     */
    SATISFIED,

    /** The quorum became unreachable, or the configured timeout elapsed before it was met. */
    FAILED;

/**
 * Indicates whether no further join arrival can change this phase.
 * @return {@code true} for satisfied or failed joins.
 */
    public boolean terminal() {
        return this != OPEN;
    }
}
