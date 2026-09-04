package ai.ravenroot.api.security.egress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservedNetworkPolicyTest {
    private final ReservedNetworkPolicy denied = ReservedNetworkPolicy.denyAllReserved();

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.1", "2130706433", "169.254.169.254",
            "10.0.0.1", "::1", "[::1]", "::ffff:127.0.0.1", "fe80::1%eth0",
            "[fe80::1%25eth0]", "4294967296", "127..1"})
    void reservedAndMalformedNumericLiteralsFailClosedWithoutDns(String literal) {
        assertFalse(denied.permitsLiteral(literal));
        SecurityException refusal = assertThrows(SecurityException.class,
                () -> denied.requireAllowedLiteral(literal));
        assertFalse(refusal.getMessage().contains(literal));
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946", "broker.example"})
    void publicLiteralsAndHostnamesPassTheLiteralBoundary(String host) {
        assertTrue(denied.permitsLiteral(host));
        assertDoesNotThrow(() -> denied.requireAllowedLiteral(host));
    }

    @Test
    void bracketedIpv6AndZoneExceptionsAreUnambiguousAndEnvironmentUsesSameParser() {
        String exceptions = "[::1]:LOOPBACK,[fe80::1%eth0]:LINK_LOCAL";
        ReservedNetworkPolicy direct = ReservedNetworkPolicy.fromCommaSeparatedExceptions(exceptions);
        ReservedNetworkPolicy environment = ReservedNetworkPolicy.fromEnvironment(
                Map.of(ReservedNetworkPolicy.EXCEPTIONS_ENVIRONMENT_VARIABLE, exceptions));

        for (ReservedNetworkPolicy policy : java.util.List.of(direct, environment)) {
            assertTrue(policy.permitsLiteral("[::1]"));
            assertTrue(policy.permitsLiteral("[fe80::1%25eth0]"));
            assertFalse(policy.permitsLiteral("[fe80::1%25ETH0]"));
            assertFalse(policy.permitsLiteral("[fe80::1%25other]"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ReservedNetworkPolicy.fromCommaSeparatedExceptions("::1:LOOPBACK"));
    }

    @Test
    void literalExceptionsDoNotAuthorizeAliasesOrOtherAddressFamilies() {
        ReservedNetworkPolicy dotted = ReservedNetworkPolicy.fromCommaSeparatedExceptions(
                "127.0.0.1:LOOPBACK");
        assertTrue(dotted.permitsLiteral("127.0.0.1"));
        assertFalse(dotted.permitsLiteral("127.1"));
        assertFalse(dotted.permitsLiteral("2130706433"));
        assertFalse(dotted.permitsLiteral("::ffff:127.0.0.1"));
        assertFalse(dotted.permitsLiteral("::127.0.0.1"));
        assertFalse(dotted.permitsLiteral("127.000.0.1"));
        ReservedNetworkPolicy mapped = ReservedNetworkPolicy.fromCommaSeparatedExceptions(
                "[::ffff:127.0.0.1]:LOOPBACK");
        assertTrue(mapped.permitsLiteral("::ffff:127.0.0.1"));
        assertFalse(mapped.permitsLiteral("::127.0.0.1"));
        assertFalse(mapped.permitsLiteral("127.0.0.1"));
        assertThrows(IllegalArgumentException.class,
                () -> ReservedNetworkPolicy.fromCommaSeparatedExceptions("127.000.0.1:LOOPBACK"));
    }
}
