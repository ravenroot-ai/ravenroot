package ai.ravenroot.server.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the limiter's retained state is bounded — the property a green functional suite cannot see.
 *
 * <p>Every functional test drives a handful of keys. A limiter that keeps one entry per key it has ever
 * observed passes all of them and still exhausts memory in production, because its keys are client
 * addresses, tenants and principals: all supplied, directly or indirectly, by whoever is sending the
 * traffic. These tests drive key counts far above every configured ceiling and assert on
 * {@code trackedX()} rather than on behaviour, so they fail on retention alone.</p>
 *
 * <p>Time is a fake monotonic clock, so eviction is driven by arithmetic rather than by sleeping. The
 * whole class is therefore deterministic and carries no timing flakiness.</p>
 */
class RateLimiterRetentionTest {
    private static final int DISTINCT_KEYS = 200_000;

    private final AtomicLong clock = new AtomicLong();

    @Test
    void distinctClientAddressesCannotGrowRetainedStateBeyondTheConfiguredCeiling() {
        var configuration = Limits.registries(500, 200, 500);
        try (var limiter = limiter(configuration)) {
            for (int index = 0; index < DISTINCT_KEYS; index++) {
                limiter.checkAddress("address-" + index);
                tick();
            }

            assertTrue(limiter.trackedClients() <= configuration.maxTrackedClients(),
                    "retained address entries " + limiter.trackedClients() + " exceeded the ceiling "
                            + configuration.maxTrackedClients() + " after " + DISTINCT_KEYS + " distinct keys");
        }
    }

    @Test
    void distinctTenantsCannotGrowRetainedStateBeyondTheConfiguredCeiling() {
        var configuration = Limits.registries(500, 200, 500);
        try (var limiter = limiter(configuration)) {
            for (int index = 0; index < DISTINCT_KEYS; index++) {
                limiter.checkIdentity("tenant-" + index, "subject-" + index);
                limiter.checkSubmissionRate("tenant-" + index);
                tick();
            }

            assertTrue(limiter.trackedTenants() <= configuration.maxTrackedTenants(),
                    "retained tenant entries " + limiter.trackedTenants() + " exceeded the ceiling "
                            + configuration.maxTrackedTenants());
        }
    }

    /**
     * Fills the principal registry specifically. The tenant budget is raised out of the way first,
     * because a throttled tenant short-circuits before the principal bucket is ever consulted and the
     * principal ceiling would then be asserted against an empty map.
     */
    @Test
    void distinctPrincipalsInsideOneTenantCannotGrowRetainedStateBeyondTheConfiguredCeiling() {
        var configuration = Limits.generousTenantBudget(500, 200, 500);
        try (var limiter = limiter(configuration)) {
            for (int index = 0; index < DISTINCT_KEYS; index++) {
                limiter.checkIdentity("tenant-a", "subject-" + index);
                tick();
            }

            assertTrue(limiter.trackedPrincipals() > 0, "the principal registry was never exercised");
            assertTrue(limiter.trackedPrincipals() <= configuration.maxTrackedPrincipals(),
                    "retained principal entries " + limiter.trackedPrincipals() + " exceeded the ceiling "
                            + configuration.maxTrackedPrincipals());
        }
    }

    /**
     * The concurrency registries are where a leak is silent: a gate whose permit is never returned stays
     * un-evictable forever, which is how an execution that never terminates becomes a permanent entry.
     */
    @Test
    void concurrencySlotsAreReleasedAndTheirRegistriesStayBounded() {
        var configuration = Limits.registries(500, 200, 500);
        try (var limiter = limiter(configuration)) {
            for (int index = 0; index < DISTINCT_KEYS; index++) {
                try (var submission = limiter.acquireSubmissionSlot("tenant-" + index);
                     var stream = limiter.acquireStreamSlot("tenant-" + index, "subject-" + index)) {
                    // Whether each slot was granted is not the point here; releasing it is.
                    assertTrue(submission.granted() || !submission.granted());
                    assertTrue(stream.granted() || !stream.granted());
                }
                tick();
            }

            assertTrue(limiter.trackedTenants() <= configuration.maxTrackedTenants(),
                    "retained tenant gates " + limiter.trackedTenants() + " exceeded the ceiling");
            assertTrue(limiter.trackedPrincipals() <= configuration.maxTrackedPrincipals(),
                    "retained principal gates " + limiter.trackedPrincipals() + " exceeded the ceiling");

            // Every lease above was closed, so after the TTL the registries must be fully reclaimable.
            clock.addAndGet(Duration.ofMinutes(10).toNanos());
            limiter.sweep();
            assertEquals(0, limiter.trackedTenants(),
                    "released gates were still retained after the idle TTL, so a permit leaked");
            assertEquals(0, limiter.trackedPrincipals(),
                    "released principal gates were still retained after the idle TTL");
        }
    }

