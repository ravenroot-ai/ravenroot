package ai.ravenroot.adapter.anthropic;

import ai.ravenroot.api.ai.ModelRequest;
import ai.ravenroot.api.ai.ModelResponse;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.SecretValue;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-10 applied to this adapter, asserted rather than described.
 *
 * <p>Both tests reproduce an unsafe redirect and response-bounding failure mode. They are
 * they are behavioural rather than structural: a test that asserted "the client is built by
 * {@code EgressHttpClients}" would have passed on a client whose redirect policy had been overridden
 * afterwards, and would say nothing at all about how many bytes get buffered.
 */
class AnthropicModelProviderEgressTest {

    private static final String KEY = "sk-ant-operator-key-EGRESS-0001";
    private static final String PROMPT = "summarise this: PROMPT-EGRESS-SENTINEL";

    // ---- a redirect is a second destination the policy never saw --------------------------------

    @Test
    void aRedirectIsNotFollowedAndTheSecondHostReceivesNothing() throws Exception {
        // Two loopback servers, the first answering 307 with a
        // Location pointing at the second. With a client that follows redirects the second host
        // receives the operator key in clear and the rendered graph prompt, and its answer is
        // accepted as the completion.
        try (var secondHop = AnthropicMessagesDouble.start();
             var anthropic = AnthropicMessagesDouble.start()) {
            secondHop.responds(200, AnthropicMessagesDouble.completion("second hop answered"));
            anthropic.redirectsTo(307, secondHop);

            var failure = failureFrom(anthropic.baseUrl());

            assertEquals(ModelInvocationException.Reason.REDIRECT_REFUSED, failure.reason());
            assertEquals(307, failure.httpStatus());
            assertEquals(0, secondHop.calls(), "the second host was reached");
            assertEquals("", secondHop.observedApiKey(), "the operator key reached the second host");
            assertEquals("", secondHop.observedBody(), "the rendered prompt reached the second host");
        }
    }

    @Test
    void everyRedirectStatusIsRefusedAndNoneIsMistakenForACompletion() throws Exception {
        // 301, 302, 303, 307 and 308 all carry a Location. None of them is a 4xx or a 5xx, so a
        // status ladder that only branches on those two ranges lets a redirect fall through into the
        // success path, where it becomes an unreadable response rather than a named refusal.
        for (int status : new int[]{301, 302, 303, 307, 308}) {
            try (var secondHop = AnthropicMessagesDouble.start();
                 var anthropic = AnthropicMessagesDouble.start()) {
                anthropic.redirectsTo(status, secondHop);

                var failure = failureFrom(anthropic.baseUrl());

                assertEquals(ModelInvocationException.Reason.REDIRECT_REFUSED, failure.reason(),
                        "status " + status + " was not reported as a refused redirect");
                assertEquals(status, failure.httpStatus());
                assertEquals(0, secondHop.calls(), "status " + status + " reached the second host");
            }
        }
    }

    // ---- a response is bounded while it streams, not after it is resident -----------------------

    @Test
    void aResponseOverTheBudgetIsRefusedWhileStreamingRatherThanBuffered() throws Exception {
        long budget = AnthropicModelProvider.RESPONSE_LIMITS.maxEncodedBytes();
        long offered = 8 * budget;
        try (var anthropic = AnthropicMessagesDouble.start()) {
            // No Content-Length: the ceiling has to catch this one while reading, because there is
            // no declared length to refuse up front.
            anthropic.respondsWithUndeclaredBody(offered);

            var failure = failureFrom(anthropic.baseUrl());

            assertEquals(ModelInvocationException.Reason.RESPONSE_UNREADABLE, failure.reason());
            assertTrue(failure.transportType().endsWith("ResponseTooLargeException"),
                    "the refusal did not come from the streaming ceiling: " + failure.transportType());
            // The assertion that separates "refused a large response" from "stopped reading at the
            // budget". Without a ceiling the double writes all 64 MiB and the adapter holds them.
            assertTrue(anthropic.bytesServed() < offered / 2,
                    "the double managed to write " + anthropic.bytesServed() + " of " + offered
                            + " bytes, so the response was being buffered rather than bounded");
        }
    }

    @Test
    void aLargeResponseUnderTheBudgetIsStillAccepted() throws Exception {
        // The ceiling is a gate, not a refusal of anything big: a long model answer must still
        // arrive. Without this, "refuse everything over a megabyte" would pass the test above.
        String longAnswer = "a".repeat(1024 * 1024);
        try (var anthropic = AnthropicMessagesDouble.start()) {
            anthropic.responds(200, AnthropicMessagesDouble.completion(longAnswer));

            ModelResponse response = provider(anthropic.baseUrl())
                    .generate(request()).toCompletableFuture().join();

            assertEquals(longAnswer.length(), String.valueOf(response.payload()).length());
        }
    }

    // ---- there is no seam through which a non-SEC-10 client could arrive ------------------------

    @Test
    void noConstructorAcceptsAnHttpClient() {
        // The deliberate divergence from AnthropicAssistantProvider, which takes its client from an
        // in-tree composition root that is reviewed alongside it. This adapter is constructed by an
        // operator, in code this repository never sees, so a client parameter would be the
        // documented way to opt out of SEC-10 rather than a gate on it.
        for (Constructor<?> constructor : AnthropicModelProvider.class.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertFalse(HttpClient.class.isAssignableFrom(parameter),
                        "a constructor accepts an HttpClient, so an operator can supply one that "
                                + "follows redirects, is proxied, or skips TLS validation");
            }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static ModelInvocationException failureFrom(String baseUrl) {
        var thrown = assertThrows(CompletionException.class,
                () -> provider(baseUrl).generate(request()).toCompletableFuture().join());
        return assertInstanceOf(ModelInvocationException.class, thrown.getCause());
    }

    private static AnthropicModelProvider provider(String baseUrl) {
        return new AnthropicModelProvider("anthropic", resolver(), baseUrl, Duration.ofSeconds(30));
    }

    private static CredentialResolver resolver() {
        return reference -> Optional.of(new SecretValue(KEY.toCharArray()));
    }

    private static ModelRequest request() {
        return new ModelRequest(UUID.randomUUID(), "llm-1", PROMPT, "the traversal payload",
                "claude-opus-5", "anthropic-main", Map.of());
    }
}
