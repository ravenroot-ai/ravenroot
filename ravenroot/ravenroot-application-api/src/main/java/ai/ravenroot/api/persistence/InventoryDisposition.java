package ai.ravenroot.api.persistence;

import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.TraversalStatus;

/**
 * The recovery classification an operator reads off a durable inventory row.
 *
 * <h2>Derived, never stored</h2>
 * <p>A disposition is computed at read time from state the store already holds authoritatively: the
 * {@link ProcessInstanceStatus}, whether a live lease exists, and whether any attempt is parked. It
 * is deliberately <strong>not</strong> a column. A stored classification is a second copy of the
 * lifecycle that has to be kept in step with the first, and the two disagree the moment a lease
 * lapses — because a lapse is the passage of time rather than a write, so there is no transaction in
 * which the stored copy could have been corrected. Deriving it means the answer cannot be stale by
 * construction, and it means the value set can grow with no schema migration and no contract
 * break.</p>
 *
 * <h2>There is deliberately no {@code PAUSED}</h2>
 * <p>No durable pause state exists in the product yet: pause and resume act on the runtime's
 * in-memory active-execution map, and {@link ProcessInstanceStatus} has no {@code PAUSED} member, so
 * a paused instance is durably indistinguishable from a running one and a {@code PAUSED} constant
 * here could only ever be returned by guessing. The work that makes pause durable adds the constant
 * then. Because the disposition is derived rather than stored, that addition needs no schema
 * migration, no backfill and no change to any row already written — which is the whole reason this
 * is computed rather than persisted.</p>
 *
 * <h2>Precedence</h2>
 * <p>More than one constant can describe the same row, so the mapping fixes a total order and applies
 * the first that matches:</p>
 * <ol>
 *   <li><strong>{@link #PARKED}</strong> — a parked attempt is an effect that was dispatched and whose
 *   outcome was never learned, and it leaves the claim loop permanently until a human decides about
 *   the past. It outranks even {@link #TERMINAL} because an instance can reach {@code FAILED} while
 *   still carrying a parked attempt: the instance is finished, the unresolved real-world effect is
 *   not, and labelling the row terminal would hide the only outstanding operator action behind a
 *   status that reads as "nothing left to do" — and would then let retention delete the sole record
 *   of it.</li>
 *   <li><strong>{@link #TERMINAL}</strong> — {@code COMPLETED} or {@code FAILED}. Nothing will run
 *   again, so no recovery classification below it can be meaningful.</li>
 *   <li><strong>{@link #WAITING}</strong> — outranks {@link #INTERRUPTED} because a waiting instance
 *   holds no lease <em>by design</em>: it is parked on an asynchronous continuation, not abandoned by
 *   a dead worker. Ranking the lease test first would classify every correctly idle instance as
 *   needing recovery and bury the cohort that genuinely does.</li>
 *   <li><strong>{@link #INTERRUPTED}</strong> — non-terminal with no live lease. This is the
 *   restart-recovery cohort.</li>
 *   <li><strong>{@link #ACTIVE}</strong> — non-terminal with a live lease.</li>
 * </ol>
 * <p>The last two are complementary by the lease predicate and the first three are mutually exclusive
 * by status, so only the {@code PARKED} and {@code WAITING} ranks change any answer. The other two
 * ranks are stated anyway, because a total order is what makes the mapping reproducible by a second
 * adapter rather than a set of overlapping rules each implementation resolves its own way.</p>
 */
public enum InventoryDisposition {

    /** Non-terminal and covered by a lease that has not expired on the store's clock. */
    ACTIVE,

    /** Status is {@link ProcessInstanceStatus#WAITING}: idle on purpose, awaiting a continuation. */
    WAITING,

    /**
     * Non-terminal with no lease, or with one that has expired. The restart-recovery cohort: whatever
     * owned this row is not renewing, so it is either gone or was never started.
     */
    INTERRUPTED,

    /**
     * At least one attempt is in {@link ai.ravenroot.api.application.NodeAttemptStatus#PARKED}: an
     * effect of unknown outcome awaits a human decision (ADR 0022).
     */
    PARKED,

    /** {@link ProcessInstanceStatus#COMPLETED} or {@link ProcessInstanceStatus#FAILED}. */
    TERMINAL;

    /**
     * Classifies one process instance under the precedence documented on this type.
     *
     * @param status           the authoritative stored lifecycle status
     * @param leaseLive        whether a lease exists whose expiry is strictly after the store's now
     * @param anyAttemptParked whether any attempt anywhere in the instance is parked
     * @return the highest-precedence disposition that describes the instance
     */
    public static InventoryDisposition ofProcess(ProcessInstanceStatus status, boolean leaseLive,
                                                 boolean anyAttemptParked) {
        if (status == null) {
            throw new IllegalArgumentException("status cannot be null");
        }
        if (anyAttemptParked) {
            return PARKED;
        }
        if (status.terminal()) {
            return TERMINAL;
        }
        if (status == ProcessInstanceStatus.WAITING) {
            return WAITING;
        }
        return leaseLive ? ACTIVE : INTERRUPTED;
    }

    /**
     * Classifies one traversal under the same precedence, against its own status and its own parked
     * attempts.
     *
     * <p>{@code leaseLive} is the <em>instance's</em> lease, because a lease is taken on the process
     * instance and never on a traversal. Two traversals of one instance therefore share the
     * {@link #ACTIVE}/{@link #INTERRUPTED} half of the answer and differ only where their own status
     * or their own parked attempts differ, which is exactly the relationship the durable rows
     * express.</p>
     *
     * @param status           the authoritative stored traversal status
     * @param leaseLive        whether the containing instance holds an unexpired lease
     * @param anyAttemptParked whether any attempt within this traversal is parked
     * @return the highest-precedence disposition that describes the traversal
     */
    public static InventoryDisposition ofTraversal(TraversalStatus status, boolean leaseLive,
                                                   boolean anyAttemptParked) {
        if (status == null) {
            throw new IllegalArgumentException("status cannot be null");
        }
        if (anyAttemptParked) {
            return PARKED;
        }
        if (status.terminal()) {
            return TERMINAL;
        }
        if (status == TraversalStatus.WAITING) {
            return WAITING;
        }
        return leaseLive ? ACTIVE : INTERRUPTED;
    }
}
