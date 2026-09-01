package ai.ravenroot.api.application;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionOwnershipRegistryTest {
    @Test
    void enforcesPositiveCapacityAndDeterministicallyEvictsTheEldestInsertion() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionOwnershipRegistry(0));
        var registry = new ExecutionOwnershipRegistry(2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        registry.commit(registry.reserve(first, "tenant-a"));
        registry.commit(registry.reserve(second, "tenant-b"));
        assertEquals(2, registry.size());
        registry.commit(registry.reserve(third, "tenant-c"));

        assertEquals(2, registry.size());
        assertNull(registry.owner(first));
        assertEquals("tenant-b", registry.owner(second));
        assertEquals("tenant-c", registry.owner(third));
    }

    @Test
    void repeatedIdentifierNeverChangesOwnershipOrInsertionOrder() {
        var registry = new ExecutionOwnershipRegistry(1);
        UUID execution = UUID.randomUUID();
        registry.commit(registry.reserve(execution, "tenant-a"));
        assertThrows(IllegalStateException.class, () -> registry.reserve(execution, "tenant-b"));
        assertEquals("tenant-a", registry.owner(execution));
        assertEquals(1, registry.size());
    }

    @Test
    void exposesReservedOwnershipAndRestoresDisplacedEntryOnRollback() {
        var registry = new ExecutionOwnershipRegistry(1);
        UUID retained = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        registry.commit(registry.reserve(retained, "tenant-a"));

        var reservation = registry.reserve(failed, "tenant-b");
        assertEquals("tenant-b", registry.owner(failed),
                "ownership must be definitive before trusted execution startup");
        assertNull(registry.owner(retained));

        registry.rollback(reservation);
        assertEquals("tenant-a", registry.owner(retained));
        assertNull(registry.owner(failed));
        assertEquals(1, registry.size());
    }

    @Test
    void refusesToEvictAnInFlightReservation() {
        var registry = new ExecutionOwnershipRegistry(1);
        UUID first = UUID.randomUUID();
        var reservation = registry.reserve(first, "tenant-a");

        assertThrows(IllegalStateException.class, () -> registry.reserve(UUID.randomUUID(), "tenant-b"));
        assertEquals("tenant-a", registry.owner(first));
        registry.rollback(reservation);
        assertEquals(0, registry.size());
    }

    // ---- API-02: markCancelled / isCancelled -----------------------------------------------

    @Test
    void markCancelledIsFalseUntilTheFirstCallAndTrueOnEveryCallAfter() {
        var registry = new ExecutionOwnershipRegistry(4);
        UUID execution = UUID.randomUUID();
        registry.commit(registry.reserve(execution, "tenant-a"));

        assertFalse(registry.isCancelled(execution));
        assertTrue(registry.markCancelled(execution), "the first call is what newly marks it cancelled");
        assertTrue(registry.isCancelled(execution));
        assertFalse(registry.markCancelled(execution),
                "a repeat cancel is idempotent: it must not report a second fresh cancellation");
        assertTrue(registry.isCancelled(execution), "still cancelled after the repeat call");
    }

    @Test
    void markCancelledOnAnUnknownIdReturnsFalseAndRecordsNothing() {
        var registry = new ExecutionOwnershipRegistry(4);
        UUID neverReserved = UUID.randomUUID();

        assertFalse(registry.markCancelled(neverReserved),
                "an id this registry never reserved must not be markable cancelled");
        assertFalse(registry.isCancelled(neverReserved));
        assertEquals(0, registry.size(), "marking an unknown id must not create an entry for it");
    }

    /**
     * The exact property the documented contract depends on: eviction removes the cancelled marker along
     * with the ownership it rode in on, so an evicted id reads back as unknown (fails closed), never as
     * "known and not cancelled" (which would misreport a cancellation-capable id as never cancelled).
     */
    @Test
    void evictionClearsTheCancelledMarkerAlongWithOwnership() {
        var registry = new ExecutionOwnershipRegistry(1);
        UUID evicted = UUID.randomUUID();
        UUID displacing = UUID.randomUUID();
        registry.commit(registry.reserve(evicted, "tenant-a"));
        registry.markCancelled(evicted);
        assertTrue(registry.isCancelled(evicted));

        registry.commit(registry.reserve(displacing, "tenant-b"));

        assertNull(registry.owner(evicted));
        assertFalse(registry.isCancelled(evicted), "an evicted id must not still read back as cancelled");
    }
}
