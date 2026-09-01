package ai.ravenroot.server.security;

import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.RavenrootServer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SseCredentialRevocationIntegrationTest {
    @Test
    void aRealJwksKeyRevocationTerminatesAnIdleAuthenticatedStream() throws Exception {
        try (var provider = new TestOidcProvider();
             var engine = new PekkoExecutionEngine("sse-jwks-revocation-test");
             var server = server(engine, provider);
             var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.start();
            Instant now = Instant.now();
            String token = TestOidcProvider.token(provider.key(), "alice", "user", now.plusSeconds(30),
                    TestOidcProvider.AUDIENCE, TestOidcProvider.ISSUER.toString(), now.minusSeconds(1), now,
                    "ravenroot.observe");
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.port() + "/v1/events"))
                            .header("Authorization", "Bearer " + token).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());

            var endOfStream = readers.submit(() -> consume(response.body()));
            provider.rotate();
            endOfStream.get(5, TimeUnit.SECONDS);
        }
    }

    private static RavenrootServer server(PekkoExecutionEngine engine, TestOidcProvider provider) {
        var authenticator = new JwtRequestAuthenticator(TestOidcProvider.ISSUER, TestOidcProvider.AUDIENCE,
                "token_kind", Duration.ofSeconds(30), new JwkSetProvider(provider.jwksUri(), Duration.ofSeconds(30)));
        var security = new HttpSecurityConfiguration(new BrowserOriginPolicy(java.util.Set.of("http://127.0.0.1:65535")),
                new SecurityHeadersPolicy(false), Duration.ofSeconds(1));
        return new RavenrootServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, false, authenticator, security);
    }

    private static long consume(InputStream input) throws Exception {
        try (input) {
            return input.readAllBytes().length;
        }
    }
}
