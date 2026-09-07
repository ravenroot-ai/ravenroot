package ai.ravenroot.server.assistant;

import ai.ravenroot.core.security.OutboundHttpPolicy;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantConfigurationTest {

    @Test
    void absentOrBlankTimeoutUsesTheDocumentedDefault() {
        assertEquals(Duration.ofSeconds(120), AssistantConfiguration.fromEnvironment(Map.of()).timeout());
        assertEquals(Duration.ofSeconds(120), AssistantConfiguration.fromEnvironment(Map.of(
                AssistantConfiguration.TIMEOUT_VARIABLE, " \t ")).timeout());
    }

    @Test
    void positiveTimeoutAcceptsBoundaryAndExistingWhitespaceNormalization() {
        assertEquals(Duration.ofSeconds(1), AssistantConfiguration.fromEnvironment(Map.of(
                AssistantConfiguration.TIMEOUT_VARIABLE, " 1 ")).timeout());
        assertEquals(Duration.ofSeconds(Long.MAX_VALUE), AssistantConfiguration.fromEnvironment(Map.of(
                AssistantConfiguration.TIMEOUT_VARIABLE, Long.toString(Long.MAX_VALUE))).timeout());
    }

    @Test
    void malformedOrNonpositiveTimeoutIsRejectedWithoutEchoOrCause() {
        for (String invalid : new String[] {"0", "-1", "1.5", "9223372036854775808",
                "secret-timeout-value"}) {
            var failure = assertThrows(IllegalArgumentException.class, () ->
                    AssistantConfiguration.fromEnvironment(Map.of(
                            AssistantConfiguration.TIMEOUT_VARIABLE, invalid)));
            assertEquals(AssistantConfiguration.TIMEOUT_VARIABLE
                    + " must be a positive whole number of seconds", failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test
    void compactConstructorKeepsItsCompatibilityFallbacks() {
        var configuration = new AssistantConfiguration(true, null, null, null, null,
                OutboundHttpPolicy.disabled(), Duration.ZERO, 0, -1);
        var positiveValues = new AssistantConfiguration(true, null, null, null, null,
                OutboundHttpPolicy.disabled(), Duration.ofSeconds(1),
                AssistantConfiguration.DEFAULT_MAX_OUTPUT_TOKENS + 1,
                AssistantConfiguration.DEFAULT_MAX_TOOL_ITERATIONS + 1);

        assertEquals(Duration.ofSeconds(120), configuration.timeout());
        assertEquals(AssistantConfiguration.DEFAULT_MAX_OUTPUT_TOKENS, configuration.maxOutputTokens());
        assertEquals(AssistantConfiguration.DEFAULT_MAX_TOOL_ITERATIONS, configuration.maxToolIterations());
        assertEquals(AssistantCredentialSource.API_KEY, configuration.credentialSource());
        assertEquals(AssistantConfiguration.DEFAULT_MAX_OUTPUT_TOKENS + 1,
                positiveValues.maxOutputTokens(), "direct positive values remain API-compatible");
        assertEquals(AssistantConfiguration.DEFAULT_MAX_TOOL_ITERATIONS + 1,
                positiveValues.maxToolIterations(), "direct positive values remain API-compatible");
    }

    @Test
    void localHttpOptInAcceptsOnlyNormalizedBooleans() {
        assertFalse(AssistantConfiguration.fromEnvironment(Map.of()).allowLocalHttp());
        assertFalse(AssistantConfiguration.fromEnvironment(Map.of(
                AssistantConfiguration.ALLOW_LOCAL_HTTP_VARIABLE, " \t\u2003 ")).allowLocalHttp());
        assertTrue(AssistantConfiguration.fromEnvironment(Map.of(
                AssistantConfiguration.ALLOW_LOCAL_HTTP_VARIABLE, " TrUe ")).allowLocalHttp());
        assertFalse(AssistantConfiguration.fromEnvironment(Map.of(
                AssistantConfiguration.ALLOW_LOCAL_HTTP_VARIABLE, " FALSE ")).allowLocalHttp());

        for (String invalid : new String[] {"1", "yes", "\u00a0", "true-and-secret-marker"}) {
            var failure = assertThrows(IllegalArgumentException.class, () ->
                    AssistantConfiguration.fromEnvironment(Map.of(
                            AssistantConfiguration.ALLOW_LOCAL_HTTP_VARIABLE, invalid)));
            assertEquals(AssistantConfiguration.ALLOW_LOCAL_HTTP_VARIABLE
                    + " must be true or false", failure.getMessage());
            assertNull(failure.getCause());
            assertFalse(failure.getMessage().contains(invalid));
        }
    }

    @Test
    void assistantPortAllowlistKeepsSharedDefaultsAndAcceptsTheFullPortRange() {
        Map<String, String> defaults = new HashMap<>();
        defaults.put(AssistantConfiguration.ALLOWED_HOSTS_VARIABLE, "models.example");
        var defaultPolicy = AssistantConfiguration.fromEnvironment(defaults).egressPolicy();
        defaultPolicy.requireAllowed(URI.create("http://models.example/path"));
        defaultPolicy.requireAllowed(URI.create("https://models.example/path"));

        defaults.put(AssistantConfiguration.ALLOWED_PORTS_VARIABLE, " \t\u2003 ");
        AssistantConfiguration.fromEnvironment(defaults).egressPolicy()
                .requireAllowed(URI.create("https://models.example/path"));

        defaults.put(AssistantConfiguration.ALLOWED_PORTS_VARIABLE, " 1, 65535 ");
        var explicitPolicy = AssistantConfiguration.fromEnvironment(defaults).egressPolicy();
        explicitPolicy.requireAllowed(URI.create("http://models.example:1/path"));
        explicitPolicy.requireAllowed(URI.create("https://models.example:65535/path"));
    }

    @Test
    void invalidAssistantPortsAreRejectedBySettingNameWithoutValueOrCause() {
        for (String invalid : new String[] {",", " , , ", "0", "65536", "not-a-port-canary",
                "2147483648"}) {
            var failure = assertThrows(IllegalArgumentException.class, () ->
                    AssistantConfiguration.fromEnvironment(Map.of(
                            AssistantConfiguration.ALLOWED_PORTS_VARIABLE, invalid)));
            assertEquals(AssistantConfiguration.ALLOWED_PORTS_VARIABLE
                    + " must contain comma-separated ports from 1 to 65535", failure.getMessage());
            assertNull(failure.getCause());
            assertFalse(failure.getMessage().contains("canary"));
        }
    }

    @Test
    void tolerantPublicConfigurationContractsRemainCompatible() {
        var configuration = AssistantConfiguration.fromEnvironment(Map.of(
                AssistantConfiguration.ENABLED_VARIABLE, "operator-specific-enabled-token",
                AssistantConfiguration.PROVIDER_VARIABLE, " Unknown-Provider ",
                AssistantConfiguration.MODEL_VARIABLE, " provider/model:version ",
                AssistantConfiguration.CREDENTIAL_SOURCE_VARIABLE, "unknown-source",
                AssistantConfiguration.API_KEY_VARIABLE, " padded-operator-key "));

        assertTrue(configuration.enabled());
        assertEquals("unknown-provider", configuration.providerId());
        assertEquals("provider/model:version", configuration.model());
        assertEquals(AssistantCredentialSource.API_KEY, configuration.credentialSource());
        assertEquals(AssistantCredential.Scheme.API_KEY, configuration.credential().scheme());
    }
}
