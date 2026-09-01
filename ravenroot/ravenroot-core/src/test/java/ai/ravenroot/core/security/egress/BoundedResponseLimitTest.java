package ai.ravenroot.core.security.egress;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The response ceiling is a gate, not a default (SEC-10).
 *
 * <p>The server is bound to the loopback <em>literal</em> 127.0.0.1 on purpose: a literal never
 * reaches the resolver SPI, so this suite exercises the volume gate without the reach filter having
 * any say in it. Reach is tested separately; volume policy remains outside this test.
 */
class BoundedResponseLimitTest {

    private HttpServer server;
    private URI base;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/sized", exchange -> {
            int size = Integer.parseInt(exchange.getRequestURI().getQuery());
            byte[] payload = new byte[size];
            java.util.Arrays.fill(payload, (byte) 'a');
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.createContext("/undeclared", exchange -> {
            // Chunked: no Content-Length, so only the streaming check can catch this one.
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                byte[] chunk = new byte[4096];
                java.util.Arrays.fill(chunk, (byte) 'b');
                for (int i = 0; i < 64; i++) {
                    out.write(chunk);
                }
            }
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String get(String path, long limit) throws Exception {
        HttpClient client = EgressHttpClients.create();
        return client.sendAsync(HttpRequest.newBuilder(base.resolve(path)).build(),
                        BoundedBodyHandlers.ofString(limit, StandardCharsets.UTF_8))
                .thenApply(HttpResponse::body)
                .get();
    }

    @Test
    @DisplayName("a body under the ceiling is returned intact")
    void underTheCeilingTheBodyIsIntact() throws Exception {
        assertEquals(1000, get("/sized?1000", 4096).length());
    }

    @Test
    @DisplayName("a declared Content-Length over the ceiling is refused before the body is read")
    void declaredOversizeIsRefused() {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> get("/sized?9000", 4096));
        assertInstanceOf(BoundedBodyHandlers.ResponseTooLargeException.class, failure.getCause());
    }

    @Test
    @DisplayName("an undeclared oversize body is refused while streaming")
    void undeclaredOversizeIsRefusedWhileStreaming() {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> get("/undeclared", 4096));
        assertInstanceOf(BoundedBodyHandlers.ResponseTooLargeException.class, failure.getCause());
    }

    @Test
    @DisplayName("a body exactly at the ceiling is allowed, so the boundary is not off by one")
    void exactlyAtTheCeilingIsAllowed() throws Exception {
        assertEquals(4096, get("/sized?4096", 4096).length());
    }

    @Test
    @DisplayName("one byte over the ceiling is refused")
    void oneByteOverIsRefused() {
        assertThrows(ExecutionException.class, () -> get("/sized?4097", 4096));
    }

    @Test
    @DisplayName("there is no way to ask for an unlimited body")
    void thereIsNoUnlimitedCeiling() {
        assertThrows(IllegalArgumentException.class,
                () -> BoundedBodyHandlers.ofString(0, StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class,
                () -> BoundedBodyHandlers.ofString(-1, StandardCharsets.UTF_8));
    }

    // ---- request side: same gate, same terms ---------------------------

    @Test
    @DisplayName("an oversize request body is refused before the request is built")
    void oversizeRequestBodyIsRefused() {
        var policy = ai.ravenroot.core.security.OutboundHttpPolicy
                .fromCommaSeparated("example.com", null, 0, 1024);
        SecurityException refused = assertThrows(SecurityException.class,
                () -> policy.requireRequestWithinLimit(new byte[1025]));
        assertTrue(refused.getMessage().contains("request body"), refused.getMessage());
    }

    @Test
    @DisplayName("a request body at or under the limit is allowed, so the boundary is not off by one")
    void requestBodyAtTheLimitIsAllowed() {
        var policy = ai.ravenroot.core.security.OutboundHttpPolicy
                .fromCommaSeparated("example.com", null, 0, 1024);
        policy.requireRequestWithinLimit(new byte[1024]);
        policy.requireRequestWithinLimit(new byte[0]);
        policy.requireRequestWithinLimit(null);
    }

    @Test
    @DisplayName("there is no unlimited request ceiling either")
    void requestCeilingIsAlwaysPositive() {
        for (long configured : new long[] {0, -1, Long.MIN_VALUE}) {
            var policy = ai.ravenroot.core.security.OutboundHttpPolicy
                    .fromCommaSeparated("example.com", null, 0, configured);
            assertTrue(policy.maximumRequestBytes() > 0,
                    "a non-positive configured value must not mean unlimited");
        }
        assertTrue(ai.ravenroot.core.security.OutboundHttpPolicy.disabled().maximumRequestBytes() > 0);
    }

    @Test
    @DisplayName("the shipped policy ceiling is positive whatever the operator configured")
    void theShippedCeilingIsAlwaysPositive() {
        assertTrue(ai.ravenroot.core.security.OutboundHttpPolicy.disabled().maximumResponseBytes() > 0);
        assertTrue(ai.ravenroot.core.security.OutboundHttpPolicy
                .fromCommaSeparated("example.com", null, -5).maximumResponseBytes() > 0);
        assertTrue(ai.ravenroot.core.security.OutboundHttpPolicy
                .fromCommaSeparated("example.com", null, 0).maximumResponseBytes() > 0);
    }
}
