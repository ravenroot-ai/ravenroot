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
import java.util.concurrent.CopyOnWriteArrayList;
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
                .allowHttpMethod("DELETE")
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

    /** Builds the production managed service boundary for a real test S3 origin. */
    public static NodePackageServices realS3(URI endpoint, HttpClient client,
                                             String accessKey, String secretKey) {
        int port = endpoint.getPort() == -1 ? 443 : endpoint.getPort();
        NodePackageEgressPolicy.Origin origin = new NodePackageEgressPolicy.Origin(
                endpoint.getScheme(), endpoint.getHost(), port);
        NodePackageEgressPolicy policy = NodePackageEgressPolicy.builder()
                .allowOrigin(endpoint.getScheme(), endpoint.getHost(), port)
                .allowHttpMethod("GET").allowHttpMethod("PUT").allowHttpMethod("DELETE")
                .allowRequestHeader("content-type").allowRequestHeader("if-match")
                .allowRequestHeader("if-none-match").allowResponseHeader("etag")
                .allowResponseHeader("x-amz-version-id")
                .byteLimits(16 * 1024 * 1024, 16 * 1024 * 1024, 1024)
                .concurrencyLimits(8, 8).maximumDeadline(Duration.ofSeconds(10))
                .bindAwsSigV4("assets-s3", origin, "credential/storage", "us-east-1", "s3").build();
        return ManagedNodePackageServices.builder("ai.ravenroot.extensions.storage", policy,
                        (packageId, tenant, reference) -> Optional.of(
                                new SecretValue((accessKey + "\n" + secretKey).toCharArray())))
                .grant(NodePackageCapability.OUTBOUND_HTTP).clientFactory(() -> client).build();
    }

    public record Fixture(NodePackageServices services, CapturingClient client, AtomicInteger generation,
                          AtomicReference<String> resolution) {
        public void rotate() { generation.incrementAndGet(); }
    }

    public static final class CapturingClient extends HttpClient {
        private final AtomicReference<HttpRequest> request = new AtomicReference<>();
        private final List<HttpRequest> requests = new CopyOnWriteArrayList<>();
        private final AtomicInteger deletes = new AtomicInteger();
        public HttpRequest request() { return request.get(); }
        public List<HttpRequest> requests() { return List.copyOf(requests); }

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
            requests.add(sent);
            return (HttpResponse<T>) response(sent, deletes);
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler) { return CompletableFuture.failedFuture(new UnsupportedOperationException()); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        private static HttpResponse<byte[]> response(HttpRequest request, AtomicInteger deletes) {
            int status = 200;
            byte[] body;
            if (request.method().equals("DELETE")) {
                status = deletes.getAndIncrement() == 0 ? 204 : 404;
                body = status == 404 ? "<Error><Message>remote detail</Message></Error>"
                        .getBytes(StandardCharsets.UTF_8) : new byte[0];
            } else if (request.uri().getRawQuery() != null
                    && request.uri().getRawQuery().contains("list-type=2")) {
                boolean second = request.uri().getRawQuery().contains("continuation-token=");
                body = (second ? """
                        <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                          <IsTruncated>false</IsTruncated>
                          <Contents><Key>tenant-data/folder/second.txt</Key><Size>2</Size></Contents>
                        </ListBucketResult>
                        """ : """
                        <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                          <IsTruncated>true</IsTruncated><NextContinuationToken>managed+/=cursor</NextContinuationToken>
                          <Contents><Key>tenant-data/folder/first.txt</Key><Size>1</Size></Contents>
                        </ListBucketResult>
                        """).getBytes(StandardCharsets.UTF_8);
            } else {
                body = "managed".getBytes(StandardCharsets.UTF_8);
            }
            int responseStatus = status;
            return new HttpResponse<>() {
                @Override public int statusCode() { return responseStatus; }
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
