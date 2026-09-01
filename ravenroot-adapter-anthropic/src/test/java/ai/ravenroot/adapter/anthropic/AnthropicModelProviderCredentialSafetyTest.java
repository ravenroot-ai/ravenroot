package ai.ravenroot.adapter.anthropic;

import ai.ravenroot.api.ai.ModelRequest;
import ai.ravenroot.api.ai.ModelResponse;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.core.security.egress.EgressHttpClients;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nothing the adapter renders may carry the operator key, or any other text the caller supplied.
 *
 * <h2>The vectors, found before the assertions were written</h2>
 * <p>Four distinct sentinels, because four distinct things can end up in a rendered failure and only
 * one of them is the obvious one:
 * <ol>
 *   <li><b>The resolved operator key.</b> The obvious one.</li>
 *   <li><b>The credential reference.</b> Not obvious, and the sharpest of the four: the editor
 *   renders {@code SECRET_REFERENCE} as a plain {@code <input type="text">} with an advisory
 *   sentence next to it, so the field that is supposed to hold a <em>name</em> for a secret is
 *   exactly the field an author pastes an actual secret into. Any failure that helpfully quoted the
 *   unresolvable reference back would publish it.</li>
 *   <li><b>The node's {@code parameter.*} entries.</b> Not obvious: they reach the adapter as an
 *   untyped {@code Map<String, Object>}, they are never validated against a schema, and they are the
 *   natural place for an author who could not make {@code credentialRef} work to try
 *   {@code parameter.apiKey} instead.</li>
 *   <li><b>The rendered prompt.</b> Carries the traversal payload, substituted into the template by
 *   the node before the adapter sees it — so it is business data of the execution, not just graph
 *   text.</li>
 * </ol>
 *
 * <h2>Where a leak would actually persist — corrected</h2>
 * <p>{@link #theSuccessfulResponseCarriesNothingFromTheRequest} used to be justified by the claim
 * that {@code ModelResponse.metadata} "becomes node attributes, is written into the execution record
 * and persists". <b>That is false, and it was false when it was written.</b>
 * {@code LlmPromptNodeBehaviorFactory} reads {@code payload()}, {@code providerId()} and
 * {@code model()} and nothing else, and {@code ModelResponse.metadata()} has no reader anywhere in
 * the reactor — it is the one surface that is <em>not</em> read.
 *
 * <p>The test is still here and still worth its cost, with the emphasis moved to where it belongs:
 * the three fields that <em>are</em> propagated are the ones on which an echo survives, and metadata
 * is scanned as well because it is part of the SPI's return value and a consumer may appear.
 */
class AnthropicModelProviderCredentialSafetyTest {

    private static final String KEY = "sk-ant-operator-key-SENTINEL-9f2a";
    private static final String REFERENCE = "sk-ant-pasted-into-the-box-SENTINEL-4c71";
    private static final String PARAMETER = "PARAMETER-SENTINEL-3c4d";
    private static final String PROMPT = "summarise this: PROMPT-SENTINEL-1a2b";

    private static final List<String> SENTINELS = List.of(KEY, REFERENCE, PARAMETER, "PROMPT-SENTINEL-1a2b");

    // ---- anti-false-green guards ----------------------------------------------------------------

    @Test
    void theDoubleReceivesTheOperatorKeySoEveryAbsenceAssertionBelowIsAboutSomethingReal() throws Exception {
        try (var anthropic = AnthropicMessagesDouble.start()) {
            provider(anthropic.baseUrl(), resolvesTo(KEY)).generate(request()).toCompletableFuture().join();

            assertEquals(1, anthropic.calls(), "the adapter never called the double at all");
            assertEquals(KEY, anthropic.observedApiKey(),
                    "Sanity check failed: the operator key did not travel through the code under test, so "
                            + "every 'the key does not leak' assertion in this class would pass vacuously.");
            assertTrue(anthropic.observedBody().contains("PROMPT-SENTINEL-1a2b"),
                    "Sanity check failed: the prompt did not reach the wire, so the request-text assertions "
                            + "in this class are about a request that was never built.");
        }
    }

    @Test
    void theErrorBodyTheAdapterDiscardsReallyDoesCarryTheEchoedKey() throws Exception {
        // The second anti-false-green guard, and the one that makes the failure-path tests mean
        // something. "The adapter never reads the error body" is only a safety property if the error
        // body was dangerous. Here the endpoint is called directly, with the adapter out of the way,
        // to show the response really does contain the echoed sentinel.
        //
        // This assertion is about the adapter's public exception. The HTTP implementation is
        // gone; the danger is not, it simply sits one layer lower now — in bytes on the socket
        // rather than in an exception message — which is why this guard was re-derived rather than
        // deleted with the dependency.
        try (var anthropic = AnthropicMessagesDouble.start()) {
            anthropic.responds(400, AnthropicMessagesDouble.error("invalid_request_error",
                    "rejected credential " + KEY));

            HttpResponse<String> direct = EgressHttpClients.create().send(
                    HttpRequest.newBuilder(URI.create(anthropic.baseUrl() + AnthropicModelProvider.MESSAGES_PATH))
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(400, direct.statusCode());
            assertTrue(direct.body().contains(KEY),
                    "the endpoint no longer echoes the key, so the adapter's refusal to read an error "
                            + "body is no longer demonstrably load-bearing — re-derive this test's premise "
                            + "before trusting the leak assertions that depend on it.");
        }
    }

    // ---- failure paths -------------------------------------------------------------------------

    @Test
    void aRejectedRequestLeaksNothingEvenWhenTheRemoteEchoesTheKeyBack() throws Exception {
        // Worst realistic case: the error body quotes back both the key and the reference.
        try (var anthropic = AnthropicMessagesDouble.start()) {
            anthropic.responds(400, AnthropicMessagesDouble.error("invalid_request_error",
                    "rejected credential " + KEY + " for reference " + REFERENCE));

            var failure = failureFrom(anthropic.baseUrl(), resolvesTo(KEY));

            assertEquals(ModelInvocationException.Reason.REQUEST_REJECTED, failure.reason());
            assertEquals(400, failure.httpStatus());
        }
    }

    @Test
    void anUnauthorizedCallLeaksNothing() throws Exception {
        try (var anthropic = AnthropicMessagesDouble.start()) {
            anthropic.responds(401, AnthropicMessagesDouble.error("authentication_error",
                    "invalid x-api-key: " + KEY));

            var failure = failureFrom(anthropic.baseUrl(), resolvesTo(KEY));

            assertEquals(ModelInvocationException.Reason.NOT_AUTHORIZED, failure.reason());
            assertEquals(401, failure.httpStatus());
        }
    }

    @Test
    void aRateLimitedCallLeaksNothing() throws Exception {
        try (var anthropic = AnthropicMessagesDouble.start()) {
            anthropic.responds(429, AnthropicMessagesDouble.error("rate_limit_error", KEY));

            var failure = failureFrom(anthropic.baseUrl(), resolvesTo(KEY));

            assertEquals(ModelInvocationException.Reason.RATE_LIMITED, failure.reason());
            assertEquals(429, failure.httpStatus());
        }
    }

    @Test
    void aProviderSideFailureLeaksNothing() throws Exception {
        try (var anthropic = AnthropicMessagesDouble.start()) {
            anthropic.responds(503, AnthropicMessagesDouble.error("api_error", KEY));

            var failure = failureFrom(anthropic.baseUrl(), resolvesTo(KEY));

            assertEquals(ModelInvocationException.Reason.PROVIDER_UNAVAILABLE, failure.reason());
            assertEquals(503, failure.httpStatus());
        }
    }

    @Test
    void aMalformedSuccessBodyIsUnreadableRatherThanAnEmptyAnswer() throws Exception {
        try (var anthropic = AnthropicMessagesDouble.start()) {
            anthropic.responds(200, "{\"content\": [ this is not json " + KEY);

            var failure = failureFrom(anthropic.baseUrl(), resolvesTo(KEY));

            assertEquals(ModelInvocationException.Reason.RESPONSE_UNREADABLE, failure.reason());
        }
    }

    @Test
    void aTransportFailureLeaksNothing() throws Exception {
        // Start and immediately stop, so the port is closed and the connection is refused. The key
        // has already been resolved and applied to the request by the time this happens.
        String deadUrl;
        try (var anthropic = AnthropicMessagesDouble.start()) {
            deadUrl = anthropic.baseUrl();
        }

        var failure = failureFrom(deadUrl, resolvesTo(KEY));

        assertEquals(ModelInvocationException.Reason.TRANSPORT_FAILURE, failure.reason());
        assertEquals(0, failure.httpStatus());
        assertFalse(failure.transportType().isEmpty(), "the discarded transport type should still be named");
    }

    @Test
    void anUnresolvableReferenceNeverNamesTheReferenceItself() {
        // The field the editor invites a paste into. If the failure quoted it, an author who typed a
        // real key here would have published it by mistyping the reference name.
        var failure = failureFrom("http://127.0.0.1:1", reference -> Optional.empty());

        assertEquals(ModelInvocationException.Reason.CREDENTIAL_UNRESOLVED, failure.reason());
    }

    @Test
    void aBlankResolvedKeyIsRefusedByTheAdapterBecauseTheTransportDoesNotRefuseIt() {
        // An environment variable that exists and is empty resolves to a blank SecretValue. The
        // unreachable base URL is what makes the point: without the adapter's own guard the reason
        // here is TRANSPORT_FAILURE, and an operator with an empty credential variable is told to go
        // and look at the network.
        assertEquals(ModelInvocationException.Reason.CREDENTIAL_UNUSABLE,
                failureFrom("http://127.0.0.1:1", resolvesTo("")).reason());
        assertEquals(ModelInvocationException.Reason.CREDENTIAL_UNUSABLE,
                failureFrom("http://127.0.0.1:1", resolvesTo("   \t ")).reason());
    }

    @Test
    void theRequestBuilderAcceptsABlankApiKeyHeader() {
        // The premise the guard above rests on, measured rather than assumed — and pinned, which the
        // equivalent premise about the vendor SDK never was. An empty field value is legal HTTP, so
        // the JDK builds the request and the blank key goes out. If a future JDK started refusing it,
        // this test goes red and the guard above becomes redundant rather than silently doubled.
        assertDoesNotThrow(() -> HttpRequest.newBuilder(URI.create("http://127.0.0.1:1/v1/messages"))
                .header("x-api-key", "")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build());
    }

    @Test
    void aCredentialCarryingAControlCharacterIsRefusedBeforeItReachesASocket() {
        // A newline pasted along with a key is the realistic case, and the JDK refuses it — with an
        // IllegalArgumentException whose message quotes the value it rejected, which is why the
        // adapter maps it rather than letting it escape.
        var failure = failureFrom("http://127.0.0.1:1", resolvesTo("key-with-a\nnewline-" + KEY));

        assertEquals(ModelInvocationException.Reason.CREDENTIAL_UNUSABLE, failure.reason());
    }

    @Test
    void anAbsentReferenceIsItsOwnReasonAndLeaksNothing() {
        var provider = provider("http://127.0.0.1:1", resolvesTo(KEY));
        var blank = new ModelRequest(UUID.randomUUID(), "n1", PROMPT, "payload", "claude-opus-5", "",
                Map.of("apiKey", PARAMETER));

        var thrown = assertThrows(CompletionException.class,
                () -> provider.generate(blank).toCompletableFuture().join());
        var failure = assertInstanceOf(ModelInvocationException.class, thrown.getCause());

        assertEquals(ModelInvocationException.Reason.CREDENTIAL_REFERENCE_ABSENT, failure.reason());
        assertNothingLeaks(thrown, failure);
    }

    // ---- the success path, where a leak would persist -------------------------------------------

    @Test
    void theSuccessfulResponseCarriesNothingFromTheRequest() throws Exception {
        try (var anthropic = AnthropicMessagesDouble.start()) {
            ModelResponse response = provider(anthropic.baseUrl(), resolvesTo(KEY))
                    .generate(request()).toCompletableFuture().join();

            for (String sentinel : SENTINELS) {
                // payload, providerId and model are the three the llm-prompt node actually reads and
                // writes into node attributes, so these three are where an echo would survive.
                assertFalse(String.valueOf(response.payload()).contains(sentinel),
                        "the completion payload carries " + sentinel);
                assertFalse(response.providerId().contains(sentinel), "providerId carries " + sentinel);
                assertFalse(response.model().contains(sentinel), "model carries " + sentinel);
                // metadata has no reader today. Scanned anyway: it is part of the SPI's return value.
                response.metadata().forEach((name, value) -> {
                    assertFalse(name.contains(sentinel), "a metadata KEY carries " + sentinel);
                    assertFalse(String.valueOf(value).contains(sentinel),
                            "metadata entry '" + name + "' carries " + sentinel);
                });
            }
        }
    }

    // ---- the guarantees that are structural, asserted as structure ------------------------------

    @Test
    void theKeyHolderRendersRedactedAndExposesNoAccessorForItsMaterial() {
        try (SecretValue secret = new SecretValue(KEY.toCharArray());
             AnthropicOperatorKey key = AnthropicOperatorKey.from(secret)) {

            assertEquals(AnthropicOperatorKey.REDACTED, key.toString());
            assertFalse(("" + key).contains(KEY), "string concatenation of the holder rendered the key");
            assertFalse(String.valueOf(key).contains(KEY), "String.valueOf of the holder rendered the key");

            assertTrue(Modifier.isFinal(AnthropicOperatorKey.class.getModifiers()),
                    "a subclass could restore a leaking toString");
            for (Method method : AnthropicOperatorKey.class.getDeclaredMethods()) {
                if (method.isSynthetic()) continue;
                Class<?> returns = method.getReturnType();
                boolean rendersMaterial = returns == String.class || returns == char[].class || returns == byte[].class;
                assertFalse(rendersMaterial && !method.getName().equals("toString"),
                        "method " + method.getName() + " returns " + returns.getSimpleName()
                                + ", which is a way for the key to leave the holder as a value");
            }
        }
    }

    @Test
    void theFailureTypeAcceptsNoFreeTextOnAnyCreationPath() {
        // A String or CharSequence parameter would create a free-text path into the rendered
        // message. Reflection keeps that path absent on every constructor and factory.
        for (Constructor<?> constructor : ModelInvocationException.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()),
                    "every constructor must be private so the factories are the only creation path");
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertFalse(Throwable.class.isAssignableFrom(parameter),
                        "a Throwable parameter would let a foreign exception's message and cause chain in");
                assertFalse(CharSequence.class.isAssignableFrom(parameter),
                        "a CharSequence parameter is a channel for caller text and vendor text alike");
            }
        }
        for (Method method : ModelInvocationException.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) continue;
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(Throwable.class.isAssignableFrom(parameter),
                        "static factory " + method.getName() + " accepts a Throwable");
                assertFalse(CharSequence.class.isAssignableFrom(parameter),
                        "static factory " + method.getName() + " accepts free text");
            }
        }
        assertTrue(Modifier.isFinal(ModelInvocationException.class.getModifiers()),
                "a subclass could widen any of the above");
    }

    @Test
    void theFailureHasNoCauseAndCannotBeGivenASuppressedException() {
        var failure = failureFrom("http://127.0.0.1:1", reference -> Optional.empty());

        assertNull(failure.getCause(), "the cause chain must be empty by construction, not by convention");

        failure.addSuppressed(new IllegalStateException("leaked " + KEY));
        assertEquals(0, failure.getSuppressed().length,
                "suppression is disabled, so addSuppressed must be a no-op rather than a second rendering surface");
    }

    // ---- helpers --------------------------------------------------------------------------------

    private ModelInvocationException failureFrom(String baseUrl, CredentialResolver resolver) {
        var thrown = assertThrows(CompletionException.class,
                () -> provider(baseUrl, resolver).generate(request()).toCompletableFuture().join());
        var failure = assertInstanceOf(ModelInvocationException.class, thrown.getCause(),
                "every failure of this adapter must be a ModelInvocationException");
        // The wrapper is scanned too: CompletionException's message is the cause's toString().
        assertNothingLeaks(thrown, failure);
        return failure;
    }

    private static AnthropicModelProvider provider(String baseUrl, CredentialResolver resolver) {
        return new AnthropicModelProvider("anthropic", resolver, baseUrl, Duration.ofSeconds(10));
    }

    private static CredentialResolver resolvesTo(String secret) {
        // A fresh SecretValue per call: the provider closes the one it is given.
        return reference -> Optional.of(new SecretValue(secret.toCharArray()));
    }

    private static ModelRequest request() {
        return new ModelRequest(UUID.randomUUID(), "llm-1", PROMPT, "the traversal payload",
                "claude-opus-5", REFERENCE, Map.of("apiKey", PARAMETER, "maxTokens", "256"));
    }

    /**
     * Scans everything the caller can render: the message, {@code toString()}, every parameter the
     * failure exposes, the whole cause chain, suppressed exceptions, and the printed stack trace —
     * which is itself a rendering surface, and the one the mail adapter's equivalent does not read.
     */
    private static void assertNothingLeaks(Throwable thrown, ModelInvocationException failure) {
        for (String sentinel : SENTINELS) {
            assertNoSentinel(thrown, sentinel, Collections.newSetFromMap(new IdentityHashMap<>()));
            assertFalse(failure.reason().name().contains(sentinel), "Reason.name() carries " + sentinel);
            assertFalse(failure.reason().sentence().contains(sentinel), "Reason.sentence() carries " + sentinel);
            assertFalse(String.valueOf(failure.httpStatus()).contains(sentinel), "httpStatus() carries " + sentinel);
            assertFalse(failure.transportType().contains(sentinel), "transportType() carries " + sentinel);
        }
    }

    private static void assertNoSentinel(Throwable throwable, String sentinel, Set<Throwable> visited) {
        if (throwable == null || !visited.add(throwable)) return;
        assertFalse(String.valueOf(throwable.getMessage()).contains(sentinel),
                throwable.getClass().getName() + ".getMessage() carries " + sentinel);
        assertFalse(throwable.toString().contains(sentinel),
                throwable.getClass().getName() + ".toString() carries " + sentinel);
        assertFalse(printed(throwable).contains(sentinel),
                throwable.getClass().getName() + "'s printed stack trace carries " + sentinel);
        assertNoSentinel(throwable.getCause(), sentinel, visited);
        for (Throwable suppressed : throwable.getSuppressed()) {
            assertNoSentinel(suppressed, sentinel, visited);
        }
    }

    private static String printed(Throwable throwable) {
        var buffer = new StringWriter();
        try (var writer = new PrintWriter(buffer)) {
            throwable.printStackTrace(writer);
        }
        return buffer.toString();
    }
}
