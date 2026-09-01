package ai.ravenroot.api.persistence;

/**
 * How one incoming branch of a fan-in join was settled (CORE-03).
 *
 * <p>A branch is settled at most once. There is deliberately no {@code PENDING} value: an
 * unsettled branch is <em>absent</em> from {@link JoinRecord#branches()} rather than present with a
 * pending marker. Absence is what makes a duplicate arrival decidable — a branch either has a
 * recorded outcome or it does not — whereas a pending marker would make "not yet arrived" and
 * "arrived, outcome not yet written" the same stored value.</p>
 */
public enum JoinBranchOutcome {
    /** The branch delivered a result to the join. Counts toward the quorum. */
    ARRIVED,

    /**
     * The branch can no longer deliver, because a node on it failed.
     *
     * <p>It does <strong>not</strong> count toward the quorum and does <strong>not</strong> by
     * itself fail the join. Failing the join on the first branch failure would make a quorum
     * meaningless: tolerating branch failure is the entire reason a caller configures {@code k < N}.
     * The join fails only when enough branches have failed that the quorum can no longer be reached
     * ({@link JoinPhase#FAILED}).</p>
     */
    FAILED,

    /**
     * The branch was never dispatched, so nothing will ever deliver on it (CORE-03).
     *
     * <p>Distinct from {@link #FAILED}, and the distinction is the point. A failed branch <em>ran</em>
     * and broke; a not-taken branch never started, because an upstream decision selected a different
     * outcome, or because every path to it died. Folding the two together would report "branch y
     * failed" for a graph in which nothing failed at all, and would send an operator looking for an
     * exception that does not exist. The actual defect — an {@code all} join spanning branches that
     * are mutually exclusive by construction — is only legible when the two are separate.</p>
     *
     * <p>Like {@link #FAILED} it does not count toward the quorum, and like {@link #FAILED} it does
     * not by itself fail the join: a {@code k of n} join whose remaining branches still reach {@code k}
     * proceeds normally.</p>
     *
     * <p>It is the one outcome that is <strong>provisional</strong>. It is recorded by whichever
     * predecessor proved the branch dead, which is a conclusion drawn without the branch's own
     * participation; a genuine arrival or failure on that branch therefore supersedes it. The
     * reverse never happens — a branch that has spoken for itself is never downgraded to
     * "not taken" — so a branch's recorded outcome only ever moves in one direction.</p>
     */
    NOT_TAKEN
}
