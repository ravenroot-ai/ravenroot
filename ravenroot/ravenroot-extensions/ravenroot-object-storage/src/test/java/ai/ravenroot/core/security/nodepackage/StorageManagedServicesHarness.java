package ai.ravenroot.core.security.nodepackage;

import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.security.SecretValue;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only public-seam composition harness; production code remains extension-only. */
public final class StorageManagedServicesHarness {
    public static Fixture create() {
        CapturingClient client = new CapturingClient();
        AtomicInteger generation = new AtomicInteger(1);
        AtomicReference<String> resolution = new AtomicReference<>();
        NodePackageEgressPolicy.Origin origin = new NodePackageEgressPolicy.Origin("https", "localhost", 443);
        NodePackageEgressPolicy policy = NodePackageEgressPolicy.builder()
                .allowOrigin("https", "localhost", 443).allowHttpMethod("GET").allowHttpMethod("PUT")
                .allowRequestHeader("content-type").allowRequestHeader("if-match").allowRequestHeader("if-none-match")
                .allowResponseHeader("etag").allowResponseHeader("x-amz-version-id")
                .byteLimits(1024, 1024, 1024).concurrencyLimits(4, 2).maximumDeadline(Duration.ofSeconds(5))
                .bindAwsSigV4("assets-s3", origin, "credential/storage", "eu-west-1", "s3").build();
        NodePackageServices services = ManagedNodePackageServices.builder("ai.ravenroot.extensions.storage", policy,
                        (packageId, tenant, reference) -> {
                            resolution.set(packageId + "/" + tenant + "/" + reference);
                            return Optional.of(new SecretValue(("AKID" + tenant + generation.get()
                                    + "\nsecret-key-" + generation.get()).toCharArray()));
                        })
                .grant(NodePackageCapability.OUTBOUND_HTTP).clientFactory(() -> client)
                .clock(Clock.fixed(Instant.parse("2013-05-24T00:00:00Z"), ZoneOffset.UTC)).build();
        return new Fixture(services, client, generation, resolution);
    }

    public record Fixture(NodePackageServices services, CapturingClient client, AtomicInteger generation,
                          AtomicReference<String> resolution) {
        public void rotate() { generation.incrementAndGet(); }
    }

    public static final class CapturingClient extends HttpClient {
        private final AtomicReference<HttpRequest> request = new AtomicReference<>();
        public HttpRequest request() { return request.get(); }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { try { return SSLContext.getDefault(); }
            catch (Exception failure) { throw new IllegalStateException(failure); } }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }

        @Override @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest sent, HttpResponse.BodyHandler<T> handler) {
            request.set(sent);
            return (HttpResponse<T>) response(sent);
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler) { return CompletableFuture.failedFuture(new UnsupportedOperationException()); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        private static HttpResponse<byte[]> response(HttpRequest request) {
            byte[] body = request.method().equals("GET") ? "managed".getBytes(StandardCharsets.UTF_8) : new byte[0];
            return new HttpResponse<>() {
                @Override public int statusCode() { return 200; }
                @Override public HttpRequest request() { return request; }
                @Override public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
                @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(
                        "etag", List.of("\"managed-etag\""), "x-amz-version-id", List.of("managed-version")),
                        (name, value) -> true); }
                @Override public byte[] body() { return body; }
                @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
                @Override public URI uri() { return request.uri(); }
                @Override public Version version() { return Version.HTTP_1_1; }
            };
        }
    }

    private StorageManagedServicesHarness() { }
}
