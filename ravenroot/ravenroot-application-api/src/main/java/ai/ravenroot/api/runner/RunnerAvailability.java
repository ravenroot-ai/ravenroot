package ai.ravenroot.api.runner;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Store-clock worker incarnation lease. This advertises capacity, never approval or authority.
 * @param tenantId authenticated worker tenant
 * @param runnerId approved worker registration name
 * @param sessionId unique supervisor boot incarnation
 * @param capacity operator-configured concurrent job slots
 * @param activeJobs currently occupied supervisor slots
 * @param runtimeProfiles installed immutable runtime profile bindings
 * @param observedAt authoritative store observation time
 * @param leaseUntil exclusive store-clock liveness deadline
 */
public record RunnerAvailability(String tenantId, String runnerId, UUID sessionId, int capacity, int activeJobs,
                                 Set<String> runtimeProfiles, Instant observedAt, Instant leaseUntil) {
    /** Rejects empty runtimes, invalid slot counts and nonpositive liveness intervals. */
    public RunnerAvailability {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("worker tenant required");
        runnerId = RunnerPolicy.identifier(runnerId); Objects.requireNonNull(sessionId);
        runtimeProfiles = RunnerPolicy.identifiers(runtimeProfiles);
        Objects.requireNonNull(observedAt); Objects.requireNonNull(leaseUntil);
        if (capacity < 1 || activeJobs < 0 || activeJobs > capacity || runtimeProfiles.isEmpty() || !leaseUntil.isAfter(observedAt))
            throw new IllegalArgumentException("invalid worker availability");
    }
    /**
     * Tests the exclusive store-clock liveness deadline.
     * @param now authoritative store time
     * @return true before, but not at, lease expiry
     */
    public boolean live(Instant now) { return now.isBefore(leaseUntil); }
    /**
     * Renews an incarnation without permitting takeover of another live supervisor.
     * @param previous previously persisted advertisement, or null for first registration
     * @param ttl operator-selected positive advertisement lifetime
     * @param now authoritative store time
     * @return advertisement restamped with store time and its new expiry
     */
    public RunnerAvailability renew(RunnerAvailability previous, Duration ttl, Instant now) {
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("worker lease duration must be positive");
        if (previous != null && (!tenantId.equals(previous.tenantId) || !runnerId.equals(previous.runnerId)
                || previous.live(now) && !sessionId.equals(previous.sessionId)))
            throw new IllegalStateException("worker incarnation is already owned by a live session");
        return new RunnerAvailability(tenantId, runnerId, sessionId, capacity, activeJobs, runtimeProfiles, now, now.plus(ttl));
    }
}
