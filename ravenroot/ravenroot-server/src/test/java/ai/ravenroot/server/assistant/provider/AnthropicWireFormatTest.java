package ai.ravenroot.server.assistant.provider;

import ai.ravenroot.server.assistant.AssistantCredential;
import ai.ravenroot.server.assistant.AssistantOutcome;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Anthropic Messages API translation, including the three wire behaviours that are silent
 * when they are wrong.
 */
class AnthropicWireFormatTest {

    private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
    private static final String CREDENTIAL = "sk-ant-test-CANARY";

    /**
     * <b>A refusal is a successful 200, and must not become an answer or a transport error.</b>
     *
     * <p>Also asserts that a refusal whose {@code stop_details} is absent is still classified as a
     * refusal: branching on {@code stop_details} rather than {@code stop_reason} is the mistake this
     * asserts against, and it is a mistake that produces "provider unavailable" for a request the
     * provider answered perfectly well.</p>
     *
     * <p><b>Mutation proof.</b> Move the {@code stop_reason} branch in
     * {@code AnthropicAssistantProvider#readTurn} below the {@code content} read, or branch on
     * {@code stop_details}, and the second assertion reds.</p>
     */
    @Test
    void aRefusalIsATwoHundredAndIsClassifiedFromStopReasonAlone() throws Exception {
        var provider = provider(client -> { });
        var withDetails = provider.readTurn(utf8("""
                {"model":"claude-opus-5","stop_reason":"refusal",
                 "stop_details":{"type":"refusal","category":"cyber"},"content":[]}"""));
        assertInstanceOf(AssistantProvider.Turn.Refused.class, withDetails);

        var withoutDetails = provider.readTurn(utf8("""
                {"model":"claude-opus-5","stop_reason":"refusal","content":[]}"""));
        assertInstanceOf(AssistantProvider.Turn.Refused.class, withoutDetails,
                "a refusal with no stop_details is still a refusal");
    }

    /**
     * <b>A 2xx this build cannot read never becomes an empty assistant turn.</b>
     *
     * <p>Three shapes, all of which a naive reader turns into {@code ""}: unparseable bytes, a 200
     * with an empty content array, and a 200 whose only text block is blank.</p>
     *
     * <p><b>Mutation proof.</b> Replace the blank-text throw in {@code readTurn} with
     * {@code return new Turn.Answer(text, model, truncated)} and the last two assertions red.</p>
     */
    @Test
    void anUnreadableSuccessIsANamedFailureNotAnEmptyAnswer() {
        var provider = provider(client -> { });
        for (String body : List.of("not json at all",
                "{\"model\":\"claude-opus-5\",\"stop_reason\":\"end_turn\",\"content\":[]}",
                "{\"model\":\"claude-opus-5\",\"stop_reason\":\"end_turn\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"   \"}]}")) {
            var failure = assertThrows(AssistantProviderException.class,
                    () -> provider.readTurn(utf8(body)),
                    () -> "this body must be a named failure, not an empty answer: " + body);
            assertEquals(AssistantOutcome.Reason.PROVIDER_UNREADABLE, failure.reason());
        }
    }

    /** A turn stopped at the ceiling keeps its text and is flagged, rather than being discarded. */
    @Test
    void truncationIsCarriedOnTheAnswerRatherThanFailingTheTurn() throws Exception {
        var turn = provider(client -> { }).readTurn(utf8("""
                {"model":"claude-opus-5","stop_reason":"max_tokens",
                 "content":[{"type":"text","text":"half an answer"}]}"""));
        var answer = assertInstanceOf(AssistantProvider.Turn.Answer.class, turn);
        assertEquals("half an answer", answer.text());
        assertTrue(answer.truncated());
    }

