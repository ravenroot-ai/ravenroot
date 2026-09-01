package ai.ravenroot.core.security.egress;

import ai.ravenroot.core.security.OutboundHttpPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DNS rebinding is driven by a hostile resolver, never by real DNS (SEC-10).
 *
 * <p>A rebinding test that resolves through real DNS proves nothing repeatable: it depends on a
 * zone the suite does not own, on network reachability and on caches nobody controls. Here the
 * upstream name source is replaced with one that answers a reserved address for a name the host
 * allowlist accepts — exactly the attack — and the assertion is that no socket can be opened to it.
 *
 * <p>Every case uses a fresh, unique hostname because the JDK caches both positive and negative
 * resolutions process-wide; reusing a name would let one case's cached answer decide the next.
 */
class DnsRebindingEgressTest {

    private static final AtomicInteger NAMES = new AtomicInteger();

    private EgressAddressGuard.NameSource originalUpstream;
    private ReservedNetworkPolicy originalPolicy;

    @BeforeAll
    static void installProvider() throws Exception {
        // Forces the JDK to instantiate the ServiceLoader-discovered provider, which is what binds
        // the built-in resolver into the guard. A literal address would not do it: literals never
        // reach the resolver SPI at all.
        assertNotNull(InetAddress.getByName("localhost"));
    }

    @BeforeEach
    void captureGlobals() {
        originalUpstream = EgressAddressGuard.upstream();
        originalPolicy = EgressAddressGuard.policy();
    }

    @AfterEach
    void restoreGlobals() {
        EgressAddressGuard.replaceUpstream(originalUpstream);
        EgressAddressGuard.configure(originalPolicy);
    }

    private static String freshName() {
        return "rebind-" + NAMES.incrementAndGet() + "-" + System.nanoTime() + ".test.invalid";
    }

    /** Answers {@code address} for every name, as an attacker-controlled authoritative zone would. */
    private static void hostileResolverReturning(String address) {
        EgressAddressGuard.replaceUpstream((name, policy) -> List.of(InetAddress.getByName(address)));
    }

    @Test
    @DisplayName("an allowlisted name whose DNS returns the cloud metadata address cannot be reached")
    void allowlistedNameResolvingToMetadataEndpointIsRefused() {
        String host = freshName();
        hostileResolverReturning("169.254.169.254");

        // The host allowlist accepts it: the operator allowlisted the NAME, which is all the
        // allowlist can ever check. This is precisely why the allowlist alone was never sufficient.
        var policy = new OutboundHttpPolicy(Set.of(host), Duration.ofSeconds(5));
        policy.requireAllowed(URI.create("https://" + host + "/latest/meta-data/"));

        UnknownHostException refused = assertThrows(UnknownHostException.class,
                () -> InetAddress.getAllByName(host));
        assertTrue(refused.getMessage().contains("reserved address space"), refused.getMessage());
    }

    @Test
    @DisplayName("the refusal stops the socket, not merely the lookup helper")
    void noSocketCanBeOpenedToARebindingName() {
        String host = freshName();
        hostileResolverReturning("127.0.0.1");
        assertThrows(UnknownHostException.class, () -> {
            try (Socket ignored = new Socket(host, 80)) {
                // unreachable
            }
        });
    }

    @Test
    @DisplayName("IPv6 rebinding to loopback and to unique-local space is refused")
    void ipv6RebindingIsRefused() {
        hostileResolverReturning("::1");
        assertThrows(UnknownHostException.class, () -> InetAddress.getAllByName(freshName()));

        hostileResolverReturning("fd00::1");
        assertThrows(UnknownHostException.class, () -> InetAddress.getAllByName(freshName()));
    }

    @Test
    @DisplayName("an IPv4-mapped IPv6 answer cannot launder a link-local address")
    void ipv4MappedRebindingIsRefused() {
        hostileResolverReturning("::ffff:169.254.169.254");
        assertThrows(UnknownHostException.class, () -> InetAddress.getAllByName(freshName()));
    }

    @Test
    @DisplayName("RFC 1918 rebinding into each private range is refused")
    void privateRangeRebindingIsRefused() {
        for (String address : List.of("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.1")) {
            hostileResolverReturning(address);
            assertThrows(UnknownHostException.class, () -> InetAddress.getAllByName(freshName()),
                    "expected refusal for " + address);
        }
    }

    @Test
    @DisplayName("a dual-stack answer keeps its public address and drops the reserved one")
    void reservedAddressesAreDroppedIndividually() throws Exception {
        String host = freshName();
        EgressAddressGuard.replaceUpstream((name, policy) -> List.of(
                InetAddress.getByName("169.254.169.254"),
                InetAddress.getByName("93.184.216.34")));

        InetAddress[] resolved = InetAddress.getAllByName(host);

        assertEquals(1, resolved.length, "the reserved address must not survive resolution");
        assertEquals("93.184.216.34", resolved[0].getHostAddress());
    }

    @Test
    @DisplayName("a public answer is untouched, so the filter is not simply denying everything")
    void publicAddressesStillResolve() throws Exception {
        String host = freshName();
        hostileResolverReturning("93.184.216.34");
        assertEquals("93.184.216.34", InetAddress.getAllByName(host)[0].getHostAddress());
    }

    @Test
    @DisplayName("an operator exception is name-scoped: it frees the named host and nothing else")
    void operatorExceptionAppliesOnlyToTheNamedHost() throws Exception {
        String exempt = freshName();
        String other = freshName();
        EgressAddressGuard.configure(ReservedNetworkPolicy.fromCommaSeparatedExceptions(
                exempt + ":LOOPBACK"));
        hostileResolverReturning("127.0.0.1");

        assertEquals("127.0.0.1", InetAddress.getAllByName(exempt)[0].getHostAddress());
        assertThrows(UnknownHostException.class, () -> InetAddress.getAllByName(other));
    }

    @Test
    @DisplayName("an exception for one reserved network does not grant another")
    void operatorExceptionIsScopedToItsNetwork() {
        String host = freshName();
        EgressAddressGuard.configure(ReservedNetworkPolicy.fromCommaSeparatedExceptions(host + ":LOOPBACK"));
        hostileResolverReturning("169.254.169.254");
        assertThrows(UnknownHostException.class, () -> InetAddress.getAllByName(host));
    }

    @Test
    @DisplayName("the installed provider is Ravenroot's, so these assertions are about production code")
    void theGuardIsActuallyInstalled() throws IOException {
        String host = freshName();
        hostileResolverReturning("10.1.2.3");
        long before = EgressAddressGuard.refusals();
        assertThrows(UnknownHostException.class, () -> InetAddress.getAllByName(host));
        assertTrue(EgressAddressGuard.refusals() > before,
                "the refusal counter must move, proving the JVM resolver routed through the guard");
    }
}
