package ai.ravenroot.core.security;

import ai.ravenroot.api.security.SecretProvider;
import ai.ravenroot.api.security.SecretValue;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProviderCredentialResolver} is pure delegation: the SPI split (SEC-06) puts every actual
 * decision — caching, revocation, which backend — behind {@link SecretProvider}, so this seam must do
 * nothing else.
 */
class ProviderCredentialResolverTest {

    @Test
    void delegatesToTheUnderlyingProvider() {
        var resolver = new ProviderCredentialResolver(new StubProvider());

        try (var secret = resolver.resolve("known").orElseThrow()) {
            assertEquals("value", new String(secret.copy()));
        }
        assertTrue(resolver.resolve("unknown").isEmpty());
    }

    @Test
    void rejectsANullProvider() {
        assertThrows(NullPointerException.class, () -> new ProviderCredentialResolver(null));
    }

    private static final class StubProvider implements SecretProvider {
        @Override
        public String id() {
            return "test";
        }

        @Override
        public Optional<SecretValue> get(String reference) {
            return "known".equals(reference) ? Optional.of(new SecretValue("value".toCharArray()))
                    : Optional.empty();
        }
    }
}
