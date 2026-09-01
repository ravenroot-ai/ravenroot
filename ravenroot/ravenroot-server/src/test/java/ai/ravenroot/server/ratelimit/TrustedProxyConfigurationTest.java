package ai.ravenroot.server.ratelimit;

import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spoofing surface of per-address limiting.
 *
 * <p>If a caller can choose the address its limit is keyed on, it gets a fresh empty bucket per request
 * and the limiter no longer exists. Every test here is a way a caller might try to do that.</p>
 */
class TrustedProxyConfigurationTest {
    private static final InetSocketAddress PROXY = new InetSocketAddress("10.0.0.1", 443);
    private static final InetSocketAddress DIRECT = new InetSocketAddress("198.51.100.9", 5555);

    @Test
    void withoutConfigurationTheForwardedHeaderIsNeverRead() {
        var direct = TrustedProxyConfiguration.direct();

        var resolution = direct.resolve(DIRECT, List.of("203.0.113.1, 203.0.113.2"));

        assertEquals("198.51.100.9", client(resolution).address(),
                "the default configuration honoured X-Forwarded-For, so any caller could pick its own key");
        assertTrue(!client(resolution).forwarded());
    }

    @Test
    void anUntrustedPeerCannotAssertAForwardedClient() {
        var behindOneProxy = new TrustedProxyConfiguration(1, Set.of("10.0.0.1"));

        var resolution = behindOneProxy.resolve(DIRECT, List.of("203.0.113.1"));

        assertEquals("198.51.100.9", client(resolution).address(),
                "a peer that is not a declared proxy had its forwarding header believed");
    }

    /** The header the client itself prepended must be discarded, not selected. */
    @Test
    void aSpoofedEntryPrependedByTheClientIsIgnored() {
        var behindOneProxy = new TrustedProxyConfiguration(1, Set.of("10.0.0.1"));

        // The proxy appended the address it actually saw, so the chain is [spoofed, real].
        var resolution = behindOneProxy.resolve(PROXY, List.of("1.2.3.4, 203.0.113.7"));

        assertEquals("203.0.113.7", client(resolution).address(),
                "the client-supplied entry was selected instead of the one the trusted proxy wrote");
        assertTrue(client(resolution).forwarded());
    }

    @Test
    void aLongSpoofedPrefixCannotShiftTheSelectedEntry() {
        var behindTwoProxies = new TrustedProxyConfiguration(2, Set.of("10.0.0.1"));

        var resolution = behindTwoProxies.resolve(PROXY,
                List.of("9.9.9.1, 9.9.9.2, 9.9.9.3, 203.0.113.7, 10.0.0.2"));

        assertEquals("203.0.113.7", client(resolution).address(),
                "padding the chain moved the selection away from the trusted suffix");
    }

    /** Header values split across multiple instances must be one ordered chain, not just the first. */
    @Test
    void multipleHeaderInstancesAreOneOrderedChain() {
        var behindOneProxy = new TrustedProxyConfiguration(1, Set.of("10.0.0.1"));

        var resolution = behindOneProxy.resolve(PROXY, List.of("1.2.3.4", "203.0.113.7"));

        assertEquals("203.0.113.7", client(resolution).address(),
                "only the first X-Forwarded-For instance was parsed, so a second instance could hide the real client");
    }

    /**
     * A chain shorter than the configured topology is rejected rather than quietly downgraded.
     * Falling back to the peer would key every client behind the proxy onto one bucket.
     */
    @Test
    void aChainShorterThanTheConfiguredHopCountIsRejected() {
        var behindTwoProxies = new TrustedProxyConfiguration(2, Set.of("10.0.0.1"));

        var resolution = behindTwoProxies.resolve(PROXY, List.of("203.0.113.7"));

        assertEquals("FORWARDED_CHAIN_TOO_SHORT", rejected(resolution).code());
    }

    @Test
    void aMissingChainFromATrustedProxyIsRejected() {
        var behindOneProxy = new TrustedProxyConfiguration(1, Set.of("10.0.0.1"));

        assertEquals("FORWARDED_CHAIN_TOO_SHORT", rejected(behindOneProxy.resolve(PROXY, List.of())).code());
    }

    @Test
    void anAbusivelyLongChainIsRejectedRatherThanParsed() {
        var behindOneProxy = new TrustedProxyConfiguration(1, Set.of("10.0.0.1"));
        var chain = new StringBuilder();
        for (int index = 0; index < TrustedProxyConfiguration.MAX_CHAIN_ENTRIES + 5; index++) {
            chain.append("203.0.113.1,");
        }

        assertEquals("FORWARDED_CHAIN_TOO_LONG",
                rejected(behindOneProxy.resolve(PROXY, List.of(chain.toString()))).code());
    }