    /** An unreleased permit must keep its gate alive, or the limit it enforces would silently reset. */
    @Test
    void anUnreleasedSlotKeepsItsGateAndIsNotEvicted() {
        var configuration = Limits.registries(500, 200, 500);
        try (var limiter = limiter(configuration)) {
            var held = limiter.acquireSubmissionSlot("tenant-a");
            assertTrue(held.granted());

            clock.addAndGet(Duration.ofMinutes(10).toNanos());
            limiter.sweep();
            assertEquals(1, limiter.trackedTenants(),
                    "a gate with an outstanding permit was evicted, which would double-issue capacity");

            held.close();
            clock.addAndGet(Duration.ofMinutes(10).toNanos());
            limiter.sweep();
            assertEquals(0, limiter.trackedTenants(), "the gate was not reclaimed after its permit returned");
        }
    }

    /** Idle eviction must reclaim, otherwise the registry rests at its ceiling forever. */
    @Test
    void idleEntriesAreEvictedOnceTheyNoLongerEnforceAnything() {
        var configuration = Limits.registries(500, 200, 500);
        try (var limiter = limiter(configuration)) {
            for (int index = 0; index < 100; index++) {
                limiter.checkAddress("address-" + index);
            }
            assertEquals(100, limiter.trackedClients());

            clock.addAndGet(Duration.ofMinutes(10).toNanos());
            limiter.sweep();

            assertEquals(0, limiter.trackedClients(),
                    "fully replenished, long-idle buckets were retained; steady-state memory would track "
                            + "the historical key count rather than the active one");
        }
    }

    /**
     * A bucket still throttling someone must never be evicted: handing that caller a fresh full bucket
     * would turn the cleanup mechanism into a limit-reset oracle.
     *
     * <p>The rate is set low against a large burst so that the bucket is still refilling long after the
     * idle TTL has passed. That is the only window in which the two conditions — idle for long enough,
     * but not yet replenished — can both be true, and it is exactly the window an eviction bug lives
     * in.</p>
     */
    @Test
    void aDrainedBucketIsNotEvictedWhileItIsStillEnforcingItsLimit() {
        var configuration = Limits.slowRefillingAddressBudget(500, 200, 500);
        try (var limiter = limiter(configuration)) {
            for (int index = 0; index < configuration.addressBurst(); index++) {
                assertTrue(limiter.checkAddress("address-a").isAllowed(), "burst should not be refused");
            }
            assertFalse(limiter.checkAddress("address-a").isAllowed(), "the burst should now be spent");

            // Well past the 60s idle TTL, but far short of the 200s this bucket needs to refill.
            clock.addAndGet(Duration.ofSeconds(90).toNanos());
            limiter.sweep();

            assertEquals(1, limiter.trackedClients(),
                    "a partially drained bucket was evicted, which resets the limit it was enforcing");

            // Non-eviction is necessary but not sufficient: the surviving bucket must also still be
            // drained. At one token per second it has recovered exactly the 90 tokens those 90 seconds
            // bought, not the 200 a fresh bucket would start with.
            int allowed = 0;
            while (limiter.checkAddress("address-a").isAllowed()) {
                allowed++;
            }
            assertEquals(90, allowed,
                    "the surviving bucket did not carry its drained state forward; " + allowed
                            + " requests were allowed where 90 seconds of refill buys exactly 90");
        }
    }

    /** Shutdown must release what accumulated rather than leaving it to an embedding process. */
    @Test
    void closingTheLimiterReleasesEveryRegistry() {
        var limiter = limiter(Limits.registries(500, 200, 500));
        for (int index = 0; index < 100; index++) {
            limiter.checkAddress("address-" + index);
            limiter.checkIdentity("tenant-" + index, "subject-" + index);
        }
        assertTrue(limiter.trackedClients() > 0);
        assertTrue(limiter.trackedTenants() > 0);

        limiter.close();

        assertEquals(0, limiter.trackedClients());
        assertEquals(0, limiter.trackedTenants());
        assertEquals(0, limiter.trackedPrincipals());
    }

