package ai.ravenroot.extensions.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The process-startup contract for every operator-controlled AI limit. */
class AgentOperationalConfigurationTest {

    @Test
    @DisplayName("missing and blank settings preserve the compatibility defaults")
    void missingAndBlankSettingsUseDefaults() {
        AgentOperationalConfiguration defaults = AgentOperationalConfiguration.defaults();
        assertEquals(defaults, AgentOperationalConfiguration.fromEnvironment(Map.of()));

        Map<String, String> blank = new HashMap<>();
        for (String name : variableNames()) blank.put(name, "  ");
        assertEquals(defaults, AgentOperationalConfiguration.fromEnvironment(blank));
        assertEquals(8, defaults.defaultMaxTurns());
        assertEquals(8, defaults.maxMcpServers());
    }

    @Test
    @DisplayName("valid values override every default as one immutable startup snapshot")
    void validValuesOverrideEveryDefault() {
        Map<String, String> configured = new HashMap<>();
        for (String name : variableNames()) configured.put(name, "4096");

        AgentOperationalConfiguration policy =
                AgentOperationalConfiguration.fromEnvironment(configured);

        for (var component : AgentOperationalConfiguration.class.getRecordComponents()) {
            try {
                assertEquals(4096, component.getAccessor().invoke(policy), component.getName());
            } catch (ReflectiveOperationException impossible) {
                throw new AssertionError(impossible);
            }
        }
    }

    @Test
    @DisplayName("invalid values fail at startup and name the setting an operator must fix")
    void invalidValuesAreActionable() {
        for (String raw : new String[] {"0", "-1", "1.5", "unlimited", "2147483648"}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> AgentOperationalConfiguration.fromEnvironment(
                            Map.of("RAVENROOT_AI_MAX_TURNS", raw)), raw);
            assertTrue(failure.getMessage().contains("RAVENROOT_AI_MAX_TURNS"), raw);
        }

        IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class,
                () -> AgentOperationalConfiguration.fromEnvironment(Map.of(
                        "RAVENROOT_AI_MAX_LLM_RESPONSE_BYTES", "67108865")));
        assertTrue(unsupported.getMessage().contains("RAVENROOT_AI_MAX_LLM_RESPONSE_BYTES"));
    }

    @Test
    @DisplayName("dependent values fail together instead of being silently clamped")
    void crossFieldRelationshipsAreValidated() {
        IllegalArgumentException turns = assertThrows(IllegalArgumentException.class,
                () -> AgentOperationalConfiguration.fromEnvironment(Map.of(
                        "RAVENROOT_AI_DEFAULT_MAX_TURNS", "65")));
        assertTrue(turns.getMessage().contains("RAVENROOT_AI_DEFAULT_MAX_TURNS"));
        assertTrue(turns.getMessage().contains("RAVENROOT_AI_MAX_TURNS"));

        IllegalArgumentException tools = assertThrows(IllegalArgumentException.class,
                () -> AgentOperationalConfiguration.fromEnvironment(Map.of(
                        "RAVENROOT_AI_MAX_MCP_TOOLS_PER_SERVER", "1025")));
        assertTrue(tools.getMessage().contains("RAVENROOT_AI_MAX_MCP_TOOLS_PER_SERVER"));
        assertTrue(tools.getMessage().contains("RAVENROOT_AI_MAX_DISCOVERED_MCP_TOOLS_PER_SERVER"));
    }

    private static String[] variableNames() {
        return new String[] {
                "RAVENROOT_AI_DEFAULT_MAX_TURNS",
                "RAVENROOT_AI_MAX_TURNS",
                "RAVENROOT_AI_MAX_MCP_SERVERS",
                "RAVENROOT_AI_MAX_SKILL_PAYLOAD_BYTES",
                "RAVENROOT_AI_MAX_SKILL_NAME_CHARS",
                "RAVENROOT_AI_MAX_SKILL_DESCRIPTION_CHARS",
                "RAVENROOT_AI_MAX_SKILL_INSTRUCTIONS_CHARS",
                "RAVENROOT_AI_MAX_MCP_TOOLS_PER_SERVER",
                "RAVENROOT_AI_DEFAULT_MAX_DISCOVERED_MCP_TOOLS_PER_SERVER",
                "RAVENROOT_AI_MAX_DISCOVERED_MCP_TOOLS_PER_SERVER",
                "RAVENROOT_AI_MAX_LLM_PROFILE_BYTES",
                "RAVENROOT_AI_MAX_MCP_PROFILE_BYTES",
                "RAVENROOT_AI_DEFAULT_LLM_TIMEOUT_MS",
                "RAVENROOT_AI_MAX_LLM_TIMEOUT_MS",
                "RAVENROOT_AI_DEFAULT_MCP_TIMEOUT_MS",
                "RAVENROOT_AI_MAX_MCP_TIMEOUT_MS",
                "RAVENROOT_AI_DEFAULT_LLM_REQUEST_BYTES",
                "RAVENROOT_AI_MAX_LLM_REQUEST_BYTES",
                "RAVENROOT_AI_DEFAULT_LLM_RESPONSE_BYTES",
                "RAVENROOT_AI_MAX_LLM_RESPONSE_BYTES",
                "RAVENROOT_AI_DEFAULT_MCP_REQUEST_BYTES",
                "RAVENROOT_AI_MAX_MCP_REQUEST_BYTES",
                "RAVENROOT_AI_DEFAULT_MCP_RESPONSE_BYTES",
                "RAVENROOT_AI_MAX_MCP_RESPONSE_BYTES",
                "RAVENROOT_AI_DEFAULT_LLM_CONCURRENCY",
                "RAVENROOT_AI_MAX_LLM_CONCURRENCY",
                "RAVENROOT_AI_DEFAULT_MCP_CONCURRENCY",
                "RAVENROOT_AI_MAX_MCP_CONCURRENCY",
                "RAVENROOT_AI_MAX_SYSTEM_PREAMBLE_CHARS"
        };
    }
}
