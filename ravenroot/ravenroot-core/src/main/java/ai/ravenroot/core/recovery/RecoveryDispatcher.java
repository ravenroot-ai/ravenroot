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
 * <p>Generic recovered attempts still require a host-specific dispatcher. A reserved handler may
 * instead be paired with a trusted bounded-continuation dispatcher. {@link #NONE} is the additive,
 * fail-closed default: unsupported work stays claimable and unacknowledged rather than being lost or
 * spuriously completed.</p>
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

    /**
     * Releases dispatcher-owned resources after recovery has acknowledged the claimed work.
     *
     * <p>The recovery service remains the sole owner of the store acknowledgement. Dispatchers
     * that must retain a fenced process lease until that acknowledgement completes can release it
     * here. The default is deliberately additive for existing dispatchers.</p>
     */
    default void afterAcknowledged(PendingWork item) { }

    /** Declines everything for hosts that install no recovery dispatcher. */
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