    /**
     * An unauthenticated caller can only allocate under the address registry, and only for addresses it
     * genuinely originates from. Forwarding headers from an untrusted peer are ignored, so the retained
     * key count stays at one however many distinct addresses the caller claims.
     */
    @Test
    void anUnauthenticatedCallerCannotMintKeysByClaimingAddresses() {
        try (var limiter = limiter(Limits.registries(500, 200, 500))) {
            var peer = new java.net.InetSocketAddress("198.51.100.9", 4242);
            for (int index = 0; index < 5_000; index++) {
                var headers = new com.sun.net.httpserver.Headers();
                headers.add("X-Forwarded-For", "203.0.113." + (index % 256));
                var resolution = limiter.resolveClient(peer, headers);
                limiter.checkAddress(((TrustedProxyConfiguration.Resolution.Client) resolution).address());
            }

            assertEquals(1, limiter.trackedClients(),
                    "a caller minted extra limiter keys through X-Forwarded-For without being a trusted proxy");
        }
    }

    /**
     * The composite principal key must encode its two parts unambiguously.
     *
     * <p>With a printable separator these two pairs would render identically and share a bucket, so one
     * principal could spend another's budget. Each pair must get its own entry.</p>
     */
    @Test
    void principalsWhoseTenantAndSubjectOverlapDoNotShareABucket() {
        var configuration = Limits.generousTenantBudget(500, 200, 500);
        try (var limiter = limiter(configuration)) {
            limiter.checkIdentity("a b", "c");
            limiter.checkIdentity("a", "b c");

            assertEquals(2, limiter.trackedPrincipals(),
                    "two different principals collapsed onto one rate-limit key");
        }
    }

    private void tick() {
        clock.addAndGet(Duration.ofMillis(1).toNanos());
    }

    private RateLimiter limiter(RateLimitConfiguration configuration) {
        return new RateLimiter(configuration, TrustedProxyConfiguration.direct(),
                RateLimitAuditSink.discarding(), clock::get);
    }

    /** Configuration variants, kept out of the tests so each test body reads as its assertion. */
    private static final class Limits {
        private Limits() {
        }

        /** Defaults with the three registry ceilings lowered so a test can reach them quickly. */
        static RateLimitConfiguration registries(int clients, int tenants, int principals) {
            var defaults = RateLimitConfiguration.DEFAULTS;
            return rebuild(defaults.addressRequestsPerSecond(), defaults.addressBurst(),
                    defaults.tenantRequestsPerSecond(), defaults.tenantBurst(), clients, tenants, principals);
        }

        /** Tenant budget raised out of the way so per-principal state is actually reached. */
        static RateLimitConfiguration generousTenantBudget(int clients, int tenants, int principals) {
            var defaults = RateLimitConfiguration.DEFAULTS;
            return rebuild(defaults.addressRequestsPerSecond(), defaults.addressBurst(),
                    1_000_000, 1_000_000, clients, tenants, principals);
        }

        /** A large burst refilling slowly, so a drained bucket outlives the idle TTL. */
        static RateLimitConfiguration slowRefillingAddressBudget(int clients, int tenants, int principals) {
            var defaults = RateLimitConfiguration.DEFAULTS;
            return rebuild(1, 200, defaults.tenantRequestsPerSecond(), defaults.tenantBurst(),
                    clients, tenants, principals);
        }

        private static RateLimitConfiguration rebuild(int addressRate, int addressBurst, int tenantRate,
                                                      int tenantBurst, int clients, int tenants,
                                                      int principals) {
            var defaults = RateLimitConfiguration.DEFAULTS;
            return new RateLimitConfiguration(
                    addressRate, addressBurst,
                    tenantRate, tenantBurst,
                    defaults.principalRequestsPerSecond(), defaults.principalBurst(),
                    defaults.submissionsPerSecond(), defaults.submissionBurst(),
                    defaults.tenantConcurrentSubmissions(), defaults.globalActiveExecutions(),
                    defaults.tenantConcurrentStreams(), defaults.principalConcurrentStreams(),
                    defaults.streamQueueCapacity(),
                    defaults.maxQueryBytes(), defaults.maxQueryParameters(),
                    defaults.maxHeaderCount(), defaults.maxHeaderBytes(), defaults.maxHeaderValueBytes(),
                    clients, tenants, principals, defaults.idleEntryTtl(), defaults.executionMaxAge());
        }
    }
}
