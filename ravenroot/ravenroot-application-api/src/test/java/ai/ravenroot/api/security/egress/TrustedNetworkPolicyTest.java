package ai.ravenroot.api.security.egress;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedNetworkPolicyTest {
    @Test
    void exactScopeControlsAdmissionAndPlaintextIndependently() throws Exception {
        TrustedNetworkPolicy policy = policy("""
                {"version":1,"rules":[{"name":"mesh-amqp","protocols":["amqp091"],
                "ports":[5672],"hosts":["rabbitmq.default.svc"],"addresses":["10.96.0.0/12"],
                "profiles":["tenant/primary"],"allowPlaintext":true}]}
                """);

        List<InetAddress> broker = List.of(InetAddress.getByName("10.96.4.20"));
        assertTrue(policy.permitsAllPlaintext(
                "amqp091", "tenant/primary", "RABBITMQ.DEFAULT.SVC", 5672, broker));
        assertFalse(policy.permitsAllPlaintext(
                "amqp091", "tenant/other", "rabbitmq.default.svc", 5672, broker));
        assertTrue(policy.permitsAll("amqp091", "tenant/primary", "rabbitmq.default.svc", 5672,
                List.of(InetAddress.getByName("10.96.4.20"))));
        assertFalse(policy.permitsAll("amqp091", "tenant/primary", "rabbitmq.default.svc", 5672,
                List.of(InetAddress.getByName("192.168.1.20"))));
    }

    @Test
    void ipv6ZoneCaseIsExactWhileAddressAndDelimiterSpellingsNormalize() throws Exception {
        TrustedNetworkPolicy policy = policy("""
                {"version":1,"rules":[{"name":"link-local-http","protocols":["http"],
                "ports":[8080],"hosts":["[FE80::A%25ETH0]"],"addresses":["fe80::/10"],
                "profiles":["pkg"],"allowPlaintext":true}]}
                """);
        List<InetAddress> linkLocal = List.of(InetAddress.getByName("fe80::a"));

        assertTrue(policy.permitsAll("http", "pkg", "fe80::a%ETH0", 8080, linkLocal));
        assertTrue(policy.permitsAllPlaintext(
                "http", "pkg", "[fe80::A%25ETH0]", 8080, linkLocal));
        assertFalse(policy.permitsAll("http", "pkg", "fe80::a%eth0", 8080, linkLocal));
        assertFalse(policy.permitsAllPlaintext(
                "http", "pkg", "[FE80::A%25eth0]", 8080, linkLocal));
        assertFalse(policy.permitsAllPlaintext("websocket", "pkg", "fe80::a%ETH0", 8080, linkLocal));
        assertFalse(policy.permitsAllPlaintext("http", "other", "fe80::a%ETH0", 8080, linkLocal));
        assertFalse(policy.permitsAllPlaintext("http", "pkg", "fe80::a%ETH0", 8081, linkLocal));
        assertFalse(policy.permitsAllPlaintext("http", "pkg", "fe80::a%ETH0", 8080,
                List.of(InetAddress.getByName("fd00::a"))));
    }

    @Test
    void dnsHostCaseRemainsNormalizedWithinTheSameRuleBoundaries() throws Exception {
        TrustedNetworkPolicy policy = policy("""
                {"version":1,"rules":[{"name":"dns-http","protocols":["http"],
                "ports":[8080],"hosts":["Service.Example"],"addresses":["10.0.0.0/8"],
                "profiles":["pkg"],"allowPlaintext":true}]}
                """);
        List<InetAddress> address = List.of(InetAddress.getByName("10.2.3.4"));

        assertTrue(policy.permitsAllPlaintext("http", "pkg", "service.example", 8080, address));
        assertTrue(policy.permitsAllPlaintext("HTTP", "pkg", "SERVICE.EXAMPLE", 8080, address));
        assertFalse(policy.permitsAllPlaintext("http", "pkg", "service.example", 8081, address));
    }

    @Test
    void zoneGrammarDoesNotBroadenDnsOrMalformedIpv6Hosts() {
        for (String host : List.of("service.example%ETH0", "fe80::1%ETH:0", "fe80::1%25")) {
            assertThrows(IllegalArgumentException.class, () -> policy("""
                    {"version":1,"rules":[{"name":"invalid-zone","protocols":["http"],
                    "ports":[8080],"hosts":["%s"],"addresses":["fe80::/10"],
                    "profiles":["pkg"],"allowPlaintext":true}]}
                    """.formatted(host)));
        }
    }

    @Test
    void everyDnsAnswerMustBeAdmittedByOneRule() throws Exception {
        TrustedNetworkPolicy split = policy("""
                {"version":1,"rules":[
                {"name":"part-a","protocols":["http"],"ports":[8080],"hosts":["service.svc"],
                 "addresses":["10.0.0.0/24"],"profiles":["pkg"],"allowPlaintext":true},
                {"name":"part-b","protocols":["http"],"ports":[8080],"hosts":["service.svc"],
                 "addresses":["10.0.1.0/24"],"profiles":["pkg"],"allowPlaintext":true}]}
                """);
        List<InetAddress> answers = List.of(
                InetAddress.getByName("10.0.0.4"), InetAddress.getByName("10.0.1.4"));

        assertFalse(split.permitsAll("http", "pkg", "service.svc", 8080, answers));
        assertFalse(split.permitsAllPlaintext("http", "pkg", "service.svc", 8080, answers));
    }

    @Test
    void plaintextRuleWithCidrDoesNotAdmitPublicAddressOutsideCidr() throws Exception {
        TrustedNetworkPolicy policy = policy("""
                {"version":1,"rules":[{"name":"mesh","protocols":["http"],"ports":[8080],
                "hosts":["service.svc"],"addresses":["10.0.0.0/8"],"profiles":["pkg"],
                "allowPlaintext":true}]}
                """);
        assertFalse(policy.permitsAllPlaintext("http", "pkg", "service.svc", 8080,
                List.of(InetAddress.getByName("93.184.216.34"))));
    }

    @Test
    void contextFreeDnsGuardUsesIntersectionRatherThanUnionAcrossScopedRules() throws Exception {
        String encoded = encode("""
                {"version":1,"rules":[
                {"name":"one","protocols":["http"],"ports":[8080],"hosts":["service.svc"],
                 "addresses":["10.0.0.0/24"],"profiles":["pkg/one"],"allowPlaintext":true},
                {"name":"two","protocols":["kafka"],"ports":[9092],"hosts":["service.svc"],
                 "addresses":["10.0.1.0/24"],"profiles":["pkg/two"],"allowPlaintext":true}]}
                """);
        ReservedNetworkPolicy policy = ReservedNetworkPolicy.fromEnvironment(Map.of(
                TrustedNetworkPolicy.ENVIRONMENT_VARIABLE, encoded));
        assertFalse(policy.permits("service.svc", InetAddress.getByName("10.0.0.4")));
        assertFalse(policy.permits("service.svc", InetAddress.getByName("10.0.1.4")));
    }

    @Test
    void explicitHostRuleCannotAuthorizeAnotherProtocolOrProfileThroughLegacyFallback() {
        ReservedNetworkPolicy policy = ReservedNetworkPolicy.fromEnvironment(Map.of(
                TrustedNetworkPolicy.ENVIRONMENT_VARIABLE, encode("""
                        {"version":1,"rules":[{"name":"http-only","protocols":["http"],
                         "ports":[8080],"hosts":["localhost"],
                         "addresses":["127.0.0.0/8","::1/128"],"profiles":["pkg/one"],
                         "allowPlaintext":true}]}
                        """)));

        assertThrows(SecurityException.class,
                () -> policy.requireAllowedDestination("kafka", "pkg/two", "localhost", 8080));
    }

    @Test
    void cidrOnlyRuleStillRequiresFiniteProtocolAndPort() throws Exception {
        TrustedNetworkPolicy policy = policy("""
                {"version":1,"rules":[{"name":"cluster-http","protocols":["http"],
                "ports":[8080],"hosts":[],"addresses":["10.0.0.0/8"],"profiles":[],
                "allowPlaintext":false}]}
                """);
        assertTrue(policy.permitsAll("http", "anything", "api.svc", 8080,
                List.of(InetAddress.getByName("10.2.3.4"))));
        assertFalse(policy.permitsAll("http", "anything", "api.svc", 8081,
                List.of(InetAddress.getByName("10.2.3.4"))));
        assertFalse(policy.permitsAllPlaintext("http", "anything", "api.svc", 8080,
                List.of(InetAddress.getByName("10.2.3.4"))));
    }

    @Test
    void hostOnlyRuleCannotAdmitReservedAddress() throws Exception {
        TrustedNetworkPolicy policy = policy("""
                {"version":1,"rules":[{"name":"public-proxy","protocols":["http"],
                "ports":[80],"hosts":["proxy.example"],"addresses":[],"profiles":[],
                "allowPlaintext":true}]}
                """);
        assertTrue(policy.permitsAllPlaintext("http", "", "proxy.example", 80,
                List.of(InetAddress.getByName("93.184.216.34"))));
        assertFalse(policy.permitsAll("http", "", "proxy.example", 80,
                List.of(InetAddress.getByName("127.0.0.1"))));
    }

    @Test
    void strictDocumentRejectsMalformedOrBroadConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> TrustedNetworkPolicy.fromBase64("not-base64"));
        assertThrows(IllegalArgumentException.class, () -> policy("""
                {"version":1,"rules":[{"name":"wild","protocols":["http"],"ports":[80],
                "hosts":["*"],"addresses":[],"profiles":[],"allowPlaintext":true}]}
                """));
        assertThrows(IllegalArgumentException.class, () -> policy("""
                {"version":1,"rules":[{"name":"unknown","protocols":["ftp"],"ports":[21],
                "hosts":["ftp.example"],"addresses":[],"profiles":[],"allowPlaintext":true}]}
                """));
        assertThrows(IllegalArgumentException.class, () -> policy("""
                {"version":1,"rules":[{"name":"host-bits","protocols":["http"],"ports":[80],
                "hosts":[],"addresses":["10.0.0.1/8"],"profiles":[],"allowPlaintext":true}]}
                """));
    }

    @Test
    void legacyExceptionRemainsAdmissionOnly() {
        ReservedNetworkPolicy legacy = ReservedNetworkPolicy.fromEnvironment(Map.of(
                ReservedNetworkPolicy.EXCEPTIONS_ENVIRONMENT_VARIABLE, "localhost:LOOPBACK"));
        assertThrows(SecurityException.class,
                () -> legacy.requirePlaintext("http", "profile", "localhost", 80));
    }

    private static TrustedNetworkPolicy policy(String json) {
        return TrustedNetworkPolicy.fromBase64(encode(json));
    }

    private static String encode(String json) {
        String compact = json.replaceAll("\\s+", "");
        return Base64.getEncoder().encodeToString(compact.getBytes(StandardCharsets.UTF_8));
    }
}
