package ai.ravenroot.core.security.nodepackage;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.node.service.OutboundHttpSigning;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedNodePackageSigningTest {
    private static final URI DESTINATION = URI.create("https://localhost/items/a%20b?z=2&a=1");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2015-08-30T12:36:00Z"), ZoneOffset.UTC);

    @Test
    void operatorGrantBindsTenantDestinationCredentialRegionAndServicePerInvocation() {
        AtomicReference<String> resolved = new AtomicReference<>();
        AtomicInteger sequence = new AtomicInteger();
        CapturingClient client = new CapturingClient();
        NodePackageEgressPolicy policy = policyBuilder()
                .bindAwsSigV4("storage", origin(), "aws/storage", "eu-west-1", "s3")
                .build();
        ManagedNodePackageServices services = ManagedNodePackageServices
                .builder("test.signing", policy, (packageId, tenant, reference) -> {
                    resolved.set(packageId + '/' + tenant + '/' + reference);
                    return Optional.of(new SecretValue(("AKID" + tenant + sequence.incrementAndGet()
                            + "\nsecret-key\nsession-token").toCharArray()));
                })
                .grant(NodePackageCapability.OUTBOUND_HTTP).clientFactory(() -> client).clock(CLOCK).build();

        execute(services, "tenant-a", signedRequest());
        HttpRequest first = client.request.get();
        String firstAuthorization = first.headers().firstValue("Authorization").orElseThrow();
        String expectedAuthorization = AwsSigV4Signer.sign("POST", DESTINATION,
                Map.of("x-safe", List.of("value")), "payload".getBytes(StandardCharsets.UTF_8),
                CLOCK.instant(), "AKIDtenant-a1\nsecret-key\nsession-token".toCharArray(),
                "eu-west-1", "s3").headers().get("authorization").getFirst();
        assertEquals("test.signing/tenant-a/aws/storage", resolved.get());
        assertEquals(expectedAuthorization, firstAuthorization,
                "managed transport signs the same normalized request values that it sends");
        assertTrue(firstAuthorization.contains("/20150830/eu-west-1/s3/aws4_request"));
        assertTrue(firstAuthorization.contains("Credential=AKIDtenant-a1/"));
        assertEquals("session-token", first.headers().firstValue("x-amz-security-token").orElseThrow());
        assertEquals("239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5",
                first.headers().firstValue("x-amz-content-sha256").orElseThrow());
        assertEquals(DESTINATION, first.uri());

        execute(services, "tenant-b", signedRequest());
        String rotated = client.request.get().headers().firstValue("Authorization").orElseThrow();
        assertEquals("test.signing/tenant-b/aws/storage", resolved.get());
        assertTrue(rotated.contains("Credential=AKIDtenant-b2/"));
        assertNotEquals(firstAuthorization, rotated, "credentials are resolved for every tenant invocation");
    }

    @Test
    void absentOrWrongGrantAndCallerSensitiveHeadersFailBeforeCredentialOrTransport() {
        AtomicInteger resolutions = new AtomicInteger();
        CapturingClient client = new CapturingClient();
        ManagedNodePackageServices services = ManagedNodePackageServices.builder("test.signing",
                        policyBuilder().allowRequestHeader("Authorization")
                                .allowRequestHeader("x-amz-security-token").build(),
                        (packageId, tenant, reference) -> {
                            resolutions.incrementAndGet();
                            return Optional.of(new SecretValue("access\nsecret".toCharArray()));
                        })
                .grant(NodePackageCapability.OUTBOUND_HTTP).clientFactory(() -> client).clock(CLOCK).build();

        assertReason(NodePackageServiceException.Reason.DESTINATION_FORBIDDEN,
                services.outboundHttp().execute(message("tenant-a"), signedRequest()));
        assertReason(NodePackageServiceException.Reason.PROTOCOL_REFUSED,
                services.outboundHttp().execute(message("tenant-a"), new OutboundHttpRequest(DESTINATION,
                        "POST", Map.of("Authorization", List.of("smuggled")), new byte[0],
                        Duration.ofSeconds(1), null)));
        assertReason(NodePackageServiceException.Reason.PROTOCOL_REFUSED,
                services.outboundHttp().execute(message("tenant-a"), new OutboundHttpRequest(DESTINATION,
                        "POST", Map.of("x-amz-security-token", List.of("smuggled")), new byte[0],
                        Duration.ofSeconds(1), null)));
        assertEquals(0, resolutions.get());
        assertEquals(0, client.sends.get());
    }

    @Test
    void malformedCredentialFailsSanitizedAndNeverStartsTransport() {
        CapturingClient client = new CapturingClient();
        ManagedNodePackageServices services = ManagedNodePackageServices.builder("test.signing",
                        policyBuilder().bindAwsSigV4("storage", origin(), "aws/storage",
                                "eu-west-1", "s3").build(),
                        (packageId, tenant, reference) -> Optional.of(new SecretValue(
                                "access-only-without-secret".toCharArray())))
                .grant(NodePackageCapability.OUTBOUND_HTTP).clientFactory(() -> client).clock(CLOCK).build();

        CompletionException failure = assertThrows(CompletionException.class,
                () -> execute(services, "tenant-secret", signedRequest()));
        assertTrue(failure.getCause() instanceof NodePackageServiceException);
        assertEquals(NodePackageServiceException.Reason.CREDENTIAL_UNAVAILABLE,
                ((NodePackageServiceException) failure.getCause()).reason());
        assertFalse(failure.toString().contains("access-only"));
        assertFalse(failure.toString().contains("tenant-secret"));
        assertEquals(0, client.sends.get());
    }

    @Test
    void literalUnicodeTargetsFailBeforeCredentialResolutionOrTransport() {
        AtomicInteger resolutions = new AtomicInteger();
        CapturingClient client = new CapturingClient();
        ManagedNodePackageServices services = ManagedNodePackageServices.builder("test.signing",
                        policyBuilder().bindAwsSigV4("storage", origin(), "aws/storage",
                                "eu-west-1", "s3").build(),
                        (packageId, tenant, reference) -> {
                            resolutions.incrementAndGet();
                            return Optional.of(new SecretValue("access\nsecret".toCharArray()));
                        })
                .grant(NodePackageCapability.OUTBOUND_HTTP).clientFactory(() -> client).clock(CLOCK).build();

        URI decomposed = URI.create("https://localhost/cafe\u0301?q=e\u0301");
        URI composed = URI.create("https://localhost/caf\u00e9?q=\u00e9");
        for (URI destination : List.of(decomposed, composed)) {
            OutboundHttpRequest request = new OutboundHttpRequest(destination, "POST",
                    Map.of("x-safe", List.of("value")), new byte[0], Duration.ofSeconds(1), null,
                    new OutboundHttpSigning("storage"));
            assertReason(NodePackageServiceException.Reason.PROTOCOL_REFUSED,
                    services.outboundHttp().execute(message("tenant-a"), request));
        }

        assertEquals(0, resolutions.get(), "transport-rewritable targets fail before secret resolution");
        assertEquals(0, client.sends.get());
    }

    @Test
    void operatorSigningGrantIsHttpsOnlyBoundedUniqueAndFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> NodePackageEgressPolicy.builder()
                .bindAwsSigV4("sign", new NodePackageEgressPolicy.Origin("http", "example.test", 80),
                        "credential", "us-east-1", "s3"));
        assertThrows(IllegalArgumentException.class, () -> NodePackageEgressPolicy.builder()
                .bindAwsSigV4("sign", origin(), "credential", "bad/region", "s3"));
        assertThrows(IllegalArgumentException.class, () -> NodePackageEgressPolicy.builder()
                .bindAwsSigV4("sign", origin(), "credential", "us-east-1", "execute-api"));
        assertThrows(IllegalArgumentException.class, () -> NodePackageEgressPolicy.builder()
                .bindAwsSigV4("sign", origin(), "credential", "us-east-1", "s3")
                .bindAwsSigV4("sign", origin(), "credential", "us-east-1", "s3"));
        assertThrows(IllegalArgumentException.class, () -> NodePackageEgressPolicy.builder()
                .bindCredential("token", origin(), "x-amz-security-token", ""));
        NodePackageEgressPolicy policy = NodePackageEgressPolicy.builder()
                .bindAwsSigV4("sign", origin(), "credential", "us-east-1", "s3").build();
        assertEquals("s3", policy.requireAwsSigV4SigningGrant("sign", DESTINATION).service());
    }

    private static NodePackageEgressPolicy.Builder policyBuilder() {
        return NodePackageEgressPolicy.builder().allowOrigin("https", "localhost", 443)
                .allowHttpMethod("POST").allowRequestHeader("x-safe");
    }

    private static NodePackageEgressPolicy.Origin origin() {
        return new NodePackageEgressPolicy.Origin("https", "localhost", 443);
    }

    private static OutboundHttpRequest signedRequest() {
        return new OutboundHttpRequest(DESTINATION, "POST", Map.of("x-safe", List.of("value")),
                "payload".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2), null,
                new OutboundHttpSigning("storage"));
    }

    private static void execute(ManagedNodePackageServices services, String tenant, OutboundHttpRequest request) {
        services.outboundHttp().execute(message(tenant), request).completion().toCompletableFuture().join();
    }

    private static NodeMessage message(String tenant) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("request", tenant, "subject", PrincipalType.USER, "issuer"),
                id, id, id, id, Set.of(), "node", null, Map.of());
    }

    private static void assertReason(NodePackageServiceException.Reason reason, OutboundCall<?> call) {
        CompletionException failure = assertThrows(CompletionException.class,
                () -> call.completion().toCompletableFuture().join());
        assertTrue(failure.getCause() instanceof NodePackageServiceException);
        assertEquals(reason, ((NodePackageServiceException) failure.getCause()).reason());
    }

    private static final class CapturingClient extends HttpClient {
        private final AtomicReference<HttpRequest> request = new AtomicReference<>();
        private final AtomicInteger sends = new AtomicInteger();

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

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest sent, HttpResponse.BodyHandler<T> handler) {
            request.set(sent);
            sends.incrementAndGet();
            return (HttpResponse<T>) response(sent);
        }

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        private static HttpResponse<byte[]> response(HttpRequest request) {
            return new HttpResponse<>() {
                @Override public int statusCode() { return 200; }
                @Override public HttpRequest request() { return request; }
                @Override public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
                @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
                @Override public byte[] body() { return new byte[0]; }
                @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
                @Override public URI uri() { return request.uri(); }
                @Override public Version version() { return Version.HTTP_1_1; }
            };
        }
    }
}
