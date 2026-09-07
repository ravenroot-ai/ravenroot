package ai.ravenroot.persistence.postgresql;

/**
 * A seam that lets a test stop a real process at a real commit boundary.
 *
 * <p>Nothing here is on the port: this interface is package-private and no port method mentions it.
 * It exists because a kill that is not precisely placed proves much less than it appears to. A test
 * that kills a writer "at some point during a batch" almost always lands in the long tail of
 * statement execution and never in the microseconds around {@code COMMIT}, so it demonstrates that an
 * un-started transaction leaves no trace — which is not in doubt — while never once exercising the
 * boundary where atomicity is actually decided.</p>
 *
 * <p>The hooks matter more for this adapter than for a single-host one. When the writer and the
 * database are separate processes, a client killed between {@link #beforeCommit()} and
 * {@link #afterCommit()} leaves a transaction the server resolves on its own, and a client killed
 * just after {@code COMMIT} returns leaves work that is durable but that no surviving process has yet
 * observed. Those are the two states a recovering peer has to distinguish, and they are reachable
 * only by stopping precisely here.</p>
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