    @Test
    void anOversizedForwardedHeaderIsRejectedBeforeItIsSplit() {
        var behindOneProxy = new TrustedProxyConfiguration(1, Set.of("10.0.0.1"));
        String huge = "203.0.113.1".repeat(TrustedProxyConfiguration.MAX_CHAIN_BYTES);

        assertEquals("FORWARDED_CHAIN_TOO_LONG",
                rejected(behindOneProxy.resolve(PROXY, List.of(huge))).code());
    }

    /** A hostname must never be resolved: that would make a header into an outbound DNS query. */
    @Test
    void aHostnameInTheChainIsRejectedRatherThanResolved() {
        var behindOneProxy = new TrustedProxyConfiguration(1, Set.of("10.0.0.1"));

        assertEquals("FORWARDED_CLIENT_INVALID",
                rejected(behindOneProxy.resolve(PROXY, List.of("attacker.example.com"))).code());
    }

    @Test
    void anObfuscatedOrNonCanonicalAddressIsRejected() {
        var behindOneProxy = new TrustedProxyConfiguration(1, Set.of("10.0.0.1"));

        assertEquals("FORWARDED_CLIENT_INVALID",
                rejected(behindOneProxy.resolve(PROXY, List.of("_hidden"))).code());
        assertEquals("FORWARDED_CLIENT_INVALID",
                rejected(behindOneProxy.resolve(PROXY, List.of("010.0.0.1"))).code(),
                "an octal-looking octet was accepted, so one address has two spellings and two buckets");
        assertEquals("FORWARDED_CLIENT_INVALID",
                rejected(behindOneProxy.resolve(PROXY, List.of("999.1.1.1"))).code());
    }

    /** Different spellings of one address must collapse onto one key, or they are separate buckets. */
    @Test
    void equivalentAddressSpellingsCanonicaliseToOneKey() {
        assertEquals(IpAddresses.canonicalOrNull("::1"), IpAddresses.canonicalOrNull("0:0:0:0:0:0:0:1"));
        assertEquals(IpAddresses.canonicalOrNull("203.0.113.7"), IpAddresses.canonicalOrNull("203.0.113.7:8443"));
        assertEquals(IpAddresses.canonicalOrNull("::1"), IpAddresses.canonicalOrNull("[::1]:443"));
    }

    @Test
    void aPortSuffixDoesNotCreateASeparateBucketPerConnection() {
        var behindOneProxy = new TrustedProxyConfiguration(1, Set.of("10.0.0.1"));

        String first = client(behindOneProxy.resolve(PROXY, List.of("203.0.113.7:11111"))).address();
        String second = client(behindOneProxy.resolve(PROXY, List.of("203.0.113.7:22222"))).address();

        assertEquals(first, second,
                "the ephemeral source port became part of the key, so every connection got a fresh bucket");
    }

    @Test
    void hopsWithoutDeclaredProxyAddressesIsAConfigurationError() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new TrustedProxyConfiguration(1, Set.of()));
        assertTrue(failure.getMessage().contains("RAVENROOT_TRUSTED_PROXY_ADDRESSES"));
    }

    @Test
    void declaredProxyAddressesWithoutHopsIsAConfigurationError() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrustedProxyConfiguration(0, Set.of("10.0.0.1")));
    }

    @Test
    void environmentDefaultsToTrustingNothing() {
        var fromEmpty = TrustedProxyConfiguration.fromEnvironment(Map.of());

        assertEquals(0, fromEmpty.trustedHops());
        assertTrue(fromEmpty.trustedPeers().isEmpty());
    }

    @Test
    void environmentRejectsHostnamesInTheTrustedProxyList() {
        assertThrows(IllegalArgumentException.class, () -> TrustedProxyConfiguration.fromEnvironment(
                Map.of("RAVENROOT_TRUSTED_PROXY_HOPS", "1",
                        "RAVENROOT_TRUSTED_PROXY_ADDRESSES", "proxy.internal")));
    }

    @Test
    void forwardedForReadsEveryHeaderInstance() {
        var headers = new Headers();
        headers.add("X-Forwarded-For", "1.1.1.1");
        headers.add("X-Forwarded-For", "2.2.2.2");

        assertEquals(List.of("1.1.1.1", "2.2.2.2"), TrustedProxyConfiguration.forwardedFor(headers));
    }

    private static TrustedProxyConfiguration.Resolution.Client client(
            TrustedProxyConfiguration.Resolution resolution) {
        return assertInstanceOf(TrustedProxyConfiguration.Resolution.Client.class, resolution);
    }

    private static TrustedProxyConfiguration.Resolution.Rejected rejected(
            TrustedProxyConfiguration.Resolution resolution) {
        return assertInstanceOf(TrustedProxyConfiguration.Resolution.Rejected.class, resolution);
    }
}
