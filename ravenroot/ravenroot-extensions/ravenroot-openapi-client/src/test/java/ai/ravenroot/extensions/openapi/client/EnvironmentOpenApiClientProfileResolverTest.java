package ai.ravenroot.extensions.openapi.client;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentOpenApiClientProfileResolverTest {
    @Test void resolvesStrictOpaqueProfileAndRefusesNonCanonicalOrUnknownFields() {
        String spec = Base64.getEncoder().encodeToString(OpenApiClientTestSupport.SPEC.getBytes(StandardCharsets.UTF_8));
        String json = """
                {"origin":"https://api.example.test","specBase64":"%s","specSha256":"%s",
                 "operations":["getPet"],"fixedHeaders":{"accept":["application/json"]},
                 "inputHeaders":["x-trace"],"responseHeaders":["content-type"],
                 "credentialBindingId":"bearer","credentialReference":"pets-token",
                 "maxRequestBytes":4096,"maxResponseBytes":8192,"timeoutMs":2000,"maxConcurrency":2}
                """.formatted(spec, OpenApiClientTestSupport.sha(OpenApiClientTestSupport.SPEC.getBytes(StandardCharsets.UTF_8)));
        String value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String key = EnvironmentOpenApiClientProfileResolver.environmentVariableName("pets");
        var profile = new EnvironmentOpenApiClientProfileResolver(Map.of(key, value)).resolve("pets").orElseThrow();
        assertEquals("https://api.example.test", profile.origin().toString());
        assertEquals(java.util.Set.of("getPet"), profile.allowedOperations());
        assertArrayEquals(OpenApiClientTestSupport.SPEC.getBytes(StandardCharsets.UTF_8), profile.specification());

        assertTrue(new EnvironmentOpenApiClientProfileResolver(Map.of(key, value.substring(0, value.length() - 1)))
                .resolve("pets").isEmpty());
        String unknown = Base64.getEncoder().encodeToString(json.replace("{", "{\"url\":\"https://attacker.test\",")
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(new EnvironmentOpenApiClientProfileResolver(Map.of(key, unknown)).resolve("pets").isEmpty());
        assertTrue(new EnvironmentOpenApiClientProfileResolver(Map.of(key, value)).resolve("../pets").isEmpty());
    }
}
