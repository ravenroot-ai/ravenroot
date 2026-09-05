package ai.ravenroot.server;

import ai.ravenroot.api.persistence.HumanTaskPolicy;
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
}
