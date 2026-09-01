package ai.ravenroot.server.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a per-address limit is actually charged against.
 *
 * <p>An address that one party fully controls a whole range of is not an identity. A host with a
 * routed IPv6 /64 can source every request from a different /128 and, keyed on the full address, each
 * one gets a fresh empty bucket — so the pre-authentication budget stops existing — while the bounded
 * address registry fills with entries that all belong to the same party, at which point genuinely new
 * clients are refused. Both failures come from the same key, so they are fixed by the same key.</p>
 */
class AddressBucketKeyTest {
    private final AtomicLong nanos = new AtomicLong();

    /** Many addresses inside one /64 are one client, and must share one bucket. */
    @Test
    void everyAddressInOneIpv6PrefixSharesOneBucket() {
        String first = IpAddresses.rateLimitKey(IpAddresses.canonicalOrNull("2001:db8::1"));
        String last = IpAddresses.rateLimitKey(
                IpAddresses.canonicalOrNull("2001:db8:0:0:ffff:ffff:ffff:ffff"));
        String middle = IpAddresses.rateLimitKey(IpAddresses.canonicalOrNull("[2001:0DB8::dead:beef]:443"));

        assertEquals("2001:db8:0:0:0:0:0:0/64", first);
        assertEquals(first, last);
        assertEquals(first, middle);
        assertNotEquals(first, IpAddresses.rateLimitKey(IpAddresses.canonicalOrNull("2001:db8:0:1::1")),
                "a neighbouring /64 is a different party and must not share the bucket");
    }

    /**
     * The measured failure, at the shipping defaults.
     *
     * <p>Twenty thousand addresses from one /64 used to allocate ten thousand entries — the whole
     * registry ceiling — and then refuse everything, including clients that had done nothing.</p>
     */
    @Test
    void aSingleIpv6HostCannotFillTheAddressRegistryOrEvadeItsBudget() {
        var limits = RateLimitConfiguration.DEFAULTS;
        try (var limiter = new RateLimiter(limits, TrustedProxyConfiguration.direct(),
                RateLimitAuditSink.discarding(), nanos::get)) {
            int allowed = 0;
            for (int index = 0; index < 20_000; index++) {
                String address = IpAddresses.canonicalOrNull(
                        "2001:db8::" + Integer.toHexString(index / 65_536) + ":" + Integer.toHexString(index % 65_536));
                if (limiter.checkAddress(address).isAllowed()) {
                    allowed++;
                }
            }

            assertEquals(1, limiter.trackedClients(),
                    "one host's /64 allocated more than one bucket, so the registry is fillable from one host");
            assertEquals(limits.addressBurst(), allowed,
                    "the whole prefix was served beyond one client's burst, so the per-address budget "
                            + "can be evaded by changing the low 64 bits");

            var newcomer = limiter.checkAddress(IpAddresses.canonicalOrNull("198.51.100.9"));

            assertTrue(newcomer.isAllowed(),
                    "a brand-new legitimate client was refused because one IPv6 host had filled the registry");
        }
    }

    /**
     * IPv4-mapped addresses must keep landing on their IPv4 bucket.
     *
     * <p>This is the property the /64 change could most plausibly have broken: aggregating a mapped
     * address onto a /64 of {@code ::ffff:0:0/96} would put every unrelated IPv4 client into one
     * bucket, which is the same starvation bug in the opposite direction.</p>
     */
    @Test
    void ipv4MappedAddressesKeyOnTheirIpv4IdentityAndNotOnAPrefix() {
        String expected = "203.0.113.7";
        for (String spelling : java.util.List.of("::ffff:203.0.113.7", "::FFFF:203.0.113.7",
                "0:0:0:0:0:ffff:203.0.113.7", "::ffff:cb00:7107", "203.0.113.7")) {
            String canonical = IpAddresses.canonicalOrNull(spelling);
            assertEquals(expected, canonical, spelling + " no longer canonicalises to its IPv4 identity");
            assertEquals(expected, IpAddresses.rateLimitKey(canonical),
                    spelling + " was aggregated onto a prefix instead of keying on its IPv4 address");
        }
        assertNotEquals(IpAddresses.rateLimitKey(IpAddresses.canonicalOrNull("::ffff:203.0.113.7")),
                IpAddresses.rateLimitKey(IpAddresses.canonicalOrNull("::ffff:198.51.100.9")),
                "two unrelated IPv4 clients were merged into one bucket");
    }

    /** IPv4 keys on the address itself: an operator assigns single IPv4 addresses, not blocks per host. */
    @Test
    void ipv4AddressesAreNotAggregated() {
        assertEquals("203.0.113.7", IpAddresses.rateLimitKey("203.0.113.7"));
        assertNotEquals(IpAddresses.rateLimitKey("203.0.113.7"), IpAddresses.rateLimitKey("203.0.113.8"));
        assertNull(IpAddresses.rateLimitKey(null));
    }

    /** The canonicalisation the key derives from must keep rejecting everything it rejected before. */
    @Test
    void nonCanonicalAndNonLiteralValuesAreStillRejected() {
        for (String rejected : java.util.List.of("010.0.0.1", "203.0.113.07", "0x7f.0.0.1", "2130706433",
                "203.0.113.7.", "proxy.example.com", "")) {
            assertNull(IpAddresses.canonicalOrNull(rejected), rejected + " was accepted as an IP literal");
        }
    }

    /**
     * The full address survives for the audit record even though the bucket key does not.
     *
     * <p>An operator blocks or investigates a host, not a prefix, so aggregating the audit field would
     * trade a real capability for nothing.</p>
     */
    @Test
    void resolutionStillReportsTheFullAddress() {
        var resolution = TrustedProxyConfiguration.direct().resolve(
                new java.net.InetSocketAddress(
                        java.net.InetAddress.getLoopbackAddress(), 1), java.util.List.of());

        assertTrue(resolution instanceof TrustedProxyConfiguration.Resolution.Client);
        var client = (TrustedProxyConfiguration.Resolution.Client) resolution;
        assertFalse(client.address().endsWith("/64"), "the resolved client address was aggregated");
    }

    /** Aggregation must not weaken the retention bound it was introduced to protect. */
    @Test
    void aggregatedKeysAreStillIdleEvicted() {
        var limits = RateLimitConfiguration.DEFAULTS;
        try (var limiter = new RateLimiter(limits, TrustedProxyConfiguration.direct(),
                RateLimitAuditSink.discarding(), nanos::get)) {
            limiter.checkAddress(IpAddresses.canonicalOrNull("2001:db8::1"));
            assertEquals(1, limiter.trackedClients());

            nanos.addAndGet(limits.idleEntryTtl().plus(Duration.ofSeconds(1)).toNanos());
            limiter.sweep();

            assertEquals(0, limiter.trackedClients(), "an aggregated bucket was retained past its idle TTL");
        }
    }
}
