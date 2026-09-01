package ai.ravenroot.server.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** A mode that is present but blank stays an explicit declaration, and keeps failing as today. */
    @Test
    void aBlankModeIsStillAnExplicitDeclarationAndStillFails() {
        assertThrows(IllegalArgumentException.class, () -> AuthenticationConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_AUTH_MODE", "",
                "RAVENROOT_BIND_ADDRESS", "127.0.0.1"), 8080));
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
}
