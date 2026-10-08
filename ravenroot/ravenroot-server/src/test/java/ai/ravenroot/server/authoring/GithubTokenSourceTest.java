package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringException;
import ai.ravenroot.api.security.SecretValue;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;

class GithubTokenSourceTest {
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

    @Test void enterpriseMintIsRepoScopedLeastPrivilegeAndExpirationChecked() throws Exception {
        var client = new TokenClient(201, ("{\"token\":\"installation\",\"expires_at\":\""
                + NOW.plusSeconds(3600) + "\"}").getBytes());
        var source = source(client);
        assertEquals("installation", source.token());
        assertEquals("https://github.example.test/api/v3/app/installations/456/access_tokens",
                client.request.uri().toString());
        String request = new String(client.requestBody);
        assertTrue(request.contains("\"repositories\":[\"graphs\"]"), request);
        assertTrue(request.contains("\"contents\":\"write\""), request);
        assertTrue(request.contains("\"pull_requests\":\"write\""), request);
        assertTrue(request.contains("\"metadata\":\"read\""), request);
    }

    @Test void oversizedAndFailedResponsesAreSanitizedWithoutSpuriousInterrupt() throws Exception {
        var oversized = new TokenClient(201, new byte[256 * 1024 + 1]);
        var oversizedSource = source(oversized);
        assertFailure(oversizedSource::token);

        var failed = new TokenClient(0, new byte[0]);
        failed.ioFailure = true;
        Thread.interrupted();
        var failedSource = source(failed);
        assertFailure(failedSource::token);
        assertFalse(Thread.currentThread().isInterrupted(), "ordinary I/O failure interrupted the request thread");
    }

    @Test void implausibleTokenExpirationIsRejected() throws Exception {
        var client = new TokenClient(201, ("{\"token\":\"installation\",\"expires_at\":\""
                + NOW.plusSeconds(30) + "\"}").getBytes());
        var tokenSource = source(client);
        assertFailure(tokenSource::token);
    }

    private static GithubTokenSource source(TokenClient client) throws Exception {
        var pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        return new GithubTokenSource(configuration(),
                ignored -> Optional.of(new SecretValue(pem.toCharArray())), client,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static GraphAuthoringConfiguration configuration() {
        var environment = new HashMap<String, String>();
        environment.put("RAVENROOT_GRAPH_AUTHORING_MODE", "GIT");
        environment.put("RAVENROOT_GRAPH_AUTHORING_REPOSITORY_OWNER", "platform");
        environment.put("RAVENROOT_GRAPH_AUTHORING_REPOSITORY_NAME", "graphs");
        environment.put("RAVENROOT_GRAPH_AUTHORING_GITHUB_API_BASE", "https://github.example.test/api/v3");
        environment.put("RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_MODE", "APP");
        environment.put("RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_REFERENCE", "git-authoring");
        environment.put("RAVENROOT_GRAPH_AUTHORING_GITHUB_APP_ID", "123");
        environment.put("RAVENROOT_GRAPH_AUTHORING_GITHUB_INSTALLATION_ID", "456");
        environment.put("RAVENROOT_GRAPH_AUTHORING_TENANT_NAMESPACES", "tenant-a=customers/a");
        environment.put("RAVENROOT_GRAPH_ARTIFACT_BASE_URL", "https://artifacts.example.test/releases/");
        return GraphAuthoringConfiguration.fromEnvironment(environment);
    }

    private static void assertFailure(Runnable action) {
        var failure = assertThrows(GraphAuthoringException.class, action::run);
        assertEquals(GraphAuthoringException.Failure.UNAVAILABLE, failure.failure());
        assertFalse(String.valueOf(failure.getMessage()).contains("token"));
    }

    private static final class TokenClient extends HttpClient {
        final int status;
        final byte[] response;
        HttpRequest request;
        byte[] requestBody;
        boolean ioFailure;
        TokenClient(int status, byte[] response) { this.status = status; this.response = response; }
        @Override @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            this.request = request;
            if (ioFailure) throw new IOException("provider detail that must stay internal");
            this.requestBody = read(request.bodyPublisher().orElseThrow());
            return (HttpResponse<T>) new Response(request, status, response);
        }
        private static byte[] read(HttpRequest.BodyPublisher publisher) {
            var output = new java.io.ByteArrayOutputStream();
            var done = new CompletableFuture<Void>();
            publisher.subscribe(new Flow.Subscriber<>() {
                public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                public void onNext(ByteBuffer value) { byte[] bytes = new byte[value.remaining()]; value.get(bytes); output.writeBytes(bytes); }
                public void onError(Throwable failure) { done.completeExceptionally(failure); }
                public void onComplete() { done.complete(null); }
            });
            done.join(); return output.toByteArray();
        }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            try { return CompletableFuture.completedFuture(send(request, handler)); }
            catch (IOException failure) { return CompletableFuture.failedFuture(failure); }
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                          HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler);
        }
    }

    private record Response(HttpRequest request, int statusCode, byte[] body)
            implements HttpResponse<byte[]> {
        @Override public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