    /** Tool calls come back as calls, with the assistant turn preserved for the echo. */
    @Test
    void toolUseBlocksBecomeToolCallsCarryingTheirOpaqueArguments() throws Exception {
        var turn = provider(client -> { }).readTurn(utf8("""
                {"model":"claude-opus-5","stop_reason":"tool_use","content":[
                  {"type":"text","text":"let me look"},
                  {"type":"tool_use","id":"toolu_1","name":"ravenroot_runtime","input":{"a":1}}]}"""));
        var calls = assertInstanceOf(AssistantProvider.Turn.ToolCalls.class, turn);
        assertEquals(1, calls.calls().size());
        assertEquals("ravenroot_runtime", calls.calls().get(0).name());
        assertTrue(calls.calls().get(0).inputJson().contains("\"a\""));
        assertEquals(2, calls.assistantContent().size(),
                "the assistant turn must be preserved for the echo, text block included");
    }

    /**
     * <b>The request carries the credential and the version header, and no sampling parameter.</b>
     *
     * <p>{@code temperature}, {@code top_p} and {@code top_k} are rejected with a 400 by the current
     * models, so sending one turns every request into a failure even though the parameter looks harmless.</p>
     *
     * <p><b>Mutation proof.</b> Add {@code ,"temperature":0} to {@code writeRequest} and the sampling
     * assertion reds; drop the {@code anthropic-version} header and the header assertion reds.</p>
     */
    @Test
    void theRequestCarriesCredentialAndVersionAndNoSamplingParameters() throws Exception {
        var captured = new java.util.concurrent.atomic.AtomicReference<HttpRequest>();
        var provider = provider(captured::set);
        provider.complete(new AssistantProvider.Request("claude-opus-5", "instructions",
                List.of(AssistantProvider.Message.author("hello")), List.of(), 4096));

        HttpRequest request = captured.get();
        assertEquals(ENDPOINT, request.uri());
        assertEquals(Optional.of(CREDENTIAL), request.headers().firstValue("x-api-key"),
                "the credential travels in the header, and only there");
        assertEquals(Optional.of(AnthropicAssistantProvider.API_VERSION),
                request.headers().firstValue("anthropic-version"));

        String body = provider.writeRequest(new AssistantProvider.Request("claude-opus-5", "instructions",
                List.of(AssistantProvider.Message.author("hello")), List.of(), 4096));
        for (String rejected : List.of("temperature", "top_p", "top_k", "fallbacks")) {
            assertFalse(body.contains(rejected),
                    () -> "'" + rejected + "' must not be sent: the current models reject the sampling "
                            + "parameters with a 400, and server-side fallbacks would silently bill a "
                            + "different model to the author's subscription");
        }
        assertFalse(body.contains(CREDENTIAL), "the credential belongs in the header, never the body");
    }

    /** A rejected credential is terminal, and named as a rejection rather than an outage. */
    @Test
    void aRejectedCredentialIsTerminalAndNamedAsARejection() {
        var provider = provider(request -> { }, 401, "{}");
        var failure = assertThrows(AssistantProviderException.class,
                () -> provider.complete(new AssistantProvider.Request("claude-opus-5", null,
                        List.of(AssistantProvider.Message.author("hi")), List.of(), 100)));
        assertEquals(AssistantOutcome.Reason.PROVIDER_REJECTED, failure.reason());
    }

    /**
     * <b>An unchecked exception from the injected client never escapes {@code complete}.</b>
     *
     * <p>Without an outer runtime catch, {@code complete}'s catches on the send call cover only
     * {@code InterruptedException}, {@code SecurityException} and {@code IOException} -- checked
     * types, plus the one unchecked type the {@code IllegalArgumentException} catch on the request
     * build handles. A bare {@code RuntimeException} from the injected {@link HttpClient} -- a client
     * bug, a stub, a transport this adapter has not met yet -- walked straight past every one of them
     * and out of this adapter entirely: not an answer, not a named failure, the third outcome
     * {@code AssistantOutcome} exists to make impossible.</p>
     *
     * <p><b>Mutation proof.</b> Remove the outer {@code try / catch (RuntimeException)} that now wraps
     * {@code complete}'s whole body and this test reds: {@code assertThrows(AssistantProviderException
     * .class, ...)} fails because the exception that actually escapes is the injected
     * {@code IllegalStateException} itself, not an {@code AssistantProviderException} at all -- an
     * unambiguous red naming the missing guard. Asserting the reason alone is enough to distinguish
     * this from every other failure in this suite: {@code ADAPTER_DEFECT} has exactly one production
     * call site, this catch, so no other code path can produce this reason for a different cause the
     * way two {@code PROVIDER_UNREADABLE} paths could (see the read-budget test below).</p>
     */
    @Test
    void anUncheckedExceptionFromTheInjectedClientIsNamedAsAnAdapterDefect() {
        var provider = providerWithClient(new ThrowingHttpClient());

        var failure = assertThrows(AssistantProviderException.class,
                () -> provider.complete(REQUEST),
                "an unchecked exception from the client must become a named failure, not escape "
                        + "complete()");

        assertEquals(AssistantOutcome.Reason.ADAPTER_DEFECT, failure.reason());
        assertFalse(failure.getMessage().contains("boom-injected-by-the-test"),
                () -> "the panel-facing message must be authored and sanitized, never the raw "
                        + "exception text: " + failure.getMessage());
        assertEquals("boom-injected-by-the-test", failure.getCause().getMessage(),
                "the original exception must still be retained as the cause, for the server log only");
    }

