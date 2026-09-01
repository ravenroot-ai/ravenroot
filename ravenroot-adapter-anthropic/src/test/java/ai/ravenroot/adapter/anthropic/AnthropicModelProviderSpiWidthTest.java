package ai.ravenroot.adapter.anthropic;

import ai.ravenroot.api.ai.ModelProvider;
import ai.ravenroot.api.ai.ModelRequest;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.core.ai.ModelProviderRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two SPI widths that had no test, pinned so that widening either becomes a visible choice.
 *
 * <h2>Why this class exists</h2>
 * <p>The adapter contract distinguishes four mismatches
 * between {@code ModelProvider} and the Messages API were fixed by a test. Two were: {@code
 * max_tokens} arriving through the untyped parameter map, and a 200 that is not an answer. Two were
 * not — the absent lifecycle and the per-request credential — and an unpinned claim about a test is
 * worse than no claim, because it retires the reader's attention without retiring the risk.
 *
 * <p>Both are assertions about <em>absence</em>, which is exactly the class of fact a knowledge graph
 * cannot establish and a reader will not re-derive: nothing fails today if a {@code close()} appears
 * on the SPI or a client cache appears in this adapter. These tests are what makes it fail.
 */
class AnthropicModelProviderSpiWidthTest {

    // ---- width 1: the SPI has no lifecycle, so a provider owns nothing it must release ----------

    @Test
    void theSpiDeclaresNoLifecycleMethodOnEitherSide() {
        assertFalse(AutoCloseable.class.isAssignableFrom(ModelProvider.class),
                "ModelProvider extending AutoCloseable would give a provider somewhere to release "
                        + "pooled state — a real improvement, and one that must be an ADR rather "
                        + "than an inherited interface");

        Set<String> lifecycleNames = Set.of("close", "shutdown", "stop", "dispose", "release");
        for (Method method : ModelProvider.class.getDeclaredMethods()) {
            assertFalse(lifecycleNames.contains(method.getName()),
                    "ModelProvider declares " + method.getName() + "(): this adapter's whole "
                            + "no-owned-state design rests on there being no such method");
        }
        assertEquals(Set.of("id", "generate"),
                java.util.Arrays.stream(ModelProvider.class.getDeclaredMethods())
                        .filter(m -> !m.isSynthetic())
                        .map(Method::getName)
                        .collect(java.util.stream.Collectors.toSet()),
                "the ModelProvider surface changed; re-derive what an adapter may now own");

        Set<String> registryRemovals = Set.of("unregister", "remove", "close", "clear", "deregister");
        for (Method method : ModelProviderRegistry.class.getDeclaredMethods()) {
            assertFalse(registryRemovals.contains(method.getName()),
                    "ModelProviderRegistry declares " + method.getName() + "(): a registered "
                            + "provider can now be taken out, which is the missing half of a lifecycle");
        }
    }

    // ---- width 2: the credential is per request, so nothing may be cached per credential --------

    @Test
    void twoDifferentReferencesSendTwoDifferentKeysOnTheSameProviderInstance() throws Exception {
        // The behaviour a client cache would break. It passes today and would keep passing on the
        // first call after a cache were added — which is why the assertion is on the SECOND call's
        // key rather than on the first's.
        try (var anthropic = AnthropicMessagesDouble.start()) {
            var provider = new AnthropicModelProvider("anthropic", keyPerReference(),
                    anthropic.baseUrl(), Duration.ofSeconds(10));

            provider.generate(request("ref-one")).toCompletableFuture().join();
            provider.generate(request("ref-two")).toCompletableFuture().join();

            List<String> keys = anthropic.observedApiKeys();
            assertEquals(2, keys.size(), "the double did not receive both calls");
            assertEquals("key-for-ref-one", keys.get(0));
            assertEquals("key-for-ref-two", keys.get(1),
                    "the second call reused the first call's credential, so something is cached "
                            + "per provider instance that must be per request");
            assertNotEquals(keys.get(0), keys.get(1));
        }
    }

    @Test
    void theAdapterHoldsNoCacheAtAllAndExactlyOneClient() {
        // The structural half. On the Messages API the credential is a request header, so one client
        // serves every reference and there is no reason for a map keyed by anything — least of all
        // by a credential reference, which is graph content and therefore attacker-influenced.
        int clients = 0;
        for (Field field : AnthropicModelProvider.class.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertTrue(Modifier.isFinal(field.getModifiers()),
                    "field " + field.getName() + " is mutable on a type shared by concurrent nodes");
            assertFalse(Map.class.isAssignableFrom(field.getType()),
                    "field " + field.getName() + " is a Map: a cache here is state the SPI gives no "
                            + "way to dispose of, and its key would be graph content");
            assertFalse(java.util.Collection.class.isAssignableFrom(field.getType()),
                    "field " + field.getName() + " is a Collection: same objection as a Map");
            if (HttpClient.class.isAssignableFrom(field.getType())) {
                clients++;
            }
        }
        assertEquals(1, clients,
                "expected exactly one HttpClient held for the provider's lifetime; a second one is "
                        + "a second set of network rules");
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static CredentialResolver keyPerReference() {
        return reference -> Optional.of(new SecretValue(("key-for-" + reference).toCharArray()));
    }

    private static ModelRequest request(String reference) {
        return new ModelRequest(UUID.randomUUID(), "llm-1", "a prompt from the graph", "payload",
                "claude-opus-5", reference, Map.of());
    }
}
