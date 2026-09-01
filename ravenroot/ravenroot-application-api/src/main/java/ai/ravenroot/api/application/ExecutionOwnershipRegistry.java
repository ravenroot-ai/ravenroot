package ai.ravenroot.api.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded insertion-ordered ownership registry. Evicted ownership becomes unknown and fails closed. */
final class ExecutionOwnershipRegistry {
    private final int capacity;
    private final Map<UUID, Ownership> owners = new LinkedHashMap<>();

    ExecutionOwnershipRegistry(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("execution ownership capacity must be positive");
        }
        this.capacity = capacity;
    }

    synchronized Reservation reserve(UUID executionId, String tenantId) {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(tenantId, "tenantId");
        Ownership existing = owners.get(executionId);
        if (existing != null) {
            throw new IllegalStateException("execution identifier ownership collision");
        }

        Map.Entry<UUID, Ownership> displaced = null;
        if (owners.size() == capacity) {
            displaced = owners.entrySet().stream()
                    .filter(entry -> entry.getValue().committed())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("execution ownership registry is busy"));
            owners.remove(displaced.getKey());
        }
        var ownership = new Ownership(tenantId, false, false);
        owners.put(executionId, ownership);
        return new Reservation(executionId, ownership,
                displaced == null ? null : displaced.getKey(),
                displaced == null ? null : displaced.getValue());
    }

    synchronized void commit(Reservation reservation) {
        Reservation actual = Objects.requireNonNull(reservation, "reservation");
        if (owners.get(actual.executionId()) != actual.ownership()) {
            throw new IllegalStateException("execution ownership reservation is no longer active");
        }
        owners.put(actual.executionId(), new Ownership(actual.ownership().tenantId(), true,
                actual.ownership().cancelled()));
    }

    synchronized void rollback(Reservation reservation) {
        Reservation actual = Objects.requireNonNull(reservation, "reservation");
        if (owners.get(actual.executionId()) != actual.ownership()) {
            return;
        }
        owners.remove(actual.executionId());
        if (actual.displacedId() != null && !owners.containsKey(actual.displacedId())) {
            var restored = new LinkedHashMap<UUID, Ownership>();
            restored.put(actual.displacedId(), actual.displacedOwnership());
            restored.putAll(owners);
            owners.clear();
            owners.putAll(restored);
        }
    }

    synchronized String owner(UUID executionId) {
        Ownership ownership = executionId == null ? null : owners.get(executionId);
        return ownership == null ? null : ownership.tenantId();
    }

    /**
     * Marks {@code executionId} cancelled, idempotently. Co-located with ownership rather than a
     * second, independently bounded map deliberately: this map is already the one bounded, LRU-evicted
     * structure keyed by execution id, so a cancelled marker rides the exact same eviction as the
     * ownership it is attached to. A second cache with its own capacity and its own eviction clock could
     * disagree with this one about which ids it still remembers, which would silently reintroduce the
     * "completed vs. never-existed" ambiguity this exists to remove.
     *
     * @return {@code true} if this call is what newly marked it cancelled, {@code false} if it was
     *         already cancelled (idempotent -- the caller's cancel is a repeat) or if {@code executionId}
     *         has no known owner (evicted or never reserved -- unknown, and the caller must have already
     *         failed the request closed via {@link ai.ravenroot.api.security.ProtectedResource#unknownOwnership}
     *         before reaching this, since this method itself makes no authorization decision)
     */
    synchronized boolean markCancelled(UUID executionId) {
        Ownership existing = executionId == null ? null : owners.get(executionId);
        if (existing == null || existing.cancelled()) {
            return false;
        }
        owners.put(executionId, new Ownership(existing.tenantId(), existing.committed(), true));
        return true;
    }

    /** Whether {@code executionId} has been marked cancelled; {@code false} for an unknown id too. */
    synchronized boolean isCancelled(UUID executionId) {
        Ownership existing = executionId == null ? null : owners.get(executionId);
        return existing != null && existing.cancelled();
    }

    synchronized int size() {
        return owners.size();
    }

    record Reservation(UUID executionId, Ownership ownership, UUID displacedId, Ownership displacedOwnership) {
    }

    private record Ownership(String tenantId, boolean committed, boolean cancelled) {
    }
}
