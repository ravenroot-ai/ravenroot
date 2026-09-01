package ai.ravenroot.core.security;

import ai.ravenroot.core.security.egress.EgressAddressGuard;
import ai.ravenroot.core.security.egress.ReservedNetworkPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reach: scheme, host, port and literal address (SEC-10).
 *
 * <p>The port allowlist and the literal-address check did not exist previously. An allowlisted
 * internal host exposed every port on itself, and a literal reserved address was reachable because
 * the JVM resolver never sees a literal.
 */
class OutboundHttpReachTest {

    private final ReservedNetworkPolicy original = EgressAddressGuard.policy();

    @AfterEach
    void restore() {
        EgressAddressGuard.configure(original);
    }

    private static OutboundHttpPolicy allowing(String... hosts) {
        return new OutboundHttpPolicy(Set.of(hosts), Duration.ofSeconds(5));
    }

    // ---- port allowlist -------------------------------------------------

    @Test
    @DisplayName("an allowlisted host does not expose every port on itself")
    void anAllowlistedHostDoesNotExposeEveryPort() {
        OutboundHttpPolicy policy = allowing("internal.example.com");

        assertDoesNotThrow(() -> policy.requireAllowed(URI.create("https://internal.example.com/api")));
        assertDoesNotThrow(() -> policy.requireAllowed(URI.create("http://internal.example.com:80/api")));

        for (int port : List.of(22, 5432, 6379, 8080, 9200, 11211)) {
            SecurityException refused = assertThrows(SecurityException.class,
                    () -> policy.requireAllowed(URI.create("http://internal.example.com:" + port + "/")),
                    "port " + port + " must not be reachable merely because the host is allowlisted");
            assertTrue(refused.getMessage().contains("port"), refused.getMessage());
        }
    }

    @Test
    @DisplayName("the default ports are the two the node actually speaks")
    void defaultPortsAreHttpAndHttps() {
        assertEquals(Set.of(80, 443), allowing("example.com").allowedPorts());
    }

    @Test
    @DisplayName("an implicit port is derived from the scheme, not left unchecked")
    void implicitPortIsDerivedFromScheme() {
        OutboundHttpPolicy policy = OutboundHttpPolicy.fromCommaSeparated("example.com", "443", 0);
        assertDoesNotThrow(() -> policy.requireAllowed(URI.create("https://example.com/")));
        assertThrows(SecurityException.class, () -> policy.requireAllowed(URI.create("http://example.com/")));
    }

    @Test
    @DisplayName("the operator can narrow and widen the port allowlist, and nothing else can")
    void operatorControlsThePortAllowlist() {
        OutboundHttpPolicy policy = OutboundHttpPolicy.fromCommaSeparated("example.com", "8443, 443", 0);
        assertEquals(Set.of(8443, 443), policy.allowedPorts());
        assertDoesNotThrow(() -> policy.requireAllowed(URI.create("https://example.com:8443/")));
        assertThrows(SecurityException.class, () -> policy.requireAllowed(URI.create("https://example.com:8444/")));
    }

    @Test
    @DisplayName("a malformed port allowlist fails loudly instead of silently denying everything")
    void malformedPortAllowlistIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OutboundHttpPolicy.fromCommaSeparated("example.com", "https", 0));
        assertThrows(IllegalArgumentException.class,
                () -> OutboundHttpPolicy.fromCommaSeparated("example.com", "70000", 0));
    }

    // ---- literal addresses ---------------------------------------------

    @Test
    @DisplayName("a literal reserved address is refused even when the operator allowlisted it")
    void literalReservedAddressIsRefused() {
        // The literal is in the host allowlist, so only the address check can refuse it. This is
        // the path the JVM resolver structurally cannot see.
        for (String literal : List.of("169.254.169.254", "127.0.0.1", "10.0.0.1", "192.168.1.1",
                "0.0.0.0", "100.64.0.1")) {
            OutboundHttpPolicy policy = allowing(literal);
            SecurityException refused = assertThrows(SecurityException.class,
                    () -> policy.requireAllowed(URI.create("http://" + literal + "/latest/meta-data/")),
                    literal + " must be refused as a reserved address");
            assertTrue(refused.getMessage().contains("reserved address"), refused.getMessage());
        }
    }

    @Test
    @DisplayName("the bare decimal form of a loopback literal is refused too")
    void decimalLiteralIsRefused() {
        // 2130706433 == 127.0.0.1. The JDK parses this as a literal without any DNS lookup, so it
        // would otherwise slip past both the resolver and a naive dotted-quad check.
        OutboundHttpPolicy policy = allowing("2130706433");
        assertThrows(SecurityException.class,
                () -> policy.requireAllowed(URI.create("http://2130706433/")));
    }

    @Test
    @DisplayName("IPv6 literals are refused, brackets and mapped form included")
    void ipv6LiteralsAreRefused() {
        assertThrows(SecurityException.class,
                () -> allowing("[::1]").requireAllowed(URI.create("http://[::1]/")));
        assertThrows(SecurityException.class,
                () -> allowing("[fd00::1]").requireAllowed(URI.create("http://[fd00::1]/")));
        assertThrows(SecurityException.class,
                () -> allowing("[::ffff:169.254.169.254]")
                        .requireAllowed(URI.create("http://[::ffff:169.254.169.254]/")));
    }

    @Test
    @DisplayName("a public literal is still reachable, so the check is not denying all literals")
    void publicLiteralIsAllowed() {
        assertDoesNotThrow(() -> allowing("93.184.216.34")
                .requireAllowed(URI.create("http://93.184.216.34/")));
    }

    @Test
    @DisplayName("an operator exception naming the literal permits it, and nothing else does")
    void operatorExceptionCoversALiteral() {
        EgressAddressGuard.configure(ReservedNetworkPolicy.fromCommaSeparatedExceptions("127.0.0.1:LOOPBACK"));
        assertDoesNotThrow(() -> allowing("127.0.0.1").requireAllowed(URI.create("http://127.0.0.1/")));
        assertThrows(SecurityException.class,
                () -> allowing("10.0.0.1").requireAllowed(URI.create("http://10.0.0.1/")));
    }

    // ---- controls that already existed, pinned so they cannot regress ----

    @Test
    @DisplayName("deny-by-default: an unconfigured policy reaches nothing")
    void unconfiguredPolicyReachesNothing() {
        assertThrows(SecurityException.class,
                () -> OutboundHttpPolicy.disabled().requireAllowed(URI.create("https://example.com/")));
        assertThrows(SecurityException.class,
                () -> OutboundHttpPolicy.fromCommaSeparatedHosts(null)
                        .requireAllowed(URI.create("https://example.com/")));
    }

    @Test
    @DisplayName("non-HTTP schemes and credentials in the URL are refused")
    void schemeAndCredentialRulesHold() {
        assertThrows(SecurityException.class,
                () -> allowing("example.com").requireAllowed(URI.create("file://example.com/etc/passwd")));
        assertThrows(SecurityException.class,
                () -> allowing("example.com").requireAllowed(URI.create("gopher://example.com/")));
        assertThrows(SecurityException.class,
                () -> allowing("example.com").requireAllowed(URI.create("https://user:pw@example.com/")));
    }
}
