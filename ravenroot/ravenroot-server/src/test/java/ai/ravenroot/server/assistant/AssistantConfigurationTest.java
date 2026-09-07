package ai.ravenroot.server.assistant;

import ai.ravenroot.core.security.OutboundHttpPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

        assertEquals(Duration.ofSeconds(120), configuration.timeout());
        assertEquals(AssistantConfiguration.DEFAULT_MAX_OUTPUT_TOKENS, configuration.maxOutputTokens());
        assertEquals(AssistantConfiguration.DEFAULT_MAX_TOOL_ITERATIONS, configuration.maxToolIterations());
        assertEquals(AssistantCredentialSource.API_KEY, configuration.credentialSource());
    }
}
