package ai.ravenroot.devharness;

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
 * <p>The far end of {@link DevHarnessArmingTest}, and the only double in it: everything between the
 * node property and the socket — {@code BehaviorRegistry}, {@link LlmPromptNodeBehaviorFactory},
 * {@code ModelProviderRegistry}, {@code OpenAiCompatibleModelProvider}, {@code EgressHttpClients} — is
 * the code the bench actually runs. That is what makes the arming assertions statements about the
 * bench rather than about a fixture.</p>
 *
 * <p>Bound to {@link InetAddress#getLoopbackAddress()} with an ephemeral port, so a test run opens no
 * reachable listener. This module is the one that must never serve a network (condition 2 of H28), and
 * that applies to its tests as much as to {@code DevHarnessMain}.</p>
 */
final class ChatCompletionsDouble implements AutoCloseable {

    private final HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>("");
    private final AtomicReference<String> responseBody = new AtomicReference<>(completion("unset"));
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger calls = new AtomicInteger();
    private final ConcurrentLinkedQueue<String> authorizations = new ConcurrentLinkedQueue<>();

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
    }

    /** The complete chat-completions URI, which is the shape {@code RAVENROOT_DEV_MODEL_*_ENDPOINT} takes. */
    String endpoint() {
        return "http://" + server.getAddress().getAddress().getHostAddress() + ":"
                + server.getAddress().getPort() + "/v1/chat/completions";
    }

    /** The host:port pair, for the outbound policy this bench composes from its environment. */
    String host() {
        return server.getAddress().getAddress().getHostAddress();
    }

    int port() {
        return server.getAddress().getPort();
    }

    String observedBody() {
        return body.get();
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
        calls.incrementAndGet();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        authorizations.add(authorization == null ? "" : authorization);
        try (InputStream input = exchange.getRequestBody()) {
            body.set(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        byte[] payload = responseBody.get().getBytes(StandardCharsets.UTF_8);
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
