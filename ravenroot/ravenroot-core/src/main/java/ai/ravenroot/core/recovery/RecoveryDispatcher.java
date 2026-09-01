package ai.ravenroot.core.recovery;

import ai.ravenroot.api.persistence.PendingWork;

/**
 * Where recovered work is sent once the recovery loop has decided it is safe to send.
 *
 * <p>The two halves are separate on purpose, and the separation is what preserves the write-ordering
 * invariant without inventing work that cannot happen. {@link #canDispatch(PendingWork)} is asked
 * <em>before</em> the {@code RUNNING} transition is persisted; only if it says yes does the recovery
 * loop persist {@code RUNNING} and then call {@link #dispatch}. A single method would force the loop
 * to write {@code RUNNING} in order to find out whether anything could be sent — and a {@code RUNNING}
 * attempt that was never sent is indistinguishable from one whose outcome is unknown, so the next
 * sweep would park work that had provably never started.</p>
 *
 * <h2>{@link #NONE} is the production wiring today, and that is a scope fact, not an oversight</h2>
 * <p>Re-dispatching a recovered attempt means executing a node of a graph, and the graph bytes are
 * stored nowhere — {@code GraphVersionPin} holds a hash, and no definition store exists. PERS-04's
 * scope is therefore "make pending work dispatchable with correct lease, fencing
 * and idempotency semantics", not "resume execution". {@link #NONE} declines everything, so recovered
 * work stays claimable and unacknowledged, which loses nothing and parks nothing spuriously.</p>
 */
public interface RecoveryDispatcher {

    /** Whether this dispatcher is able to send {@code item} at all. */
    boolean canDispatch(PendingWork item);

    /**
     * Sends {@code item}, presenting {@code idempotencyKey} as the effect identity.
     *
     * <p>The key is the attempt id because the unit of idempotency is the attempt. The same
     * attempt redelivered presents the same key and dedupes through the store's existing
     * {@code IdempotencyWrite} machinery; a deliberate retry is a new attempt with a new id and is
     * therefore a new effect by construction rather than by policy.</p>
     *
     * <p>Called only after the attempt's {@code RUNNING} transition has been durably committed under
     * the fence.</p>
     */
    void dispatch(PendingWork item, String idempotencyKey);

    /** Declines everything. See the class comment for why this is the current production wiring. */
    RecoveryDispatcher NONE = new RecoveryDispatcher() {
        @Override
        public boolean canDispatch(PendingWork item) {
            return false;
        }

        @Override
        public void dispatch(PendingWork item, String idempotencyKey) {
            throw new IllegalStateException("RecoveryDispatcher.NONE dispatches nothing");
        }
    };
}
