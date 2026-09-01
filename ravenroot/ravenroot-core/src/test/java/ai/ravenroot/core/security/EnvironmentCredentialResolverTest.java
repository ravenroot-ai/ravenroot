package ai.ravenroot.core.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direct coverage of the {@code SecretProvider} contract for the environment-backed provider (SEC-06). */
class EnvironmentCredentialResolverTest {

    @Test
    void idIdentifiesTheProvider() {
        assertEquals("environment", new EnvironmentCredentialResolver(Map.of()).id());
    }

    @Test
    void resolvesAReferenceThroughItsOwnEncoding() {
        // The key is computed via environmentVariableName(...) (SEC-26) rather than a hand-spelled
        // literal, so this test does not silently assert the pre-SEC-26 collapsing-normalization
        // scheme it replaced.
        var provider = new EnvironmentCredentialResolver(Map.of(
                EnvironmentCredentialResolver.environmentVariableName("openai-main"), "secret-value"));

        try (var secret = provider.get("openai-main").orElseThrow()) {
            assertEquals("secret-value", new String(secret.copy()));
        }
    }

    @Test
    void aDifferentlyCasedReferenceIsNoLongerTheSameKey() {
        // Hex encoding is case-sensitive per byte, so this is now a negative case, not a
        // "normalizes to the same thing" case produced by the lossy derivation (see
        // EnvironmentCredentialResolverInjectivityTest for the exhaustive property).
        assertNotEquals(EnvironmentCredentialResolver.environmentVariableName("openai-main"),
                EnvironmentCredentialResolver.environmentVariableName("OpenAI-Main"));
    }

    @Test
    void returnsEmptyForAnUnknownReference() {
        assertTrue(new EnvironmentCredentialResolver(Map.of()).get("missing").isEmpty());
    }

    @Test
    void returnsEmptyForABlankOrNullReference() {
        var provider = new EnvironmentCredentialResolver(Map.of());
        assertTrue(provider.get("").isEmpty());
        assertTrue(provider.get("   ").isEmpty());
        assertTrue(provider.get(null).isEmpty());
    }
}
