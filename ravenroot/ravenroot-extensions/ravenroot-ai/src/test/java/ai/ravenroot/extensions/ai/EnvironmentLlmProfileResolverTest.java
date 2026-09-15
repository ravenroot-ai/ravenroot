package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.security.EnvironmentKeyCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentLlmProfileResolverTest {

    private static final String LOCAL = """
            {"endpoint":"http://127.0.0.1:8000/v1/chat/completions","model":"qwen38"}""";

    @Test
    @DisplayName("the variable name is hex(profileName), the one derivation every resolver uses")
    void theVariableNameIsHexOfTheProfileName() {
        assertEquals("RAVENROOT_LLM_PROFILE_" + EnvironmentKeyCodec.hex("local"),
                EnvironmentLlmProfileResolver.environmentVariableName("local"));
    }

    @Test
    @DisplayName("a local llama.cpp profile resolves with its defaults applied")
    void theLocalProfileResolves() {
        var profile = resolve("local", LOCAL).orElseThrow();

        assertEquals("http://127.0.0.1:8000/v1/chat/completions", profile.endpoint().toString());
        assertEquals("qwen38", profile.model());
        assertEquals(Optional.empty(), profile.credentialBinding());
        assertEquals(60_000, profile.timeoutMs());
        assertEquals(AgentOperationalConfiguration.DEFAULT_MAX_LLM_BYTES, profile.maxRequestBytes());
        assertEquals(AgentOperationalConfiguration.DEFAULT_MAX_LLM_BYTES, profile.maxResponseBytes());
        assertEquals(4, profile.maxConcurrency());
    }

    @Test
    @DisplayName("a credential binding is accepted on https and refused on plaintext")
    void aCredentialBindingRequiresAnEncryptedOrigin() {
        String withKey = """
                {"endpoint":"%s","model":"m","credentialBindingId":"llm","credentialReference":"llm-key"}""";

        var secure = resolve("hosted", withKey.formatted("https://api.example.test/v1/chat/completions"));
        assertTrue(secure.isPresent());
        assertEquals("llm", secure.get().credentialBinding().orElseThrow().bindingId());
        assertEquals("llm-key", secure.get().credentialBinding().orElseThrow().reference());

        assertEquals(Optional.empty(),
                resolve("hosted", withKey.formatted("http://api.example.test/v1/chat/completions")));
    }

    @Test
    @DisplayName("an unknown member is a refusal, not a silently narrower profile")
    void anUnknownMemberIsRefused() {
        assertEquals(Optional.empty(), resolve("local", """
                {"endpoint":"http://127.0.0.1:8000/v1/chat/completions","model":"m","stream":true}"""));
    }

    @Test
    @DisplayName("absent, blank, non-canonical Base64 and malformed JSON all read as absent")
    void malformedInputReadsAsAbsent() {
        var resolver = new EnvironmentLlmProfileResolver(Map.of(
                EnvironmentLlmProfileResolver.environmentVariableName("blank"), "",
                EnvironmentLlmProfileResolver.environmentVariableName("noncanonical"),
                AiTestSupport.encodedProfile(LOCAL).replace('=', '.'),
                EnvironmentLlmProfileResolver.environmentVariableName("malformed"),
                AiTestSupport.encodedProfile("{not json"),
                EnvironmentLlmProfileResolver.environmentVariableName("incomplete"),
                AiTestSupport.encodedProfile("{\"model\":\"m\"}")));

        for (String name : new String[] { "absent", "blank", "noncanonical", "malformed", "incomplete" }) {
            assertEquals(Optional.empty(), resolver.resolve(name), name);
        }
    }

    @Test
    @DisplayName("a profile name outside the ASCII mask never reaches a variable lookup")
    void aMaskedProfileNameIsRefused() {
        var resolver = new EnvironmentLlmProfileResolver(Map.of());

        for (String name : new String[] { null, "", "-leading", "with space", "x".repeat(65) }) {
            assertEquals(Optional.empty(), resolver.resolve(name), String.valueOf(name));
        }
    }

    @Test
    @DisplayName("a ceiling above this bundle's hard maximum is refused rather than clamped")
    void anExcessiveCeilingIsRefused() {
        assertEquals(Optional.empty(), resolve("local", """
                {"endpoint":"http://127.0.0.1:8000/v1/chat/completions","model":"m",
                 "maxResponseBytes":1073741824}"""));
    }

    @Test
    @DisplayName("an operator override admits a profile above the compatibility ceiling")
    void anOperatorCanRaiseACeiling() {
        var environment = Map.of(
                EnvironmentLlmProfileResolver.environmentVariableName("local"),
                AiTestSupport.encodedProfile("""
                        {"endpoint":"http://127.0.0.1:8000/v1/chat/completions","model":"m",
                         "maxRequestBytes":16777216,"maxResponseBytes":16777216} """));
        AgentOperationalConfiguration policy = AgentOperationalConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_AI_MAX_LLM_REQUEST_BYTES", "16777216",
                "RAVENROOT_AI_MAX_LLM_RESPONSE_BYTES", "16777216"));

        LlmProfile profile = new EnvironmentLlmProfileResolver(environment, policy)
                .resolve("local").orElseThrow();
        assertEquals(16_777_216, profile.maxRequestBytes());
        assertEquals(16_777_216, profile.maxResponseBytes());
    }

    @Test
    @DisplayName("the configured preamble ceiling replaces the former hidden 2048-character parser cap")
    void aConfiguredLongPreambleIsAccepted() {
        String preamble = "p".repeat(4096);
        var environment = Map.of(
                EnvironmentLlmProfileResolver.environmentVariableName("local"),
                AiTestSupport.encodedProfile("""
                        {"endpoint":"http://127.0.0.1:8000/v1/chat/completions","model":"m",
                         "systemPreamble":"%s"} """.formatted(preamble)));

        LlmProfile profile = new EnvironmentLlmProfileResolver(environment,
                AgentOperationalConfiguration.defaults()).resolve("local").orElseThrow();
        assertEquals(preamble, profile.systemPreamble());
    }

    private static Optional<LlmProfile> resolve(String name, String json) {
        return new EnvironmentLlmProfileResolver(Map.of(
                EnvironmentLlmProfileResolver.environmentVariableName(name),
                AiTestSupport.encodedProfile(json))).resolve(name);
    }
}
