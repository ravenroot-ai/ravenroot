package ai.ravenroot.server.assistant;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production selection and local-HTTP policy for the OpenAI-compatible provider. */
class OpenAiCompatibleAssistantConfigurationTest {

    @Test
    void credentialFreeLocalHttpNeedsTheSwitchAndTheExactHostAndPortAllowlist() {
        Map<String, String> env = localHttp();

        AssistantConfiguration ready = AssistantConfiguration.fromEnvironment(env);
        assertTrue(ready.availability().ready());
        assertEquals(AssistantConfiguration.OPENAI_COMPATIBLE_PROVIDER, ready.availability().provider());

        env.remove(AssistantConfiguration.ALLOW_LOCAL_HTTP_VARIABLE);
        assertEquals(AssistantAvailability.InertReason.INSECURE_REFUSED,
                AssistantConfiguration.fromEnvironment(env).availability().reason());

        env = localHttp();
        env.remove(AssistantConfiguration.ALLOWED_HOSTS_VARIABLE);
        assertEquals(AssistantAvailability.InertReason.HOST_NOT_ALLOWLISTED,
                AssistantConfiguration.fromEnvironment(env).availability().reason());

        env = localHttp();
        env.remove(AssistantConfiguration.ALLOWED_PORTS_VARIABLE);
        assertEquals(AssistantAvailability.InertReason.HOST_NOT_ALLOWLISTED,
                AssistantConfiguration.fromEnvironment(env).availability().reason());
    }

    @Test
    void credentialBearingHttpIsAlwaysRefusedEvenWithTheLocalSwitch() {
        Map<String, String> withKey = localHttp();
        withKey.put(AssistantConfiguration.API_KEY_VARIABLE, "operator-secret");
        assertEquals(AssistantAvailability.InertReason.INSECURE_REFUSED,
                AssistantConfiguration.fromEnvironment(withKey).availability().reason());

        Map<String, String> oauth = localHttp();
        oauth.put(AssistantConfiguration.CREDENTIAL_SOURCE_VARIABLE, "oauth");
        assertEquals(AssistantAvailability.InertReason.INSECURE_REFUSED,
                AssistantConfiguration.fromEnvironment(oauth).availabilityWith(null).reason());
    }

    @Test
    void explicitHttpOptInDoesNotPermitAPlaintextRemoteEndpoint() {
        Map<String, String> env = localHttp();
        env.put(AssistantConfiguration.ENDPOINT_VARIABLE,
                "http://models.example:8081/v1/chat/completions");
        env.put(AssistantConfiguration.ALLOWED_HOSTS_VARIABLE, "models.example");

        assertEquals(AssistantAvailability.InertReason.INSECURE_REFUSED,
                AssistantConfiguration.fromEnvironment(env).availability().reason());
    }

    @Test
    void ipv6LoopbackIsLocalButStillNeedsTheSeparateEgressGrant() {
        Map<String, String> env = localHttp();
        env.put(AssistantConfiguration.ENDPOINT_VARIABLE,
                "http://[::1]:8081/v1/chat/completions");
        env.put(AssistantConfiguration.ALLOWED_HOSTS_VARIABLE, "[::1]");

        assertEquals(AssistantAvailability.InertReason.HOST_NOT_ALLOWLISTED,
                AssistantConfiguration.fromEnvironment(env).availability().reason(),
                "IPv6 loopback must clear the plaintext-local check before SEC-10 applies");
    }

    @Test
    void invalidOrCredentialBearingEndpointUrisFailBeforeAnyClientExists() {
        Map<String, String> malformed = localHttp();
        malformed.put(AssistantConfiguration.ENDPOINT_VARIABLE, "not a URI");
        assertThrows(IllegalArgumentException.class,
                () -> AssistantConfiguration.fromEnvironment(malformed));

        Map<String, String> userInfo = localHttp();
        userInfo.put(AssistantConfiguration.ENDPOINT_VARIABLE,
                "https://secret@models.example/v1/chat/completions");
        IllegalArgumentException credentialInUri = assertThrows(IllegalArgumentException.class,
                () -> AssistantConfiguration.fromEnvironment(userInfo));
        assertTrue(credentialInUri.getMessage().contains(AssistantConfiguration.ENDPOINT_VARIABLE));
        assertTrue(!credentialInUri.getMessage().contains("secret"));
    }

    @Test
    void selectionIsIndependentOfExecutableNodeProfiles() {
        Map<String, String> env = localHttp();
        env.put("RAVENROOT_LLM_PROFILE_6C6F63616C", "not-an-assistant-profile");

        AssistantConfiguration configuration = AssistantConfiguration.fromEnvironment(env);

        assertEquals(AssistantConfiguration.OPENAI_COMPATIBLE_PROVIDER, configuration.providerId());
        assertEquals("local-model", configuration.model());

        AssistantService service = AssistantService.fromEnvironment(env,
                (subject, provider) -> java.util.Set.of());
        assertTrue(service.availability().ready(),
                "the production Assistant registry must wire the provider, not only parse its id");
        assertEquals(AssistantConfiguration.OPENAI_COMPATIBLE_PROVIDER,
                service.availability().provider());
    }

    private static Map<String, String> localHttp() {
        Map<String, String> env = new HashMap<>();
        env.put(AssistantConfiguration.PROVIDER_VARIABLE,
                AssistantConfiguration.OPENAI_COMPATIBLE_PROVIDER);
        env.put(AssistantConfiguration.ENDPOINT_VARIABLE,
                "http://localhost:8081/v1/chat/completions");
        env.put(AssistantConfiguration.MODEL_VARIABLE, "local-model");
        env.put(AssistantConfiguration.ALLOWED_HOSTS_VARIABLE, "localhost");
        env.put(AssistantConfiguration.ALLOWED_PORTS_VARIABLE, "8081");
        env.put(AssistantConfiguration.ALLOW_LOCAL_HTTP_VARIABLE, "true");
        return env;
    }
}