    /**
     * <b>A response larger than the read budget is refused <em>as it is read</em>, and is a named
     * failure rather than an {@link Error}.</b>
     *
     * <p>The adapter used to take the body with {@code BodyHandlers.ofByteArray()}, which buffers
     * everything the provider sends before any budget of ours is consulted. A budget read after the
     * allocation cannot refuse anything, and the {@link OutOfMemoryError} a large enough body produced
     * is an {@link Error} — so it walked straight through {@code readTurn}'s
     * {@code catch (RuntimeException)} and left the adapter as an unnamed failure. That is the exact
     * outcome {@code AssistantOutcome} exists to make impossible.</p>
     *
     * <p><b>Why the body is a stream that never ends.</b> A fixed oversized array would prove only
     * that a large body is rejected, which the old code also did once it had survived allocating it.
     * An endless stream cannot be buffered at all, so the test passes only if something stops reading
     * — and the byte count asserted below is the direct evidence that it stopped at the budget rather
     * than at the end of the body. The test would not terminate against a {@code readAllBytes()}.</p>
     *
     * <p><b>The positive control is the second half.</b> A refusal assertion driven through a
     * substitute client is worth nothing unless the same harness can also produce a success: without
     * it, a double that fails to deliver any body at all would satisfy the first half and the test
     * would be asserting its own breakage. The small body below travels the identical path — same
     * client, same handler, same bounded read — and must come back as words.</p>
     *
     * <p><b>Mutation proof.</b> Change {@code readNBytes(budget + 1)} to {@code readNBytes(budget)} in
     * {@code readBounded} and the message assertion reds: the body is then exactly at budget, the
     * over-budget branch never fires, and the refusal arrives from the JSON reader instead — the same
     * reason for a different cause, which is why this asserts the message and not the reason alone.
     * Restore {@code BodyHandlers.ofByteArray()} and this test does not compile.</p>
     */
    @Test
    void aResponseOverTheReadBudgetIsRefusedAsItIsReadAndNamed() throws Exception {
        var endless = new EndlessStream();
        var oversized = provider(request -> { }, 200, () -> endless);

        var failure = assertThrows(AssistantProviderException.class,
                () -> oversized.complete(REQUEST),
                "a response past the read budget must be a named failure, not an Error and not an "
                        + "answer");
        assertEquals(AssistantOutcome.Reason.PROVIDER_UNREADABLE, failure.reason());
        assertTrue(failure.getMessage().contains("response budget"),
                () -> "the refusal must come from the transport bound rather than from the JSON reader "
                        + "further in -- both are PROVIDER_UNREADABLE, so the reason alone cannot tell "
                        + "them apart: " + failure.getMessage());

        long budget = AnthropicAssistantProvider.RESPONSE_LIMITS.maxEncodedBytes();
        assertTrue(endless.served() <= budget + 1,
                () -> "the body must be bounded as it is read: at most one byte past the budget was "
                        + "ever taken from the socket, and " + endless.served() + " were");
        assertTrue(endless.closed(),
                "the refusal path must still close the body, or the connection leaks");

        // Positive control: the same client, handler and bounded read, with a body under the budget.
        var answer = provider(request -> { }).complete(REQUEST);
        assertEquals("ok", assertInstanceOf(AssistantProvider.Turn.Answer.class, answer).text(),
                "the harness must be able to produce a success, or the refusal above proves nothing");
    }

