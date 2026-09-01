package ai.ravenroot.adapter.anthropic;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A loopback stand-in for {@code api.anthropic.com}.
 *
 * <p>Bound to {@link InetAddress#getLoopbackAddress()} on an ephemeral port. The provider's real
 * {@code EgressHttpClients} client is pointed at it through the operator-supplied {@code baseUrl}, so
 * the request is really serialised, really authenticated and really parsed — which is what makes the
 * credential-safety assertions non-vacuous: the operator key genuinely travels through the code under
 * test and is genuinely observed arriving here.
 *
 * <h2>What this double does NOT prove</h2>
 * <p>Derived directly from the adapter contract; the list
 * omitted the two things that had actually gone wrong. Redirects and the response ceiling are now the
 * first two entries because they were the defects, not hypotheticals.
 * <ul>
 *   <li><b>Wire compatibility with the actual service.</b> Every response body here is written by
 *   this test. If the Messages API changes shape, these tests keep passing. This is the largest gap
 *   and it grew when the vendor SDK was dropped: an SDK upgrade used to be a free notification that
 *   a request or response shape had moved, and hand-written JSON gives no such signal.</li>
 *   <li><b>Authentication.</b> It records the {@code x-api-key} it receives and accepts anything. A
 *   wrong or revoked key produces a real 401 from the real service and nothing here.</li>
 *   <li><b>TLS.</b> Plain HTTP over loopback: certificate validation, the TLS-version floor and the
 *   hostname-verification kill switch — all three of {@code EgressHttpClients}'s TLS rules — are
 *   exercised by {@code EgressTlsRulesTest} in {@code ravenroot-core} and not here.</li>
 *   <li><b>Proxy absence.</b> {@code EgressProxyAbsenceTest} pins {@code NO_PROXY} on the same client
 *   this adapter builds; loopback would not exercise it regardless.</li>
 *   <li><b>Retry and rate-limit behaviour.</b> The adapter performs no retry at all, so there is no
 *   backoff or {@code retry-after} handling to exercise — a deliberate narrowing from the vendor
 *   SDK's default of two, which retried a request nothing here had budgeted for.</li>
 *   <li><b>Model behaviour.</b> Refusal, truncation and content shapes are canned, not produced by a
 *   model.</li>
 *   <li><b>DNS and reserved-network reach.</b> A loopback literal never reaches the resolver SPI, so
 *   {@code EgressAddressGuard} and {@code ReservedNetworkPolicy} have no say in any test here.</li>
 * </ul>
 *
 * <p><b>What it does prove, and did not before:</b> that a {@code 307} is not followed and the second
 * host receives nothing ({@code AnthropicModelProviderEgressTest}), and that a response larger than
 * the declared budget is refused while it streams rather than buffered — measured by how many bytes
 * this double managed to write before the subscription was cancelled.
 */
final class AnthropicMessagesDouble implements AutoCloseable {

    private final HttpServer server;
    private final AtomicReference<String> observedApiKey = new AtomicReference<>("");
    private final AtomicReference<String> observedBody = new AtomicReference<>("");
    private final List<String> observedApiKeys = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicLong bytesServed = new AtomicLong();

    private volatile int status = 200;
    private volatile String responseBody = completion("a perfectly ordinary answer");
    private volatile String location;
    private volatile long chunkedBytes = -1;

    private AnthropicMessagesDouble(HttpServer server) {
        this.server = server;
    }

    static AnthropicMessagesDouble start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AnthropicMessagesDouble started = new AnthropicMessagesDouble(server);
        server.createContext("/", started::handle);
        server.start();
        return started;
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        String key = exchange.getRequestHeaders().getFirst("x-api-key");
        observedApiKey.set(key == null ? "" : key);
        observedApiKeys.add(key == null ? "" : key);
        try (InputStream body = exchange.getRequestBody()) {
            observedBody.set(new String(body.readAllBytes(), StandardCharsets.UTF_8));
        }
        if (location != null) {
            exchange.getResponseHeaders().add("location", location);
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        if (chunkedBytes >= 0) {
            // No Content-Length: only a streaming check can catch this one, which is the case the
            // adapter's ceiling has to survive.
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[64 * 1024];
            java.util.Arrays.fill(chunk, (byte) ' ');
            try (OutputStream out = exchange.getResponseBody()) {
                for (long written = 0; written < chunkedBytes; written += chunk.length) {
                    out.write(chunk);
                    bytesServed.addAndGet(chunk.length);
                }
            } catch (IOException cancelled) {
                // Expected once the client cancels the subscription: this is the observation the
                // oversize test makes, recorded in bytesServed rather than swallowed silently.
            }
            exchange.close();
            return;
        }
        byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        bytesServed.addAndGet(payload.length);
        exchange.close();
    }

    AnthropicMessagesDouble responds(int status, String body) {
        this.status = status;
        this.responseBody = body;
        this.location = null;
        this.chunkedBytes = -1;
        return this;
    }

    /** Answers {@code status} with a {@code Location} pointing at {@code target}'s Messages path. */
    AnthropicMessagesDouble redirectsTo(int status, AnthropicMessagesDouble target) {
        this.status = status;
        this.location = target.baseUrl() + AnthropicModelProvider.MESSAGES_PATH;
        this.chunkedBytes = -1;
        return this;
    }

    /** Answers 200 with no declared length and keeps writing until cancelled or {@code total} sent. */
    AnthropicMessagesDouble respondsWithUndeclaredBody(long total) {
        this.status = 200;
        this.location = null;
        this.chunkedBytes = total;
        return this;
    }

    String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    /** The {@code x-api-key} header value this double last received, or {@code ""}. */
    String observedApiKey() {
        return observedApiKey.get();
    }

    /** Every {@code x-api-key} received, in arrival order. */
    List<String> observedApiKeys() {
        return List.copyOf(observedApiKeys);
    }

    /** The raw request body this double last received, or {@code ""}. */
    String observedBody() {
        return observedBody.get();
    }

    int calls() {
        return calls.get();
    }

    /**
     * Bytes this double actually managed to write.
     *
     * <p>The assertion that matters for the response ceiling: it is the difference between "the
     * adapter rejected a large response" and "the adapter stopped reading at the budget". Only the
     * second is what SEC-10 requires, and only a body with no declared end can tell them
     * apart.
     */
    long bytesServed() {
        return bytesServed.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- canned bodies -------------------------------------------------------------------------

    static String completion(String text) {
        return completion(text, "end_turn");
    }

    static String completion(String text, String stopReason) {
        return completion(text, stopReason, "claude-opus-5");
    }

    static String completion(String text, String stopReason, String model) {
        return "{\"id\":\"msg_double\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"" + model + "\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],"
                + "\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":11,\"output_tokens\":7}}";
    }

    static String emptyCompletion() {
        return "{\"id\":\"msg_double\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-opus-5\",\"content\":[],"
                + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":11,\"output_tokens\":0}}";
    }

    /**
     * An error body that quotes {@code echoed} back at the caller — the worst realistic case, in
     * which the remote end reflects part of what it was sent into its own error message.
     */
    static String error(String type, String echoed) {
        return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\""
                + echoed + "\"}}";
    }
}
