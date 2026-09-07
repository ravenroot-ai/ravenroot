package ai.ravenroot.server.security;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticationConfigurationTest {
    @Test
    void disabledModeIsExplicitAndRestrictedToLoopback() {
        var configuration = AuthenticationConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_AUTH_MODE", "disabled",
                "RAVENROOT_BIND_ADDRESS", "127.0.0.1"), 8080);
        assertEquals("disabled", configuration.mode());
        assertThrows(IllegalArgumentException.class, () -> AuthenticationConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_AUTH_MODE", "disabled",
                "RAVENROOT_BIND_ADDRESS", "0.0.0.0"), 8080));
    }

    @Test
    void disabledModePermitsOnlyTheExplicitContainerLoopbackProxyContract() {
        var configuration = AuthenticationConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_AUTH_MODE", "disabled",
                "RAVENROOT_BIND_ADDRESS", "0.0.0.0",
                "RAVENROOT_CONTAINER_LOOPBACK_ONLY", "true",
                "RAVENROOT_LOCAL_HOST_BIND_ADDRESS", "127.0.0.1"), 8080, true);
        assertEquals("disabled", configuration.mode());

        assertThrows(IllegalArgumentException.class, () -> AuthenticationConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_AUTH_MODE", "disabled",
                "RAVENROOT_BIND_ADDRESS", "0.0.0.0",
                "RAVENROOT_CONTAINER_LOOPBACK_ONLY", "true",
                "RAVENROOT_LOCAL_HOST_BIND_ADDRESS", "0.0.0.0"), 8080, true));
        assertThrows(IllegalArgumentException.class, () -> AuthenticationConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_AUTH_MODE", "disabled",
                "RAVENROOT_BIND_ADDRESS", "0.0.0.0",
                "RAVENROOT_CONTAINER_LOOPBACK_ONLY", "true",
                "RAVENROOT_LOCAL_HOST_BIND_ADDRESS", "127.0.0.1"), 8080, false));
    }

    /** PLAT-11: zero configuration on the default bind starts disabled on loopback. */
    @Test
    void undeclaredModeOnALoopbackBindDefaultsToDisabled() {
        var configuration = AuthenticationConfiguration.fromEnvironment(Map.of(), 8080);
        assertEquals("disabled", configuration.mode());
        assertTrue(configuration.bindAddress().getAddress().isLoopbackAddress());

        // An explicit loopback bind with no declared mode behaves the same way.
        assertEquals("disabled", AuthenticationConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_BIND_ADDRESS", "127.0.0.1"), 8080).mode());
    }

    /**
     * PLAT-11 security half. This asserts only that startup refuses. It is deliberately
     * message-agnostic so a diagnostic mutation does not obscure whether the protection exists.
     */
    @Test
    void undeclaredModeOnANonLoopbackBindRefusesToStart() {
        assertThrows(IllegalArgumentException.class,
                () -> AuthenticationConfiguration.fromEnvironment(Map.of(
                        "RAVENROOT_BIND_ADDRESS", "0.0.0.0"), 8080));
        assertThrows(IllegalArgumentException.class,
                () -> AuthenticationConfiguration.fromEnvironment(Map.of(
                        "RAVENROOT_BIND_ADDRESS", "192.168.1.10"), 8080));
    }

    /** PLAT-11 diagnostic half: the refusal names the variable the operator must set. */
    @Test
    void undeclaredModeOnANonLoopbackBindNamesTheVariableToSet() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> AuthenticationConfiguration.fromEnvironment(Map.of(
                        "RAVENROOT_BIND_ADDRESS", "0.0.0.0"), 8080));
        assertTrue(error.getMessage().contains("RAVENROOT_AUTH_MODE"),
                "the refusal must name the variable to set, was: " + error.getMessage());
    }

    /**
     * The container exception belongs to an explicitly declared {@code disabled}, and the new
     * default must not reach it. Same environment as the accepted container contract above, minus
     * the declaration: it must still refuse.
     */
    @Test
    void theContainerLoopbackProxyExceptionIsNotAvailableToTheUndeclaredDefault() {
        assertThrows(IllegalArgumentException.class,
                () -> AuthenticationConfiguration.fromEnvironment(Map.of(
                        "RAVENROOT_BIND_ADDRESS", "0.0.0.0",
                        "RAVENROOT_CONTAINER_LOOPBACK_ONLY", "true",
                        "RAVENROOT_LOCAL_HOST_BIND_ADDRESS", "127.0.0.1"), 8080, true));
    }

    /** Blank has the same safe exposure-sensitive meaning as absence. */
    @Test
    void aBlankModeUsesTheExposureSensitiveDefault() {
        assertEquals("disabled", AuthenticationConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_AUTH_MODE", " \t ",
                "RAVENROOT_BIND_ADDRESS", "127.0.0.1"), 8080).mode());
        assertThrows(IllegalArgumentException.class, () -> AuthenticationConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_AUTH_MODE", " ",
                "RAVENROOT_BIND_ADDRESS", "0.0.0.0"), 8080));
    }

    @Test
    void blankOptionalAuthenticationValuesUseTheirDefaults() {
        assertEquals("disabled", AuthenticationConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_BIND_ADDRESS", "\t"), 8080).mode());

        Map<String, String> oidc = oidcEnvironment();
        oidc.put("RAVENROOT_AUTH_PRINCIPAL_TYPE_CLAIM", " ");
        oidc.put("RAVENROOT_AUTH_CLOCK_SKEW_SECONDS", " ");
        oidc.put("RAVENROOT_AUTH_JWKS_CACHE_SECONDS", "\t");
        assertEquals("oidc", AuthenticationConfiguration.fromEnvironment(oidc, 8080).mode());
    }

    @Test
    void authenticationDurationsAcceptTheirExactBoundaries() {
        for (String skew : new String[] {"0", "120"}) {
            Map<String, String> environment = oidcEnvironment();
            environment.put("RAVENROOT_AUTH_CLOCK_SKEW_SECONDS", skew);
            assertEquals("oidc", AuthenticationConfiguration.fromEnvironment(environment, 8080).mode());
        }
        for (String cache : new String[] {"30", "3600"}) {
            Map<String, String> environment = oidcEnvironment();
            environment.put("RAVENROOT_AUTH_JWKS_CACHE_SECONDS", cache);
            assertEquals("oidc", AuthenticationConfiguration.fromEnvironment(environment, 8080).mode());
        }
    }

    @Test
    void malformedAuthenticationValuesFailWithSanitizedSettingNames() {
        var invalidMode = assertThrows(IllegalArgumentException.class,
                () -> AuthenticationConfiguration.fromEnvironment(Map.of(
                        "RAVENROOT_AUTH_MODE", "secret-mode-value"), 8080));
        assertEquals("RAVENROOT_AUTH_MODE must be 'disabled', 'local-token', or 'oidc'",
                invalidMode.getMessage());
        assertTrue(!invalidMode.getMessage().contains("secret-mode-value"));
        assertNull(invalidMode.getCause());

        assertSanitizedNumericFailure("RAVENROOT_AUTH_CLOCK_SKEW_SECONDS", "secret-clock-value");
        assertSanitizedNumericFailure("RAVENROOT_AUTH_CLOCK_SKEW_SECONDS", "-1");
        assertSanitizedNumericFailure("RAVENROOT_AUTH_CLOCK_SKEW_SECONDS", "121");
        assertSanitizedNumericFailure("RAVENROOT_AUTH_JWKS_CACHE_SECONDS", "29");
        assertSanitizedNumericFailure("RAVENROOT_AUTH_JWKS_CACHE_SECONDS", "3601");
        assertSanitizedNumericFailure("RAVENROOT_AUTH_JWKS_CACHE_SECONDS", "9223372036854775808");

        var invalidBind = assertThrows(IllegalArgumentException.class,
                () -> AuthenticationConfiguration.fromEnvironment(Map.of(
                        "RAVENROOT_BIND_ADDRESS", "secret invalid bind"), 8080));
        assertEquals("Invalid RAVENROOT_BIND_ADDRESS", invalidBind.getMessage());
        assertNull(invalidBind.getCause());

        Map<String, String> invalidIssuer = oidcEnvironment();
        invalidIssuer.put("RAVENROOT_AUTH_ISSUER", "https://secret invalid issuer");
        var invalidUri = assertThrows(IllegalArgumentException.class,
                () -> AuthenticationConfiguration.fromEnvironment(invalidIssuer, 8080));
        assertEquals("RAVENROOT_AUTH_ISSUER must be a valid URI", invalidUri.getMessage());
        assertNull(invalidUri.getCause());

        Map<String, String> noncanonicalIssuer = oidcEnvironment();
        noncanonicalIssuer.put("RAVENROOT_AUTH_ISSUER", "https://issuer.example/a/../secret");
        var refusedIssuer = assertThrows(IllegalArgumentException.class,
                () -> AuthenticationConfiguration.fromEnvironment(noncanonicalIssuer, 8080));
        assertEquals("RAVENROOT_AUTH_ISSUER is invalid", refusedIssuer.getMessage());
        assertNull(refusedIssuer.getCause());

        Map<String, String> credentialedJwks = oidcEnvironment();
        credentialedJwks.put("RAVENROOT_AUTH_JWKS_URI", "https://secret@issuer.example/jwks");
        var refusedJwks = assertThrows(IllegalArgumentException.class,
                () -> AuthenticationConfiguration.fromEnvironment(credentialedJwks, 8080));
        assertEquals("RAVENROOT_AUTH_JWKS_URI is invalid", refusedJwks.getMessage());
        assertTrue(!refusedJwks.getMessage().contains("secret"));
        assertNull(refusedJwks.getCause());
    }

    @Test
    void localAuthenticationTokenIsNeverTrimmed() {
        String rawToken = " 012345678901234567890123456789 ";
        var configuration = AuthenticationConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_AUTH_MODE", "local-token",
                "RAVENROOT_AUTH_LOCAL_TOKEN", rawToken), 8080);
        assertEquals("local-token", configuration.mode());
    }

    @Test
    void oidcModeFailsFastWhenTrustConfigurationIsIncomplete() {
        assertThrows(IllegalArgumentException.class,
                () -> AuthenticationConfiguration.fromEnvironment(Map.of("RAVENROOT_AUTH_MODE", "oidc"), 8080));
    }

    @Test
    void localTokenRequiresLoopbackAndHighEntropyCredential() {
        assertThrows(IllegalArgumentException.class, () -> AuthenticationConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_AUTH_MODE", "local-token",
                "RAVENROOT_BIND_ADDRESS", "0.0.0.0",
                "RAVENROOT_AUTH_LOCAL_TOKEN", "01234567890123456789012345678901"), 8080));
        assertThrows(IllegalArgumentException.class, () -> AuthenticationConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_AUTH_MODE", "local-token",
                "RAVENROOT_BIND_ADDRESS", "127.0.0.1",
                "RAVENROOT_AUTH_LOCAL_TOKEN", "short"), 8080));
    }

    private static Map<String, String> oidcEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("RAVENROOT_AUTH_MODE", "oidc");
        environment.put("RAVENROOT_AUTH_ISSUER", "https://issuer.example");
        environment.put("RAVENROOT_AUTH_AUDIENCE", "ravenroot");
        environment.put("RAVENROOT_AUTH_JWKS_URI", "https://issuer.example/jwks");
        return environment;
    }

    private static void assertSanitizedNumericFailure(String name, String value) {
        Map<String, String> environment = oidcEnvironment();
        environment.put(name, value);
        var failure = assertThrows(IllegalArgumentException.class,
                () -> AuthenticationConfiguration.fromEnvironment(environment, 8080));
        assertTrue(failure.getMessage().startsWith(name + " must be an integer between "));
        assertTrue(!failure.getMessage().contains(value));
        assertNull(failure.getCause());
    }
}
