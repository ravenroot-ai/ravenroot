package ai.ravenroot.server.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAuthorityBudgetConfigurationTest {
    @Test
    void shippedDefaultsAreFinitePinnedAndUseDistinctBootEpochs() {
        var first = AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of());

        assertEquals("ravenroot-server", first.runtimeInstanceId());
        assertEquals("builtin-conservative-v1", first.rateCardVersion());
        assertEquals("USD", first.currency());
        assertEquals(10, first.inputTokenRateMicros());
        assertEquals(30, first.outputTokenRateMicros());
        assertTrue(first.rootMaxima().turns() > 0);
        assertTrue(first.authorityScopes().contains("runtime:delegate"));
        assertTrue(first.bootEpoch() >= 0);
    }

    @Test
    void explicitKnownFreeRateAndDisabledDelegationAreRepresentable() {
        var policy = AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_AGENT_INPUT_TOKEN_RATE_MICROS", "0",
                "RAVENROOT_AGENT_OUTPUT_TOKEN_RATE_MICROS", "0",
                "RAVENROOT_AGENT_RATE_CARD_VERSION", "known-free-v1",
                "RAVENROOT_AGENT_AUTHORITY_SCOPES", ""));

        assertEquals(0, policy.inputTokenRateMicros());
        assertEquals(0, policy.outputTokenRateMicros());
        assertEquals("known-free-v1", policy.rateCardVersion());
        assertEquals(java.util.Set.of(), policy.authorityScopes());
    }

    @Test
    void malformedOrUnboundedConfigurationFailsClosed() {
        assertThrows(IllegalArgumentException.class, () ->
                AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(
                        "RAVENROOT_AGENT_MAX_TURNS", "0")));
        assertThrows(IllegalArgumentException.class, () ->
                AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(
                        "RAVENROOT_AGENT_INPUT_TOKEN_RATE_MICROS", "unknown")));
        assertThrows(IllegalArgumentException.class, () ->
                AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(
                        "RAVENROOT_AGENT_COST_CURRENCY", "US")));
    }
}
