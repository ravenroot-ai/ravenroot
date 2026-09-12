package ai.ravenroot.server;

import ai.ravenroot.api.persistence.HumanTaskPolicy;
import ai.ravenroot.core.runtime.GraphExecutionLimits;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HumanTaskConfigurationTest {
    @Test
    void absentAndUnicodeBlankValuesUseDefaults() {
        assertEquals(HumanTaskPolicy.DEFAULTS, HumanTaskConfiguration.fromEnvironment(Map.of()));
        assertEquals(HumanTaskPolicy.DEFAULTS, HumanTaskConfiguration.fromSources(
                Map.of("ravenroot.human-task.max-page-size", "\u00a0\u2003"),
                Map.of("RAVENROOT_HUMAN_TASK_WRITE_ATTEMPTS", "\u2003")));
    }

    @Test
    void nonBlankPropertyPrecedesEnvironment() {
        var policy = HumanTaskConfiguration.fromSources(
                Map.of("ravenroot.human-task.max-page-size", "250"),
                Map.of("RAVENROOT_HUMAN_TASK_MAX_PAGE_SIZE", "200"));
        assertEquals(250, policy.inboxMaxPageSize());
    }

    @Test
    void blankPropertyDefersToEnvironment() {
        var policy = HumanTaskConfiguration.fromSources(
                Map.of("ravenroot.human-task.max-page-size", "\u00a0"),
                Map.of("RAVENROOT_HUMAN_TASK_MAX_PAGE_SIZE", "200"));
        assertEquals(200, policy.inboxMaxPageSize());
    }

    @Test
    void formerOperationalMaximaAreDefaultsThatOperatorsCanRaise() {
        var policy = HumanTaskConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_HUMAN_TASK_MAX_RESPONSE_BYTES", "600000",
                "RAVENROOT_HUMAN_TASK_MAX_DECISION_BODY_BYTES", "700000",
                "RAVENROOT_HUMAN_TASK_MAX_EXPIRY_SECONDS", "5184000",
                "RAVENROOT_HUMAN_TASK_MAX_ESCALATION_SECONDS", "5183999",
                "RAVENROOT_HUMAN_TASK_MAX_PAGE_SIZE", "250"));
        assertEquals(600_000, policy.maxResponseBytes());
        assertEquals(700_000, policy.decisionBodyMaxBytes());
        assertEquals(5_184_000, policy.maxExpirySeconds());
        assertEquals(250, policy.inboxMaxPageSize());
    }

    @Test
    void confirmationValuesUseTheSamePropertyEnvironmentAndPolicyAuthority() {
        var policy = HumanTaskConfiguration.fromSources(
                Map.of("ravenroot.human-task.max-confirmation-prompt-bytes", "8192",
                        "ravenroot.human-task.default-attention-page-size", "25"),
                Map.of("RAVENROOT_HUMAN_TASK_MAX_CONFIRMATION_PROMPT_BYTES", "4096",
                        "RAVENROOT_HUMAN_TASK_MAX_ATTENTION_PAGE_SIZE", "80"));
        assertEquals(8_192, policy.confirmation().maxPromptUtf8Bytes());
        assertEquals(25, policy.confirmation().attentionDefaultPageSize());
        assertEquals(80, policy.confirmation().attentionMaxPageSize());
    }

    @Test
    void malformedAndOverflowValuesAreSanitizedAndAttributed() {
        for (String value : new String[]{"secret-text", "999999999999999999999999999"}) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> HumanTaskConfiguration.fromEnvironment(
                            Map.of("RAVENROOT_HUMAN_TASK_MAX_PAGE_SIZE", value)));
            assertFalse(failure.getMessage().contains(value));
            assertFalse(failure.getMessage().contains("NumberFormatException"));
            assertEquals(true, failure.getMessage().contains("ravenroot.human-task.max-page-size"));
        }
    }

    @Test
    void crossFieldFailureNamesBothConfigurationSurfaces() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> HumanTaskConfiguration.fromEnvironment(Map.of(
                        "RAVENROOT_HUMAN_TASK_DEFAULT_PAGE_SIZE", "101",
                        "RAVENROOT_HUMAN_TASK_MAX_PAGE_SIZE", "100")));
        assertEquals(true, failure.getMessage().contains("RAVENROOT_HUMAN_TASK_DEFAULT_PAGE_SIZE"));
        assertFalse(failure.getMessage().contains("101"));
    }

    @Test
    void resourceCeilingsAcceptTheirBoundaryAndRejectTheNextValue() {
        var atBoundary = HumanTaskConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_HUMAN_TASK_MAX_AUTHORIZATION_TOKENS", "256",
                "RAVENROOT_HUMAN_TASK_MAX_RESPONSE_SCHEMA_BYTES", "128",
                "RAVENROOT_HUMAN_TASK_MAX_PAGE_SIZE", "1000",
                "RAVENROOT_HUMAN_TASK_WRITE_ATTEMPTS", "32"));
        assertEquals(256, atBoundary.maxAuthorizationTokens());
        assertEquals(128, atBoundary.maxResponseSchemaUtf8Bytes());
        assertEquals(1_000, atBoundary.inboxMaxPageSize());
        assertEquals(32, atBoundary.writeAttempts());

        for (var entry : Map.of(
                "RAVENROOT_HUMAN_TASK_MAX_AUTHORIZATION_TOKENS", "257",
                "RAVENROOT_HUMAN_TASK_MAX_RESPONSE_SCHEMA_BYTES", "129",
                "RAVENROOT_HUMAN_TASK_MAX_PAGE_SIZE", "1001",
                "RAVENROOT_HUMAN_TASK_WRITE_ATTEMPTS", "33").entrySet()) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> HumanTaskConfiguration.fromEnvironment(Map.of(entry.getKey(), entry.getValue())));
            assertFalse(failure.getMessage().contains(entry.getValue()));
        }
    }

    @Test
    void humanTaskAndGraphPayloadPoliciesMustComposeBeforeServing() {
        HumanTaskConfiguration.requireCompatible(HumanTaskPolicy.DEFAULTS,
                GraphExecutionLimits.DEFAULTS);
        var custom = HumanTaskConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_HUMAN_TASK_MAX_RESPONSE_BYTES", "600000",
                "RAVENROOT_HUMAN_TASK_MAX_DECISION_BODY_BYTES", "700000"));
        var compatible = GraphExecutionLimits.fromEnvironment(Map.of(
                GraphExecutionLimits.MAX_PAYLOAD_BYTES_VARIABLE, "700000",
                GraphExecutionLimits.MAX_CUMULATIVE_PAYLOAD_BYTES_VARIABLE, "800000"));
        HumanTaskConfiguration.requireCompatible(custom, compatible);

        var payloadFailure = assertThrows(IllegalArgumentException.class,
                () -> HumanTaskConfiguration.requireCompatible(custom, GraphExecutionLimits.DEFAULTS));
        assertEquals(true, payloadFailure.getMessage().contains("RAVENROOT_GRAPH_MAX_PAYLOAD_BYTES"));
        var cumulativeFailure = assertThrows(IllegalArgumentException.class,
                () -> HumanTaskConfiguration.requireCompatible(custom,
                        GraphExecutionLimits.fromEnvironment(Map.of(
                                GraphExecutionLimits.MAX_PAYLOAD_BYTES_VARIABLE, "700000",
                                GraphExecutionLimits.MAX_CUMULATIVE_PAYLOAD_BYTES_VARIABLE, "600000"))));
        assertEquals(true, cumulativeFailure.getMessage().contains(
                "RAVENROOT_GRAPH_MAX_CUMULATIVE_PAYLOAD_BYTES"));
    }
}