    // ---------------------------------------------------------------------------------------------

    private static final AssistantProvider.Request REQUEST = new AssistantProvider.Request(
            "claude-opus-5", null, List.of(AssistantProvider.Message.author("hi")), List.of(), 100);

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * <b>An OAuth token is presented as a bearer token with the beta header, never as an API key.</b>
     *
     * <h2>Why this is a wire test and not a unit test on the credential</h2>
     * <p>Because the failure it guards against is invisible everywhere else. An access token put into
     * {@code x-api-key} is a perfectly well-formed request that this build would send happily; the
     * provider answers 401, the adapter reports {@code PROVIDER_REJECTED} — "check the configured
     * profile" — and an operator goes looking for a revoked key that was never issued. Nothing in the
     * types would be wrong. Only the bytes would be.</p>
     *
     * <p>The {@code anthropic-beta: oauth-2025-04-20} header is asserted for the same reason and is
     * not optional: {@code /v1/messages} does not accept a bearer token without it. Asserting the
     * absence of {@code x-api-key} matters as much as asserting the presence of the other two —
     * sending both credentials is a request the provider rejects outright, so a "belt and braces"
     * adapter that set both would fail every call.</p>
     *
     * <p><b>Not verified against a real provider.</b> No test in this suite has ever made a network
     * request — see this class's own fake client — so what is pinned here is that the adapter emits
     * the documented shape, not that the provider accepts it.</p>
     *
     * <p><b>Mutation proof.</b> Make {@code complete} write {@code x-api-key} unconditionally and all
     * three assertions turn red.</p>
     */
    @Test
    void anOauthTokenIsSentAsABearerTokenWithTheBetaHeaderAndNoApiKey() throws Exception {
        var captured = new java.util.concurrent.atomic.AtomicReference<HttpRequest>();
        var provider = providerWithCredential(captured::set,
                AssistantCredential.oauthToken("oauth-access-token-CANARY"));

        provider.complete(oneTurn());

        HttpRequest request = captured.get();
        assertEquals(Optional.of("Bearer oauth-access-token-CANARY"),
                request.headers().firstValue("authorization"),
                "an OAuth token must be presented as a bearer token");
        assertEquals(Optional.of("oauth-2025-04-20"),
                request.headers().firstValue("anthropic-beta"),
                "/v1/messages does not accept a bearer token without the oauth beta header");
        assertTrue(request.headers().firstValue("x-api-key").isEmpty(),
                "sending both credentials is a request the provider rejects, not a safer one");
    }

    /**
     * <b>An operator API key goes in {@code x-api-key}, with no bearer header.</b>
     *
     * <p>The control for the test above. Without it, an adapter that had simply switched everything
     * to bearer tokens would pass that one and silently break every existing keyed deployment.</p>
     */
    @Test
    void anOperatorApiKeyIsStillSentAsAnApiKeyAndNotAsABearerToken() throws Exception {
        var captured = new java.util.concurrent.atomic.AtomicReference<HttpRequest>();
        var provider = providerWithCredential(captured::set, AssistantCredential.ofNullable(CREDENTIAL));

        provider.complete(oneTurn());

        HttpRequest request = captured.get();
        assertEquals(Optional.of(CREDENTIAL), request.headers().firstValue("x-api-key"),
                "the operator key path must remain unchanged");
        assertTrue(request.headers().firstValue("authorization").isEmpty(),
                "an API key is not a bearer token");
        assertTrue(request.headers().firstValue("anthropic-beta").isEmpty(),
                "the oauth beta header must not ride along on a keyed request");
    }

    private static AssistantProvider.Request oneTurn() {
        return new AssistantProvider.Request("claude-opus-5", "system",
                List.of(AssistantProvider.Message.author("hello")), List.of(), 1024);
    }

