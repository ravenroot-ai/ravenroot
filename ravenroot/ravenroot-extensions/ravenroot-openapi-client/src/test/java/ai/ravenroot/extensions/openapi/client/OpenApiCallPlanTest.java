package ai.ravenroot.extensions.openapi.client;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiCallPlanTest {
    @Test void compilesOnlyAllowlistedOperationsAndBoundedInternalSchemas() {
        var plan = OpenApiCallPlan.compile(OpenApiClientTestSupport.profile(Set.of("getPet", "createPet"), 2));
        assertEquals(Set.of("getPet", "createPet"), plan.operationIds());
        assertEquals("GET", plan.operation("getPet").method());
        assertTrue(plan.operation("getPet").idempotent());
        assertFalse(plan.operation("createPet").idempotent());
        assertTrue(plan.operation("createPet").authenticated());
    }

    @Test void rejectsDigestMismatchExternalRefsServersExtensionsAndReferenceCycles() {
        OpenApiClientProfile valid = OpenApiClientTestSupport.profile(Set.of("getPet"), 2);
        var wrong = new OpenApiClientProfile(valid.name(), valid.origin(), valid.specification(), "0".repeat(64),
                valid.allowedOperations(), valid.fixedHeaders(), valid.allowedInputHeaders(),
                valid.projectedResponseHeaders(), valid.credentialBindingId(), valid.credentialReference(),
                valid.maxRequestBytes(), valid.maxResponseBytes(), valid.timeoutMs(), valid.maxConcurrency());
        assertCode(wrong);
        assertCode(OpenApiClientTestSupport.profile(OpenApiClientTestSupport.SPEC.replace(
                "\"paths\":{", "\"servers\":[{\"url\":\"https://attacker.test\"}],\"paths\":{"), Set.of("getPet")));
        assertCode(OpenApiClientTestSupport.profile(OpenApiClientTestSupport.SPEC.replace(
                "\"info\":{", "\"x-ambient\":true,\"info\":{"), Set.of("getPet")));
        assertCode(OpenApiClientTestSupport.profile(OpenApiClientTestSupport.SPEC.replace(
                "#/components/schemas/Pet", "https://attacker.test/schema.json"), Set.of("getPet")));
        String cyclic = OpenApiClientTestSupport.SPEC.replace(
                "\"Pet\":{\"type\":\"object\"", "\"Pet\":{\"$ref\":\"#/components/schemas/Pet\"},\"Unused\":{\"type\":\"object\"");
        assertCode(OpenApiClientTestSupport.profile(cyclic, Set.of("getPet")));
    }

    @Test void rejectsCookieQueryOAuthAndMultipleCredentialRequirements() {
        String queryKey = OpenApiClientTestSupport.SPEC.replace(
                "{\"type\":\"http\",\"scheme\":\"bearer\"}",
                "{\"type\":\"apiKey\",\"name\":\"token\",\"in\":\"query\"}");
        assertCode(OpenApiClientTestSupport.profile(queryKey, Set.of("createPet")));
        String oauth = OpenApiClientTestSupport.SPEC.replace(
                "{\"type\":\"http\",\"scheme\":\"bearer\"}",
                "{\"type\":\"oauth2\",\"flows\":{}}");
        assertCode(OpenApiClientTestSupport.profile(oauth, Set.of("createPet")));
        String alternatives = OpenApiClientTestSupport.SPEC.replace(
                "\"security\":[{\"bearerAuth\":[]}]", "\"security\":[{\"bearerAuth\":[]},{\"bearerAuth\":[]}]");
        assertCode(OpenApiClientTestSupport.profile(alternatives, Set.of("createPet")));
    }

    @Test void rejectsAllowedOperationsThatNeedIncompatibleManagedHeaderPlacements() {
        String mixed = OpenApiClientTestSupport.SPEC
                .replace("\"operationId\":\"getPet\",",
                        "\"operationId\":\"getPet\",\"security\":[{\"apiKeyAuth\":[]}],")
                .replace("\"securitySchemes\":{\"bearerAuth\":{\"type\":\"http\",\"scheme\":\"bearer\"}}",
                        "\"securitySchemes\":{\"bearerAuth\":{\"type\":\"http\",\"scheme\":\"bearer\"},"
                                + "\"apiKeyAuth\":{\"type\":\"apiKey\",\"name\":\"X-Api-Key\",\"in\":\"header\"}}")
                ;

        assertCode(OpenApiClientTestSupport.profile(mixed, Set.of("getPet", "createPet")));
    }

    @Test void profileRefusesAuthorityAndCredentialWidening() {
        byte[] spec = OpenApiClientTestSupport.SPEC.getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> new OpenApiClientProfile("pets",
                URI.create("http://api.example.test"), spec, OpenApiClientTestSupport.sha(spec), Set.of("getPet"),
                Map.of(), Set.of(), Set.of(), null, null, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new OpenApiClientProfile("pets",
                URI.create("https://user@api.example.test"), spec, OpenApiClientTestSupport.sha(spec), Set.of("getPet"),
                Map.of(), Set.of(), Set.of(), "binding", null, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new OpenApiClientProfile("pets",
                URI.create("https://api.example.test"), spec, OpenApiClientTestSupport.sha(spec), Set.of("getPet"),
                Map.of("authorization", List.of("secret")), Set.of(), Set.of(), null, null, 1, 1, 1, 1));
    }

    private static void assertCode(OpenApiClientProfile profile) {
        OpenApiClientException failure = assertThrows(OpenApiClientException.class, () -> OpenApiCallPlan.compile(profile));
        assertEquals(OpenApiClientException.Code.CONFIGURATION, failure.code());
    }
}
