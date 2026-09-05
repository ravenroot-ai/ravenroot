package ai.ravenroot.core.security.egress;

import ai.ravenroot.api.node.service.ExternalIoLimits;
import ai.ravenroot.api.node.service.OutboundHttpRepresentationPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.net.http.HttpClient.Version;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        server.createContext("/json-gzip", exchange -> {
            byte[] payload = gzip("{\"answer\":42}".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(payload); }
        });
        server.createContext("/gzip-bomb", exchange -> {
            byte[] payload = gzip(new byte[64 * 1024]);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(payload); }
        });
        server.createContext("/gzip-trailing", exchange -> {
            byte[] member = gzip("{}".getBytes(StandardCharsets.UTF_8));
            byte[] payload = java.util.Arrays.copyOf(member, member.length + 1);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(payload); }
        });
        server.createContext("/gzip-concatenated", exchange -> {
            byte[] member = gzip("{}".getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[member.length * 2];
            System.arraycopy(member, 0, payload, 0, member.length);
            System.arraycopy(member, 0, payload, member.length, member.length);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(payload); }
        });
        server.createContext("/gzip-malformed", exchange -> {
            byte[] payload = {(byte) 0x1f, (byte) 0x8b, 8, 12, 0, 0, 0, 0, 0, 0,
                    (byte) 0xff, (byte) 0xff, 0, 0, 0, 0, 0, 0};
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(payload); }
        });
        server.createContext("/gzip-fhcrc", exchange -> {
            byte[] member = gzip("{}".getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[member.length + 2];
            System.arraycopy(member, 0, payload, 0, 10);
            payload[3] = 2;
            System.arraycopy(member, 10, payload, 12, member.length - 10);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(payload); }
        });
        server.createContext("/empty", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/empty-br", exchange -> {
            exchange.getResponseHeaders().set("Content-Encoding", "br");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/wrong-media", exchange -> {
            byte[] payload = "not-json".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(payload); }
        });
        server.createContext("/error-text", exchange -> {
            byte[] payload = "bounded remote error".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(429, payload.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(payload); }
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
    void gzipIsDecodedOnlyWithinMediaEncodedDecodedAndRatioLimits() throws Exception {
        ExternalIoLimits limits = ExternalIoLimits.compressedHttp(1, 1024, 1024, 1024, 20,
                Duration.ofSeconds(2), Set.of("application/json"));
        byte[] value = HttpClient.newHttpClient().sendAsync(
                HttpRequest.newBuilder(base.resolve("/json-gzip")).build(),
                BoundedBodyHandlers.withLimits(limits)).thenApply(HttpResponse::body).get();
        assertEquals("{\"answer\":42}", new String(value, StandardCharsets.UTF_8));

        ExternalIoLimits ratioOne = ExternalIoLimits.compressedHttp(1, 1024, 128 * 1024, 128 * 1024, 1,
                Duration.ofSeconds(2), Set.of("application/json"));
        ExecutionException refused = assertThrows(ExecutionException.class, () -> HttpClient.newHttpClient()
                .sendAsync(HttpRequest.newBuilder(base.resolve("/gzip-bomb")).build(),
                        BoundedBodyHandlers.withLimits(ratioOne)).thenApply(HttpResponse::body).get());
        assertInstanceOf(BoundedBodyHandlers.ResponseTooLargeException.class, refused.getCause());
    }

    @Test
    void mediaTypeIsCheckedBeforeBodyButMissingTypeIsAllowedOnlyForEmptyBody() throws Exception {
        ExternalIoLimits limits = ExternalIoLimits.http(1, 1024, Duration.ofSeconds(2),
                Set.of("application/json"));
        assertEquals(0, HttpClient.newHttpClient().sendAsync(
                HttpRequest.newBuilder(base.resolve("/empty")).build(),
                BoundedBodyHandlers.withLimits(limits)).thenApply(response -> response.body().length).get());
        ExecutionException refused = assertThrows(ExecutionException.class, () -> HttpClient.newHttpClient()
                .sendAsync(HttpRequest.newBuilder(base.resolve("/wrong-media")).build(),
                        BoundedBodyHandlers.withLimits(limits)).thenApply(HttpResponse::body).get());
        assertInstanceOf(BoundedBodyHandlers.ResponseMediaTypeException.class, refused.getCause());
        ExecutionException encoding = assertThrows(ExecutionException.class,
                () -> HttpClient.newHttpClient().sendAsync(
                        HttpRequest.newBuilder(base.resolve("/empty-br")).build(),
                        BoundedBodyHandlers.withLimits(limits)).thenApply(HttpResponse::body).get());
        assertInstanceOf(BoundedBodyHandlers.ResponseEncodingException.class, encoding.getCause());
    }

    @Test
    void statusAwareHandlerBoundsButDoesNotInterpretErrorRepresentations() throws Exception {
        ExternalIoLimits limits = ExternalIoLimits.compressedHttp(1, 64, 64, 8, 10,
                Duration.ofSeconds(2), Set.of("application/json"));
        HttpResponse<byte[]> response = HttpClient.newHttpClient().sendAsync(
                HttpRequest.newBuilder(base.resolve("/error-text")).build(),
                BoundedBodyHandlers.withLimitsForSuccess(limits)).get();
        assertEquals(429, response.statusCode());
        assertEquals("bounded remote error", new String(response.body(), StandardCharsets.UTF_8));

        ExternalIoLimits tooSmall = ExternalIoLimits.compressedHttp(1, 4, 64, 64, 10,
                Duration.ofSeconds(2), Set.of("application/json"));
        ExecutionException oversized = assertThrows(ExecutionException.class, () -> HttpClient.newHttpClient()
                .sendAsync(HttpRequest.newBuilder(base.resolve("/error-text")).build(),
                        BoundedBodyHandlers.withLimitsForSuccess(tooSmall)).get());
        assertInstanceOf(BoundedBodyHandlers.ResponseTooLargeException.class, oversized.getCause());

        ExecutionException selected = assertThrows(ExecutionException.class,
                () -> HttpClient.newHttpClient().sendAsync(
                        HttpRequest.newBuilder(base.resolve("/error-text")).build(),
                        BoundedBodyHandlers.withLimits(limits,
                                new OutboundHttpRepresentationPolicy(false, Set.of(429)))).get());
        assertInstanceOf(BoundedBodyHandlers.ResponseMediaTypeException.class, selected.getCause());
    }

    @Test
    void projectedOutputLimitIsNotMisappliedToDecodedTransportBytes() throws Exception {
        ExternalIoLimits limits = new ExternalIoLimits(1, 16, 16, 1, 1,
                Duration.ofSeconds(2), Duration.ofSeconds(1), Set.of(), Set.of("identity"));
        byte[] body = HttpClient.newHttpClient().sendAsync(
                HttpRequest.newBuilder(base.resolve("/sized?2")).build(),
                BoundedBodyHandlers.withLimits(limits)).thenApply(HttpResponse::body).get();
        assertEquals(2, body.length);
        assertThrows(IllegalArgumentException.class, () -> limits.requireOutputBytes(body.length));
    }

    @Test
    void trailingAndConcatenatedGzipMembersAreRefused() {
        ExternalIoLimits limits = ExternalIoLimits.compressedHttp(1, 1024, 1024, 1024, 100,
                Duration.ofSeconds(2), Set.of("application/json"));
        for (String path : List.of("/gzip-trailing", "/gzip-concatenated", "/gzip-malformed", "/gzip-fhcrc")) {
            ExecutionException refused = assertThrows(ExecutionException.class,
                    () -> HttpClient.newHttpClient().sendAsync(HttpRequest.newBuilder(base.resolve(path)).build(),
                            BoundedBodyHandlers.withLimits(limits)).thenApply(HttpResponse::body).get());
            assertInstanceOf(BoundedBodyHandlers.ResponseEncodingException.class, refused.getCause());
        }
    }

    @Test
    void streamedBreachCancelsSubscriptionAndDiscardsRetainedBytes() {
        ExternalIoLimits limits = ExternalIoLimits.http(1, 4, Duration.ofSeconds(1), Set.of());
        HttpResponse.BodySubscriber<byte[]> subscriber = BoundedBodyHandlers.withLimits(limits).apply(
                new HttpResponse.ResponseInfo() {
                    @Override public int statusCode() { return 200; }
                    @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
                    @Override public Version version() { return Version.HTTP_1_1; }
                });
        AtomicBoolean cancelled = new AtomicBoolean();
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long count) { }
            @Override public void cancel() { cancelled.set(true); }
        });
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[5])));
        assertTrue(cancelled.get());
        assertThrows(ExecutionException.class, () -> subscriber.getBody().toCompletableFuture().get());
    }

    @Test
    void missingRequiredMediaTypeCancelsBeforeCopyingTheFirstStreamedByte() {
        ExternalIoLimits limits = ExternalIoLimits.http(1, 1024, Duration.ofSeconds(1),
                Set.of("application/json"));
        HttpResponse.BodySubscriber<byte[]> subscriber = BoundedBodyHandlers.withLimits(limits).apply(
                new HttpResponse.ResponseInfo() {
                    @Override public int statusCode() { return 200; }
                    @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
                    @Override public Version version() { return Version.HTTP_1_1; }
                });
        AtomicBoolean cancelled = new AtomicBoolean();
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long count) { }
            @Override public void cancel() { cancelled.set(true); }
        });
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1])));
        assertTrue(cancelled.get());
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> subscriber.getBody().toCompletableFuture().get());
        assertInstanceOf(BoundedBodyHandlers.ResponseMediaTypeException.class, failure.getCause());
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

    private static byte[] gzip(byte[] value) throws java.io.IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(value); }
        return bytes.toByteArray();
    }
}
