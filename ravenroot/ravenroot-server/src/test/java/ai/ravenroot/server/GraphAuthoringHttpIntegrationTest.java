package ai.ravenroot.server;

import ai.ravenroot.api.authoring.GraphAuthoringRepository;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.server.authoring.GraphAuthoringConfiguration;
import ai.ravenroot.server.authoring.GraphAuthoringHttpApi;
import ai.ravenroot.server.audit.StructuredAuthorizationLogger;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

class GraphAuthoringHttpIntegrationTest {
    @Test void liveHttpUsesAuthenticatedTenantAndEnforcesReadWriteScopes() throws Exception {
        var repository = new TenantRecordingRepository();
        var authorization = new DefaultAuthorizationService(new StructuredAuthorizationLogger(
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream())));
        var api = new GraphAuthoringHttpApi(GraphAuthoringConfiguration.local(), repository,
                authorization, ignored -> { });
        assertTrue(api.capabilitiesJson().contains("ARTIFACT_IMPORT"));
        assertTrue(api.capabilitiesJson().contains("ARTIFACT_DEPLOY"));
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/graph-authoring", exchange -> {
            String token = exchange.getRequestHeaders().getFirst("Authorization");
            String[] claims = token.replace("Bearer ", "").split(":", -1);
            Set<String> scopes = claims.length > 2 && claims[2].equals("none") ? Set.of()
                    : Set.of(AuthorizationAction.GRAPH_READ.requiredScope(),
                    AuthorizationAction.GRAPH_WRITE.requiredScope());
            var principal = new AuthenticatedPrincipal(claims[1], AuthenticatedPrincipal.Type.USER,
                    "https://issuer.example", claims[0], Set.of(Role.PLATFORM_ADMIN), scopes,
                    Instant.now().plus(Duration.ofMinutes(5)));
            api.handle(exchange, HttpRequestContext.create("authoring-test").withPrincipal(principal));
        });
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/graph-authoring");
            var tenantA = client.send(HttpRequest.newBuilder(base)
                    .header("Authorization", "Bearer tenant-a:alice:all").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var tenantB = client.send(HttpRequest.newBuilder(base)
                    .header("Authorization", "Bearer tenant-b:bob:all").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, tenantA.statusCode(), tenantA.body());
            assertEquals(200, tenantB.statusCode(), tenantB.body());
            assertTrue(tenantA.body().contains("tenant-a.graphml"));
            assertFalse(tenantA.body().contains("tenant-b.graphml"));
            assertTrue(tenantB.body().contains("tenant-b.graphml"));

            var denied = client.send(HttpRequest.newBuilder(base)
                    .header("Authorization", "Bearer tenant-a:alice:none").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, denied.statusCode(), denied.body());

            var saved = client.send(HttpRequest.newBuilder(base.resolve("/v1/graph-authoring/orders.graphml"))
                    .header("Authorization", "Bearer tenant-b:bob:all")
                    .header("Idempotency-Key", "request-key-000000000001")
                    .header("X-Ravenroot-Expected-Draft", "absent")
                    .header("X-Ravenroot-Expected-Release", "absent")
                    .header("X-Ravenroot-Expected-Publication", "absent")
                    .PUT(HttpRequest.BodyPublishers.ofString("<graphml/>")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, saved.statusCode(), saved.body());
            assertEquals("tenant-b", repository.lastActor.tenantId());

            var traversal = client.send(HttpRequest.newBuilder(base.resolve("/v1/graph-authoring/%2e%2e%2fsecret"))
                    .header("Authorization", "Bearer tenant-a:alice:all").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(Set.of(400, 404).contains(traversal.statusCode()), traversal.body());
        } finally {
            server.stop(0);
            api.close();
        }
    }

    private static final class TenantRecordingRepository implements GraphAuthoringRepository {
        volatile Actor lastActor;
        @Override public Set<Capability> capabilities() { return Set.of(Capability.LIST, Capability.OPEN, Capability.SAVE); }
        @Override public CompletionStage<Page<DocumentSummary>> list(Actor actor, String cursor) {
            lastActor = actor;
            return CompletableFuture.completedFuture(new Page<>(List.of(summary(actor.tenantId() + ".graphml")), ""));
        }
        @Override public CompletionStage<Document> open(Actor actor, String id, boolean released) {
            lastActor = actor; return CompletableFuture.completedFuture(new Document(summary(id), "<graphml/>".getBytes()));
        }
        @Override public CompletionStage<Document> save(Actor actor, SaveRequest request) {
            lastActor = actor; return CompletableFuture.completedFuture(new Document(summary(request.documentId()), request.graphMl()));
        }
        @Override public CompletionStage<Page<HistoryEntry>> history(Actor actor, String id, String cursor) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Difference> diff(Actor actor, String id, String from, String to) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Document> restore(Actor actor, MutationRequest request) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Void> deleteDraft(Actor actor, MutationRequest request) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Document> discardDraft(Actor actor, MutationRequest request) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<ReleaseProposal> proposeRelease(Actor actor, MutationRequest request) { throw new UnsupportedOperationException(); }
        private static DocumentSummary summary(String id) {
            return new DocumentSummary(id, id, "orders", 1,
                    new Revision("absent", "absent", "absent"), false, false, false);
        }
    }
}
