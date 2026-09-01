package ai.ravenroot.extensions.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentStorageProfileResolverTest {
    private static final String JSON = """
            {"origin":"https://s3.example.test","region":"eu-west-1","bucket":"bucket-a",
             "keyPrefix":"tenant-data","addressingStyle":"path","signingBindingId":"assets-s3",
             "operations":["get","put"],"contentTypes":["text/plain"],"allowIfMatch":true,
             "allowIfNoneMatch":true,"maxObjectBytes":1024,"timeoutMs":2000,"maxConcurrency":2,
             "maxRequestsPerSecond":10}
            """;

    @Test void resolvesOnlyCanonicalBoundedStrictProfiles() {
        String key = EnvironmentStorageProfileResolver.environmentVariableName("assets");
        String encoded = Base64.getEncoder().encodeToString(JSON.getBytes(StandardCharsets.UTF_8));
        StorageProfile profile = new EnvironmentStorageProfileResolver(Map.of(key, encoded)).resolve("assets").orElseThrow();
        assertEquals("assets-s3", profile.signingBindingId());
        assertEquals(java.util.Set.of(StorageProfile.Operation.GET, StorageProfile.Operation.PUT), profile.allowedOperations());
        assertTrue(new EnvironmentStorageProfileResolver(Map.of(key, encoded.substring(0, encoded.length() - 1)))
                .resolve("assets").isEmpty());
        assertTrue(new EnvironmentStorageProfileResolver(Map.of()).resolve("assets").isEmpty());
    }

    @Test void unknownFieldsAndPlainJsonFailClosed() {
        String key = EnvironmentStorageProfileResolver.environmentVariableName("assets");
        String unknown = JSON.replace("\"maxRequestsPerSecond\":10", "\"maxRequestsPerSecond\":10,\"url\":\"https://evil\"");
        assertTrue(new EnvironmentStorageProfileResolver(Map.of(key,
                Base64.getEncoder().encodeToString(unknown.getBytes(StandardCharsets.UTF_8)))).resolve("assets").isEmpty());
        assertTrue(new EnvironmentStorageProfileResolver(Map.of(key, JSON)).resolve("assets").isEmpty());
    }
}
