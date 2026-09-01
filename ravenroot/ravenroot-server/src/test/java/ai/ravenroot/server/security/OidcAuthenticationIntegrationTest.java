package ai.ravenroot.server.security;

import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.RavenrootServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OidcAuthenticationIntegrationTest {
    private static final String GRAPH = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <graph id="auth" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="to-end" source="start" target="end"/>
              </graph>
            </graphml>
            """;

    @Test
    void protectsApiAndSseAndAcceptsUserAndWorkloadIdentities() throws Exception {
        try (var provider = new TestOidcProvider();
             var engine = new PekkoExecutionEngine("oidc-integration-test");
             var server = server(engine, provider)) {
            server.start();
            var client = HttpClient.newHttpClient();
            String base = "http://localhost:" + server.port();

            var missing = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/status")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, missing.statusCode());
            assertEquals("Bearer", missing.headers().firstValue("WWW-Authenticate").orElseThrow());

            var missingSse = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/events")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, missingSse.statusCode());

            var malformed = client.send(authorized(base + "/v1/status", "malformed.token.value").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, malformed.statusCode());

            try (var otherProvider = new TestOidcProvider()) {
                String wrongSignature = otherProvider.token("mallory", "user", Instant.now().plusSeconds(60),
                        TestOidcProvider.AUDIENCE);
                var invalidSignature = client.send(authorized(base + "/v1/status", wrongSignature).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(401, invalidSignature.statusCode());
            }

            String user = provider.token("alice", "user", Instant.now().plusSeconds(60),
                    TestOidcProvider.AUDIENCE);
            var status = client.send(authorized(base + "/v1/status", user).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, status.statusCode());
            assertTrue(status.body().contains("apache-pekko"));

            String workload = TestOidcProvider.token(provider.key(), "worker-1", "workload",
                    Instant.now().plusSeconds(60), TestOidcProvider.AUDIENCE,
                    TestOidcProvider.ISSUER.toString(), Instant.now().minusSeconds(1), Instant.now(),
                    "ravenroot.execute");
            var execution = client.send(authorized(base + "/v1/executions", workload)
                            .header("Content-Type", "application/graphml+xml")
                            .POST(HttpRequest.BodyPublishers.ofString(GRAPH)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, execution.statusCode());
        }
    }

    @Test
    void mapsValidIdentityWithInsufficientRouteScopeToForbidden() throws Exception {
        try (var provider = new TestOidcProvider();
             var engine = new PekkoExecutionEngine("oidc-scope-test");
             var server = server(engine, provider)) {
            server.start();
            String token = provider.token("alice", "user", Instant.now().plusSeconds(60),
                    TestOidcProvider.AUDIENCE);
            var response = HttpClient.newHttpClient().send(
                    authorized("http://localhost:" + server.port() + "/v1/executions", token)
                            .POST(HttpRequest.BodyPublishers.ofString(GRAPH)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, response.statusCode());
        }
    }

    private static RavenrootServer server(PekkoExecutionEngine engine, TestOidcProvider provider) {
        var authenticator = new JwtRequestAuthenticator(TestOidcProvider.ISSUER, TestOidcProvider.AUDIENCE,
                "token_kind", Duration.ofSeconds(30),
                new JwkSetProvider(provider.jwksUri(), Duration.ofSeconds(30)));
        return new RavenrootServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, authenticator);
    }

    private static HttpRequest.Builder authorized(String uri, String token) {
        return HttpRequest.newBuilder(URI.create(uri)).header("Authorization", "Bearer " + token);
    }
}
