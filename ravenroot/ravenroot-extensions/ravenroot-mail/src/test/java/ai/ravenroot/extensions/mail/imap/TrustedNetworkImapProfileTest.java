package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TrustedNetworkImapProfileTest {
    @Test void publicConstructorKeepsPlaintextFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> profile("PLAIN", null));
        assertDoesNotThrow(() -> profile("IMAPS", null));
        assertDoesNotThrow(() -> profile("STARTTLS", null));
    }

    @Test void exactAdministratorProofCannotMoveAcrossProfileHostOrPort() {
        ReservedNetworkPolicy policy = policy("tenant/reader", "127.0.0.1", 143, true);
        var proof = policy.authorizePlaintext("imap", "tenant/reader", "127.0.0.1", 143);

        assertDoesNotThrow(() -> profile("PLAIN", proof));
        assertThrows(IllegalArgumentException.class, () -> new ImapProfile(
                "tenant", "other", "127.0.0.1", 143, "PLAIN", "reader", "credential",
                Set.of("INBOX"), 1_000, 1_000, 1, 10, 128, proof));
        assertThrows(IllegalArgumentException.class, () -> new ImapProfile(
                "tenant", "reader", "127.0.0.2", 143, "PLAIN", "reader", "credential",
                Set.of("INBOX"), 1_000, 1_000, 1, 10, 128, proof));
        assertThrows(IllegalArgumentException.class, () -> new ImapProfile(
                "tenant", "reader", "127.0.0.1", 1143, "PLAIN", "reader", "credential",
                Set.of("INBOX"), 1_000, 1_000, 1, 10, 128, proof));
    }

    @Test void environmentResolverRequiresSeparateAdmissionAndPlaintextAuthority() {
        String key = EnvironmentImapProfileResolver.environmentVariableName("tenant", "reader");
        String value = "127.0.0.1;143;PLAIN;reader;credential;INBOX;1000;1000;1;10;128";

        assertTrue(new EnvironmentImapProfileResolver(Map.of(
                key, value,
                ReservedNetworkPolicy.EXCEPTIONS_ENVIRONMENT_VARIABLE, "127.0.0.1:LOOPBACK"))
                .resolve("tenant", "reader").isEmpty());

        Map<String, String> authorized = new java.util.LinkedHashMap<>();
        authorized.put(key, value);
        authorized.put(ReservedNetworkPolicy.EXCEPTIONS_ENVIRONMENT_VARIABLE, "127.0.0.1:LOOPBACK");
        authorized.put("RAVENROOT_EGRESS_TRUSTED_NETWORK_POLICY",
                encodedPolicy("tenant/reader", "127.0.0.1", 143, true));
        ImapProfile resolved = new EnvironmentImapProfileResolver(authorized)
                .resolve("tenant", "reader").orElseThrow();
        assertEquals("PLAIN", resolved.securityMode());
        assertNotNull(resolved.plaintextAuthorization());

        authorized.put("RAVENROOT_EGRESS_TRUSTED_NETWORK_POLICY",
                encodedPolicy("tenant/other", "127.0.0.1", 143, true));
        assertTrue(new EnvironmentImapProfileResolver(authorized)
                .resolve("tenant", "reader").isEmpty());
    }

    static ReservedNetworkPolicy policy(String profile, String host, int port, boolean plaintext) {
        return ReservedNetworkPolicy.fromEnvironment(Map.of(
                ReservedNetworkPolicy.EXCEPTIONS_ENVIRONMENT_VARIABLE, host + ":LOOPBACK",
                "RAVENROOT_EGRESS_TRUSTED_NETWORK_POLICY",
                encodedPolicy(profile, host, port, plaintext)));
    }

    private static String encodedPolicy(String profile, String host, int port, boolean plaintext) {
        String json = """
                {"version":1,"rules":[{"name":"imap-test","protocols":["imap"],"ports":[%d],
                "hosts":["%s"],"addresses":["127.0.0.0/8","::1/128"],"profiles":["%s"],"allowPlaintext":%s}]}
                """.formatted(port, host, profile, plaintext).replace("\n", "");
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static ImapProfile profile(String mode,
                                       ReservedNetworkPolicy.PlaintextAuthorization proof) {
        return new ImapProfile("tenant", "reader", "127.0.0.1", 143, mode,
                "reader", "credential", Set.of("INBOX"), 1_000, 1_000, 1, 10, 128, proof);
    }
}
