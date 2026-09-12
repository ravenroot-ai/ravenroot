package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One page of the tenant-scoped process inventory.
 *
 * <h2>Determinism while the store keeps accepting work</h2>
 * <p>Rows are ordered by {@code (createdAt descending, processInstanceId descending)}. Both components
 * are immutable for the life of a row, so no row can move between pages while a scan is in flight —
 * which is the property a mutable ordering key such as {@code updatedAt} cannot give, because a row
 * updated mid-scan would jump ahead of the cursor and be returned twice or fall behind it and be
 * skipped. Newly created work sorts <em>before</em> page one and is therefore simply not seen by a
 * scan already under way; that is the honest behaviour of a stable cursor rather than a gap, and a
 * caller that wants the new work asks for page one again.</p>
 *
 * <h2>{@code retainedFrom} is what makes an absence readable</h2>
 * <p>Without it, a caller that fails to find an instance cannot tell "it never existed" from "it
 * completed and its retention window elapsed" — two answers that call for opposite actions. This is
 * the same disclosure {@link ExecutionStore#journalRetainedFrom(String)} makes for events and
 * {@link ExecutionStore#forgottenBefore(String)} makes for idempotency records: an honest floor,
 * published rather than inferred by probing.</p>
 *
 * @param items        the page, in {@code (createdAt DESC, processInstanceId DESC)} order
 * @param nextCursor   opaque, versioned and tenant-bound cursor for the following page; absent when
 *                     this page is the last one
 * @param retainedFrom the tenant's inventory retention floor: the latest retention deadline this
 *                     tenant has actually crossed. Every terminal instance whose deadline is strictly
 *                     after this instant is still present; one whose deadline is at or before it may
 *                     have been purged. Measured in the same {@code retainedUntil} deadlines the rows
 *                     themselves publish, and taken as the latest rather than the earliest boundary
 *                     crossed — see {@link ExecutionStore#inventoryRetainedFrom(String)} for why both
 *                     of those are load-bearing. {@link Instant#MIN} until something is purged
 */
public record ProcessInventoryPage(List<ProcessInventoryEntry> items, Optional<String> nextCursor,
                                   Instant retainedFrom) {

    /** Freezes the page and rejects one that could not describe a real answer. */
    public ProcessInventoryPage {
        items = List.copyOf(items == null ? List.of() : items);
        nextCursor = nextCursor == null ? Optional.empty() : nextCursor;
        if (retainedFrom == null) {
            throw new IllegalArgumentException("retainedFrom cannot be null");
        }
    }
}
