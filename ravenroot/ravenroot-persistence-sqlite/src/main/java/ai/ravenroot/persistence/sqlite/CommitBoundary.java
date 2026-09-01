package ai.ravenroot.persistence.sqlite;

/**
 * A seam that lets a test stop a real process at a real commit boundary.
 *
 * <p>ADR 0010 section 12.4 forbids adding a fault-injection point to the <em>port</em>, and nothing
 * here is on the port: this interface is package-private and no {@code ExecutionStore} method
 * mentions it. It exists because the documented requirements for PERS-03 includes a kill test at commit
 * boundaries, and a kill that is not precisely placed proves much less than it appears to. A test that
 * kills a writer "at some point during a batch" almost always lands in the long tail of statement
 * execution and never in the microseconds around {@code COMMIT}, so it demonstrates that an
 * un-started transaction leaves no trace — which is not in doubt — while never once exercising the
 * boundary where atomicity is actually decided.</p>
 *
 * <p>The two hooks bracket the single {@code COMMIT} of a batch. A test drives the process to
 * {@link #beforeCommit()} and kills it there to prove that a fully-written but uncommitted batch is
 * invisible after recovery; it drives the process to {@link #afterCommit()} and kills it there to
 * prove that a committed batch is present after recovery, with no opportunity in between for the
 * adapter to have done anything else.</p>
 */
interface CommitBoundary {

    /** Every write goes through this instance in production; both hooks do nothing. */
    CommitBoundary NONE = new CommitBoundary() {
    };

    /** Called with the batch fully written to the transaction and {@code COMMIT} not yet issued. */
    default void beforeCommit() {
    }

    /** Called immediately after {@code COMMIT} returns and before anything else happens. */
    default void afterCommit() {
    }
}
