package ai.ravenroot.adapter.openaicompatible;

import ai.ravenroot.api.ai.ModelRequest;
import ai.ravenroot.api.security.SecretValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-10 as it applies to this adapter: the client cannot be substituted, a redirect is not
 * followed, and a response is bounded while it streams.
 *
 * <p>These three properties are tested behaviorally so the assertions cover the effective client,
 * not merely its construction path.
 */
class OpenAiCompatibleModelProviderEgressTest {

    private static final String KEY_REFERENCE = "operator-key";
    private static final String KEY = "sk-do-not-forward-me";

    @Test
    @DisplayName("no constructor accepts an HttpClient")
    void noConstructorAcceptsAnHttpClient() {
        // The one seam that would let an operator hand this adapter a client built outside
        // EgressHttpClients -- which is to say, a client that may follow redirects and bound nothing.
        // The sibling adapter states the reasoning: this module has no in-tree composition root, so a
        // client parameter would not be a gate, it would be the documented way to opt out of one.
        for (Constructor<?> constructor : OpenAiCompatibleModelProvider.class.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertFalse(HttpClient.class.isAssignableFrom(parameter),
                        constructor + " accepts an HttpClient");
                assertFalse(HttpClient.Builder.class.isAssignableFrom(parameter),
                        constructor + " accepts an HttpClient.Builder");
            }
        }
    }

    @Test
    @DisplayName("a redirect is refused, and the second host receives neither the key nor the prompt")
    void aRedirectIsRefusedAndTheSecondHostReceivesNothing() throws Exception {
        // Five statuses, not one: 307 and 308 preserve the method and body, 301/302/303 historically
        // rewrite POST to GET, and a client that followed any of them would put an operator key and a
        // rendered graph prompt on a host the deployment never named.
        for (int status : new int[] {301, 302, 303, 307, 308}) {
            try (ChatCompletionsDouble second = ChatCompletionsDouble.start();
                 ChatCompletionsDouble first = ChatCompletionsDouble.start()) {
                first.redirectsTo(status, second);

                ModelInvocationException failure = failureOf(provider(first.endpoint()));

                assertEquals(ModelInvocationException.Reason.REDIRECT_REFUSED, failure.reason(),
                        "status " + status);
                assertEquals(status, failure.httpStatus());
                assertEquals(1, first.calls());
                // The observation that matters. Not "the adapter reported an error" -- "the second
                // host was never contacted at all".
                assertEquals(0, second.calls(), "status " + status + " reached the second host");
                assertEquals("", second.observedAuthorization());
                assertEquals("", second.observedBody());
            }
        }
    }

    @Test
    @DisplayName("an oversized response is refused while it streams, not after it is buffered")
    void anOversizedResponseIsRefusedWhileItStreams() throws Exception {
        try (ChatCompletionsDouble endpoint = ChatCompletionsDouble.start()) {
            long offered = 64L * 1024 * 1024;
            long ceiling = OpenAiCompatibleModelProvider.RESPONSE_LIMITS.maxEncodedBytes();
            // No Content-Length, so the declared-length shortcut cannot fire: only the streaming
            // check can catch this one.
            endpoint.respondsWithUndeclaredBody(offered);

            ModelInvocationException failure = failureOf(provider(endpoint.endpoint()));

            // RESPONSE_UNREADABLE, not TRANSPORT_FAILURE: the socket did not fail, so advising a
            // retry would be false and would reproduce the same response.
            assertEquals(ModelInvocationException.Reason.RESPONSE_UNREADABLE, failure.reason());
            assertEquals(BoundedBodyHandlersName(), failure.transportType());
            // The difference between "refused a large response" and "stopped reading at the budget":
            // with the ceiling removed the double writes all 64 MiB and the adapter holds them.
            //
            // The bound is deliberately loose, and the looseness is measured rather than guessed.
            // bytesServed counts what the SERVER wrote, which includes everything already sitting in
            // the kernel send buffer and the client receive buffer when the subscription is
            // cancelled -- bytes the adapter never allocates. On this machine that overshoot was
            // ~1.5 MiB past the 8 MiB ceiling; on a host with larger socket buffers it would be
            // more. Pinning it tightly would make this test fail for a reason that has nothing to do
            // with the control, so the assertion states the property the control actually has: the
            // response stopped early. With the ceiling removed this number is exactly `offered`.
            assertTrue(endpoint.bytesServed() < offered / 2,
                    "the response was not cut short: served " + endpoint.bytesServed()
                            + " of " + offered + " offered, against a ceiling of " + ceiling);
        }
    }

    private static String BoundedBodyHandlersName() {
        return "ai.ravenroot.core.security.egress.BoundedBodyHandlers$ResponseTooLargeException";
    }

    private static OpenAiCompatibleModelProvider provider(String endpoint) {
        return new OpenAiCompatibleModelProvider("ollama-local",
                reference -> KEY_REFERENCE.equals(reference)
                        ? Optional.of(new SecretValue(KEY.toCharArray()))
                        : Optional.empty(),
                endpoint, "qwen3", CredentialRequirement.REQUIRED, Duration.ofSeconds(30));
    }

    private static ModelInvocationException failureOf(OpenAiCompatibleModelProvider provider) {
        ModelRequest request = new ModelRequest(UUID.randomUUID(), "node-1",
                "a rendered prompt that must not travel", "payload", "", KEY_REFERENCE, Map.of());
        ExecutionException raised = assertThrows(ExecutionException.class,
                () -> provider.generate(request).toCompletableFuture().get(60, TimeUnit.SECONDS));
        return assertInstanceOf(ModelInvocationException.class, raised.getCause());
    }
}
