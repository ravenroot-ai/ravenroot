package ai.ravenroot.api.persistence;

import java.time.Instant;

/**
 * Ownership of one process instance for a bounded window (ADR 0010 sections 4 and 5).
 *
 * <p>Expiry is evaluated against the <em>store's</em> clock, never the caller's, and lazily at claim
 * time; no adapter is required to run a background reaper. Both instants are reported by the store
 * so a caller can observe how much of its window remains without consulting a local clock.</p>
 * @param key the stable key used to identify the requested resource.
 * @param workerId the stable worker id used to identify the requested resource.
 * @param fencingToken the stable fencing token used to identify the requested resource.
 * @param claimedAt instant at which the lease was claimed.
 * @param expiresAt instant at which the lease expires.
 */
public record LeaseHandle(ExecutionKey key, String workerId, long fencingToken,
                          Instant claimedAt, Instant expiresAt) {
    /** Validates the worker lease used to fence concurrent execution. */
    public LeaseHandle {
        if (key == null) throw new IllegalArgumentException("key cannot be null");
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("workerId cannot be blank");
        if (claimedAt == null) throw new IllegalArgumentException("claimedAt cannot be null");
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt cannot be null");
        if (expiresAt.isBefore(claimedAt)) {
            throw new IllegalArgumentException("expiresAt cannot precede claimedAt");
        }
    }
}
