package ai.ravenroot.api.persistence;

import java.util.Objects;

/**
 * How far one publisher destination has durably delivered this tenant's journal (ADR 0011, PERS-07).
 *
 * <h2>Why the outbox is a cursor and not a queue of copies</h2>
 * <p>The obvious transactional outbox writes one pending row per event per destination and deletes it
 * on delivery. This one keeps a single journal and gives each destination a durable position over it.
 * The difference is not stylistic:</p>
 * <ul>
 *   <li>Adding a destination later requires no backfill and no migration. Under the row-per-
 *   destination shape, a destination that did not exist when an event was written has no row for it,
 *   so it can never receive it — the events are silently invisible to the new consumer rather than
 *   merely undelivered.</li>
 *   <li>The journal already has to exist and already has to be retained for replay. A second copy of
 *   the same bytes per destination would multiply the storage the retention policy governs, and would
 *   introduce a second thing that could disagree with the first.</li>
 *   <li>Redelivery after a crash is a re-read, not a resurrection. There is no state to repair,
 *   because the position simply did not advance.</li>
 * </ul>
 *
 * <p>The cost, stated rather than glossed: one destination is strictly ordered and strictly
 * sequential, because a cursor is a single position. A destination that needs parallel delivery must
 * be split into several destinations with disjoint scopes. That is the correct trade here — ordering
 * per process instance is a guarantee this platform makes, and parallel delivery within one ordered
 * stream would break it.</p>
 *
 * <h2>Advancing is a compare-and-set</h2>
 * <p>{@link ExecutionStore#advanceOutboxCursor(JournalCursor, long)} takes the cursor the caller
 * <em>read</em> and fails with {@link ExecutionStoreFailure.ConcurrencyConflict} if the stored
 * position has moved since. Two publishers for one destination therefore cannot both advance past
 * the same events, and the loser re-reads rather than skipping work it never delivered. Without the
 * expectation, a slow duplicate publisher could advance the cursor backwards over events the fast one
 * had already passed, or forwards over events nobody delivered — the second being a silent loss.</p>
 *
 * @param tenantId         the tenant whose journal this cursor walks
 * @param destination      opaque publisher identity, e.g. an SSE projection or a broker adapter.
 *                         Opaque because the port must not enumerate transports it does not depend on
 * @param deliveredThrough the highest {@link JournalRecord#journalOffset()} this destination has
 *                         durably delivered. Zero means nothing has been delivered yet, which is
 *                         distinguishable from a truncated journal because offsets start at one
 */
public record JournalCursor(String tenantId, String destination, long deliveredThrough) {

    /** Validates the durable delivery position for one tenant and destination. */
    public JournalCursor {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be blank");
        }
        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("destination cannot be blank");
        }
        if (deliveredThrough < 0) {
            throw new IllegalArgumentException("deliveredThrough cannot be negative");
        }
        Objects.requireNonNull(tenantId);
    }

/**
 * A destination that has never delivered anything.
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @param destination journal destination whose cursor is updated.
 * @return initial cursor for the supplied destination before any journal record is consumed.
 */
    public static JournalCursor start(String tenantId, String destination) {
        return new JournalCursor(tenantId, destination, 0L);
    }
}
