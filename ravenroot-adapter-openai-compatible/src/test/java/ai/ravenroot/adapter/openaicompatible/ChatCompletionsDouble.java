package ai.ravenroot.adapter.openaicompatible;

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
 * A loopback stand-in for an OpenAI-compatible {@code /v1/chat/completions} endpoint.
 *
 * <p>Bound to {@link InetAddress#getLoopbackAddress()} on an ephemeral port. The provider's real
 * {@code EgressHttpClients} client is pointed at it through the operator-supplied endpoint, so the
 * request is really serialised, really authenticated and really parsed — which is what makes the
 * credential-safety assertions non-vacuous: the key genuinely travels through the code under test and
 * is genuinely observed arriving here.
 *
 * <p>The canned bodies below are the shape Ollama's OpenAI-compatible endpoint returns for a
 * {@code qwen3}, a representative local target. Nothing here contacts Ollama, and Ollama does not
 * need to be running.
 *
 * <h2>What this double does NOT prove</h2>
 * <ul>
 *   <li><b>Wire compatibility with any actual server.</b> Every response body here is written by this
 *   test. If Ollama, LM Studio or a hosted gateway changes shape, these tests keep passing. This is
 *   the largest gap, and it is the same one the sibling adapter records — with no vendor SDK in the
 *   dependency graph there is no upgrade that would notify anyone that a shape moved.</li>
 *   <li><b>Authentication.</b> It records the {@code authorization} header it receives and accepts
 *   anything. A wrong or revoked key produces a real 401 from a real server and nothing here.</li>
 *   <li><b>TLS.</b> Plain HTTP over loopback: certificate validation, the TLS-version floor and the
 *   hostname-verification kill switch — all three of {@code EgressHttpClients}'s TLS rules — are
 *   exercised by {@code EgressTlsRulesTest} in {@code ravenroot-core} and not here.</li>
 *   <li><b>Proxy absence.</b> {@code EgressProxyAbsenceTest} pins {@code NO_PROXY} on the same client
 *   this adapter builds; loopback would not exercise it regardless.</li>
 *   <li><b>Retry and rate-limit behaviour.</b> The adapter performs no retry at all, so there is no
 *   backoff or {@code retry-after} handling to exercise.</li>
 *   <li><b>Model behaviour.</b> Refusal, truncation and content shapes are canned, not produced by a
 *   model. In particular nothing here proves how a real {@code qwen3} formats its reasoning.</li>
 *   <li><b>DNS and reserved-network reach.</b> A loopback literal never reaches the resolver SPI, so
 *   {@code EgressAddressGuard} and {@code ReservedNetworkPolicy} have no say in any test here. That
 *   is also why a literal {@code 127.0.0.1} target needs no egress
 *   configuration — see the provider's Javadoc.</li>
 * </ul>
 */
final class ChatCompletionsDouble implements AutoCloseable {

    private final HttpServer server;
    private final AtomicReference<String> observedAuthorization = new AtomicReference<>("");
    private final AtomicReference<String> observedBody = new AtomicReference<>("");
    private final List<String> observedAuthorizations = new CopyOnWriteArrayList<>();
    private final List<String> observedBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicLong bytesServed = new AtomicLong();

    private volatile int status = 200;
    private volatile String responseBody = completion("a perfectly ordinary answer");
    private volatile String location;
    private volatile long chunkedBytes = -1;

    private ChatCompletionsDouble(HttpServer server) {
        this.server = server;
    }

    static ChatCompletionsDouble start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ChatCompletionsDouble started = new ChatCompletionsDouble(server);
        server.createContext("/", started::handle);
        server.start();
        return started;
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        String authorization = exchange.getRequestHeaders().getFirst("authorization");
        String seen = authorization == null ? "" : authorization;
        observedAuthorization.set(seen);
        observedAuthorizations.add(seen);
        try (InputStream body = exchange.getRequestBody()) {
            String read = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            observedBody.set(read);
            observedBodies.add(read);
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

    ChatCompletionsDouble responds(int status, String body) {
        this.status = status;
        this.responseBody = body;
        this.location = null;
        this.chunkedBytes = -1;
        return this;
    }

    /** Answers {@code status} with a {@code Location} pointing at {@code target}'s endpoint. */
    ChatCompletionsDouble redirectsTo(int status, ChatCompletionsDouble target) {
        this.status = status;
        this.location = target.endpoint();
        this.chunkedBytes = -1;
        return this;
    }

    /** Answers 200 with no declared length and keeps writing until cancelled or {@code total} sent. */
    ChatCompletionsDouble respondsWithUndeclaredBody(long total) {
        this.status = 200;
        this.location = null;
        this.chunkedBytes = total;
        return this;
    }

    /** The full endpoint URI, in the shape the provider requires: origin plus the complete path. */
    String endpoint() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort()
                + "/v1/chat/completions";
    }

    /** The {@code authorization} header value this double last received, or {@code ""}. */
    String observedAuthorization() {
        return observedAuthorization.get();
    }

    /** Every {@code authorization} received, in arrival order. */
    List<String> observedAuthorizations() {
        return List.copyOf(observedAuthorizations);
    }

    /** The raw request body this double last received, or {@code ""}. */
    String observedBody() {
        return observedBody.get();
    }

    /** Every request body received, in arrival order. */
    List<String> observedBodies() {
        return List.copyOf(observedBodies);
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
        return completion(text, "stop", "qwen3");
    }

    static String completion(String text, String finishReason) {
        return completion(text, finishReason, "qwen3");
    }

    /** {@code model} is what the SERVER reports, which the adapter must not echo back. */
    static String completion(String text, String finishReason, String model) {
        return "{\"id\":\"chatcmpl-double\",\"object\":\"chat.completion\",\"created\":1735689600,"
                + "\"model\":\"" + model + "\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
                + text + "\"},\"finish_reason\":\"" + finishReason + "\"}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18}}";
    }

    /** The shape sent when the turn carried only tool calls: {@code content} is JSON null. */
    static String nullContentCompletion() {
        return "{\"id\":\"chatcmpl-double\",\"object\":\"chat.completion\",\"created\":1735689600,"
                + "\"model\":\"qwen3\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":null},\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":0,\"total_tokens\":11}}";
    }

    static String noChoicesCompletion() {
        return "{\"id\":\"chatcmpl-double\",\"object\":\"chat.completion\",\"created\":1735689600,"
                + "\"model\":\"qwen3\",\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":0,\"total_tokens\":11}}";
    }

    /**
     * An error body that quotes {@code echoed} back at the caller — the worst realistic case, in
     * which the remote end reflects part of what it was sent into its own error message.
     */
    static String error(String type, String echoed) {
        return "{\"error\":{\"type\":\"" + type + "\",\"message\":\"" + echoed + "\"}}";
    }
}
