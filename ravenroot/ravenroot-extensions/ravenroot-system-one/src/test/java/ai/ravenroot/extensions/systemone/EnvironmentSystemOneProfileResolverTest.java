package ai.ravenroot.extensions.systemone;

import ai.ravenroot.api.security.EnvironmentKeyCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentSystemOneProfileResolverTest {
    @Test
    void resolvesStrictOperatorCapabilitiesAndRizzoLimit() {
        String profileName = "rizzo-local";
        String json = """
                {"endpoint":"http://127.0.0.1:8017/v1/systemone","model":"rizzo-spark-q8",
                 "maxChoiceOptions":26,"maxScoreLevels":10,"maxQuestions":20,
                 "supportedTypes":["choice","noul","score"]}
                """;
        var resolver = new EnvironmentSystemOneProfileResolver(Map.of(
                EnvironmentSystemOneProfileResolver.environmentVariableName(profileName),
                SystemOneTestSupport.encodedProfile(json)));

        SystemOneProfile profile = resolver.resolve(profileName).orElseThrow();
        assertEquals(26, profile.maxChoiceOptions());
        assertEquals(10, profile.maxScoreLevels());
        assertEquals(SystemOneProfile.PORTABLE_TYPES, profile.supportedTypes());
        assertEquals("RAVENROOT_SYSTEM_ONE_PROFILE_" + EnvironmentKeyCodec.hex(profileName),
                EnvironmentSystemOneProfileResolver.environmentVariableName(profileName));
    }

    @Test
    void unknownFieldsAndHalfCredentialBindingsAreRefused() {
        assertTrue(resolve("{\"endpoint\":\"https://example.test/v1/systemone\",\"model\":\"m\",\"retry\":3}").isEmpty());
        assertTrue(resolve("{\"endpoint\":\"https://example.test/v1/systemone\",\"model\":\"m\",\"credentialBindingId\":\"system-one\"}").isEmpty());
    }

    @Test
    void credentialBindingRequiresTlsOrAnExactTrustedNetworkPlaintextRule() {
        String profileName = "hosted";
        String template = "{\"endpoint\":\"%s\",\"model\":\"m\","
                + "\"credentialBindingId\":\"system-one\",\"credentialReference\":\"api-key\"}";
        assertTrue(resolve(template.formatted("http://127.0.0.1:8080/v1/systemone")).isEmpty());
        assertTrue(resolve(template.formatted("https://system-one.example.test/v1/systemone")).isPresent());

        String json = template.formatted("http://127.0.0.1:8080/v1/systemone");
        String policy = """
                {"version":1,"rules":[{"name":"system-one-mesh","protocols":["http"],"ports":[8080],
                "hosts":["127.0.0.1"],"addresses":["127.0.0.0/8"],
                "profiles":["ai.ravenroot.extensions.systemone"],"allowPlaintext":true}]}
                """.replaceAll("\\s+", "");
        var resolver = new EnvironmentSystemOneProfileResolver(Map.of(
                EnvironmentSystemOneProfileResolver.environmentVariableName(profileName),
                SystemOneTestSupport.encodedProfile(json),
                "RAVENROOT_EGRESS_TRUSTED_NETWORK_POLICY",
                Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8))));
        assertEquals("http://127.0.0.1:8080/v1/systemone",
                resolver.resolve(profileName).orElseThrow().endpoint().toString());
    }

    private static java.util.Optional<SystemOneProfile> resolve(String json) {
        return new EnvironmentSystemOneProfileResolver(Map.of(
                EnvironmentSystemOneProfileResolver.environmentVariableName("local"),
                SystemOneTestSupport.encodedProfile(json))).resolve("local");
    }
}
