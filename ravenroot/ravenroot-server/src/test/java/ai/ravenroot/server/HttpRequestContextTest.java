package ai.ravenroot.server;

import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressResponse;
import ai.ravenroot.api.ingress.IngressRouteOwner;
import ai.ravenroot.server.ingress.ManagedIngressRegistry;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRequestContextTest {
    @Test
    void enrichmentCopiesOneRequestIdentityAndSnapshotsNetworkAndPrincipalValues() {
        var roles = new HashSet<>(Set.of(Role.VIEWER));
        var scopes = new HashSet<>(Set.of("read"));
        var original = HttpRequestContext.create("request-1");
        var direct = original.withClient("127.0.0.1", false);
        var proxied = original.withClient("203.0.113.7", true);
        var firstPrincipal = new AuthenticatedPrincipal("alice", AuthenticatedPrincipal.Type.USER,
                "issuer", "tenant-a", roles, scopes);
        var authenticated = proxied.withPrincipal(firstPrincipal);
        roles.clear();
        scopes.clear();

        assertEquals("request-1", direct.requestId());
        assertEquals("request-1", proxied.requestId());
        assertEquals("request-1", authenticated.requestId());
        assertFalse(direct.forwarded());
        assertEquals("127.0.0.1", direct.clientAddress());
        assertTrue(proxied.forwarded());
        assertEquals("203.0.113.7", proxied.clientAddress());
        assertEquals(Set.of(Role.VIEWER), authenticated.requirePrincipal().roles());
        assertEquals(Set.of("read"), authenticated.requirePrincipal().scopes());
        assertFalse(original.principal().isPresent());

        var refreshedPrincipal = new AuthenticatedPrincipal("alice", AuthenticatedPrincipal.Type.USER,
                "issuer", "tenant-a", Set.of(Role.VIEWER), Set.of("read", "events"));
        var refreshed = authenticated.applicationContext(refreshedPrincipal);
        assertEquals("request-1", refreshed.requestId());
        assertEquals(PrincipalType.USER, refreshed.principalType());
        assertEquals(Set.of("read", "events"), refreshed.scopes());
        assertEquals("203.0.113.7", authenticated.clientAddress());
        assertTrue(authenticated.forwarded());
        assertNotSame(authenticated.applicationContext(), refreshed,
                "an SSE credential refresh gets a new immutable application context");
        assertEquals(Set.of("read"), authenticated.applicationContext().scopes(),
                "refreshing must not mutate the request's authenticated snapshot");
    }

    @Test
    void activeServerRoutesHaveNoExchangeRegistryOrAttributePath() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ai/ravenroot/server/RavenrootServer.java"));

        assertFalse(source.contains("AuthenticatedPrincipalAttribute"));
        assertFalse(source.contains(".setAttribute("));
        assertFalse(source.contains(".getAttribute("));
        int mint = source.indexOf("HttpRequestContext.create()");
        assertTrue(mint >= 0);
        assertEquals(mint, source.lastIndexOf("HttpRequestContext.create()"),
                "a request context must be minted once at the outer server boundary");
        assertTrue(source.contains(
                "registry.bindContextual(server, handler -> publicContext(protectedRequest(handler)))"));
    }

    @Test
    void contextualManagedIngressCannotFallBackToAConflictingLegacyPrincipal() throws Exception {
        var declaration = new IngressAuthorityDeclaration("context.test", "main", "/managed/context",
                Set.of("invoke"), 1, 1, 256, 256, Duration.ofSeconds(2));
        var registry = ManagedIngressRegistry.prepare(java.util.List.of(declaration), true);
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        var observed = new AtomicReference<ai.ravenroot.api.ingress.IngressRequest>();
        var explicit = HttpRequestContext.create("explicit-ingress-request")
                .withClient("127.0.0.1", false)
                .withPrincipal(new AuthenticatedPrincipal("alice", AuthenticatedPrincipal.Type.USER,
                        "issuer", "tenant-a", Set.of(), Set.of("invoke")));
        registry.bindContextual(server, handler -> exchange -> {
            AuthenticatedPrincipalAttribute.install(exchange,
                    new AuthenticatedPrincipal("mallory", AuthenticatedPrincipal.Type.USER,
                            "issuer", "tenant-b", Set.of(), Set.of("invoke")));
            try {
                handler.handle(exchange, explicit);
            } finally {
                AuthenticatedPrincipalAttribute.clear(exchange);
            }
        });
        assertThrows(IllegalStateException.class, () -> registry.bind(server, handler -> handler));
        var lease = registry.authorityFor(new IngressRouteOwner(
                        "context.test", "tenant-a", "deployment", "node", 1))
                .acquire("route", "/route", Set.of("POST"), request -> {
                    observed.set(request);
                    return CompletableFuture.completedFuture(new IngressResponse(204, Map.of(), new byte[0]));
                });
        server.start();
        try {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + server.getAddress().getPort()
                                    + "/managed/context/route"))
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());

            assertEquals(204, response.statusCode());
            assertEquals("tenant-a", observed.get().principal().tenantId());
            assertEquals("alice", observed.get().principal().subject());
        } finally {
            lease.release();
            registry.close();
            server.stop(0);
        }

        var legacyRegistry = ManagedIngressRegistry.prepare(java.util.List.of(declaration), true);
        var legacyServer = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        try {
            legacyRegistry.bind(legacyServer, handler -> handler);
            assertThrows(IllegalStateException.class, () -> legacyRegistry.bindContextual(
                    legacyServer, handler -> exchange -> handler.handle(exchange, explicit)));
        } finally {
            legacyRegistry.close();
            legacyServer.stop(0);
        }
    }
}
