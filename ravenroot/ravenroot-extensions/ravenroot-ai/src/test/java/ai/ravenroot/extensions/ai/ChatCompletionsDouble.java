package ai.ravenroot.extensions.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A loopback stand-in for an OpenAI-compatible {@code /v1/chat/completions} endpoint.
 *
 * <p>The far end of {@link AiBundleEndToEndTest}, and the only double in it. Everything between the
 * node property and the socket -- {@code NodePackages}, {@code BehaviorRegistry},
 * {@link LlmPromptNodeBehavior}, {@link OpenAiCompatibleChat}, {@code ManagedNodePackageServices},
 * {@code EgressHttpClients} -- is the code an operator's own image actually runs. That is what makes
 * those assertions statements about the bundle rather than about a fixture.</p>
 *
 * <p>Carried over from {@code ravenroot-dev-harness}'s double of the same name. Bound to
 * {@link InetAddress#getLoopbackAddress()} with an ephemeral port, so a test run opens no reachable
 * listener.</p>
 */
final class ChatCompletionsDouble implements AutoCloseable {

    private final HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>("");
    private final AtomicReference<String> responseBody = new AtomicReference<>(completion("unset"));
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger calls = new AtomicInteger();
    private final ConcurrentLinkedQueue<String> authorizations = new ConcurrentLinkedQueue<>();
    private final java.util.List<String> script = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.List<String> observedBodies =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private ChatCompletionsDouble(HttpServer server) {
        this.server = server;
    }

    static ChatCompletionsDouble start() throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var endpoint = new ChatCompletionsDouble(server);
        server.createContext("/v1/chat/completions", endpoint::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return endpoint;
    }

    /** A minimal, well-formed chat-completions response carrying {@code text}. */
    static String completion(String text) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"}}]}";
    }

    void responds(int httpStatus, String json) {
        status.set(httpStatus);
        responseBody.set(json);
        // Cleared, or a later single response would be shadowed by an earlier script that indexes on
        // the cumulative call counter. Only reachable within one test today; a trap for the next one.
        script.clear();
    }

    /**
     * Answers each of {@code json} in turn, then repeats the last one.
     *
     * <p>Needed by the {@code agent} node and by nothing before it: a far end that says the same
     * thing forever cannot tell a loop that ran twice from a single call made twice.</p>
     */
    void respondsInSequence(String... json) {
        status.set(200);
        script.clear();
        script.addAll(List.of(json));
    }

    String endpoint() {
        return "http://" + host() + ":" + port() + "/v1/chat/completions";
    }

    String host() {
        return server.getAddress().getAddress().getHostAddress();
    }

    int port() {
        return server.getAddress().getPort();
    }

    String observedBody() {
        return body.get();
    }

    /** Every request body, in the order it arrived. */
    List<String> observedBodies() {
        return List.copyOf(observedBodies);
    }

    /** Empty when the request carried no {@code Authorization} header at all. */
    String observedAuthorization() {
        String last = null;
        for (String value : authorizations) {
            last = value;
        }
        return last == null ? "" : last;
    }

    List<String> observedAuthorizations() {
        return List.copyOf(authorizations);
    }

    int calls() {
        return calls.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        int call = calls.incrementAndGet();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        authorizations.add(authorization == null ? "" : authorization);
        try (InputStream input = exchange.getRequestBody()) {
            String received = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            body.set(received);
            observedBodies.add(received);
        }
        String answer = script.isEmpty()
                ? responseBody.get()
                : script.get(Math.min(call - 1, script.size() - 1));
        byte[] payload = answer.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status.get(), payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