    private static AnthropicAssistantProvider providerWithCredential(
            java.util.function.Consumer<HttpRequest> capture, AssistantCredential credential) {
        return new AnthropicAssistantProvider(
                new CapturingHttpClient(capture, 200,
                        () -> new ByteArrayInputStream(utf8("{\"model\":\"claude-opus-5\","
                                + "\"stop_reason\":\"end_turn\",\"content\":"
                                + "[{\"type\":\"text\",\"text\":\"ok\"}]}"))),
                ENDPOINT, "claude-opus-5", credential, Duration.ofSeconds(5));
    }

    private static AnthropicAssistantProvider provider(java.util.function.Consumer<HttpRequest> capture) {
        return provider(capture, 200,
                "{\"model\":\"claude-opus-5\",\"stop_reason\":\"end_turn\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}");
    }

    private static AnthropicAssistantProvider provider(java.util.function.Consumer<HttpRequest> capture,
                                                       int status, String body) {
        return provider(capture, status, () -> new ByteArrayInputStream(utf8(body)));
    }

    private static AnthropicAssistantProvider provider(java.util.function.Consumer<HttpRequest> capture,
                                                       int status,
                                                       java.util.function.Supplier<InputStream> body) {
        return providerWithClient(new CapturingHttpClient(capture, status, body));
    }

    /** For substituting a whole {@link HttpClient}, such as {@link ThrowingHttpClient}. */
    private static AnthropicAssistantProvider providerWithClient(HttpClient client) {
        return new AnthropicAssistantProvider(client, ENDPOINT,
                "claude-opus-5", AssistantCredential.ofNullable(CREDENTIAL), Duration.ofSeconds(5));
    }

    /**
     * A body with no end, which records how much of it was actually taken.
     *
     * <p>{@code served()} is the assertion that matters: it is the difference between "the adapter
     * rejected a large response" and "the adapter stopped reading at the budget". Only the second is
     * the bounded-read contract requires, and only an endless body can tell them apart.</p>
     */
    private static final class EndlessStream extends InputStream {
        private long served;
        private boolean closed;

        @Override
        public int read() {
            served++;
            return ' ';
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            java.util.Arrays.fill(buffer, offset, offset + length, (byte) ' ');
            served += length;
            return length;
        }

        @Override
        public void close() {
            closed = true;
        }

        long served() {
            return served;
        }

        boolean closed() {
            return closed;
        }
    }

    /**
     * A client that records the request and replays a canned response.
     *
     * <p>Deliberately a substitute for the <em>injected</em> client rather than a way of intercepting
     * one the adapter built: the adapter has no way to build one, which is the property
     * {@code AssistantProvider}'s own Javadoc records and this class relies on.</p>
     */
    private static class CapturingHttpClient extends HttpClient {
        private final java.util.function.Consumer<HttpRequest> capture;
        private final int status;
        private final java.util.function.Supplier<InputStream> body;

        CapturingHttpClient(java.util.function.Consumer<HttpRequest> capture, int status,
                            java.util.function.Supplier<InputStream> body) {
            this.capture = capture;
            this.status = status;
            this.body = body;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            capture.accept(request);
            return (HttpResponse<T>) new CannedResponse(request, status, body.get());
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.completedFuture(send(request, handler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> handler,
                                                                HttpResponse.PushPromiseHandler<T> promises) {
            return sendAsync(request, handler);
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (java.security.NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException(unavailable);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public WebSocket.Builder newWebSocketBuilder() {
            throw new UnsupportedOperationException("the assistant never opens a web socket");
        }
    }

    /**
     * A client whose {@code send} throws a bare unchecked exception, standing in for the injected
     * client: a client bug, a stub, or a transport failure not covered by any of
     * {@code complete}'s named catches.
     */
    private static final class ThrowingHttpClient extends CapturingHttpClient {
        ThrowingHttpClient() {
            super(request -> { }, 200, () -> new ByteArrayInputStream(new byte[0]));
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new IllegalStateException("boom-injected-by-the-test");
        }
    }

    private record CannedResponse(HttpRequest request, int statusCode, InputStream body)
            implements HttpResponse<InputStream> {
        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (a, b) -> true);
        }

        @Override
        public InputStream body() {
            return body;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
