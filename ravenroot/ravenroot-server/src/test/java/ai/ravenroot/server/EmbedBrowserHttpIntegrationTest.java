package ai.ravenroot.server;

import ai.ravenroot.api.embed.AuthorizedEmbedGraphProjection;
import ai.ravenroot.api.embed.AuthorizedEmbedSessionCreation;
import ai.ravenroot.api.embed.EmbedCapability;
import ai.ravenroot.api.embed.EmbedGraphProjection;
import ai.ravenroot.api.embed.EmbedProjectionBudget;
import ai.ravenroot.api.embed.EmbedProjectionEligibility;
import ai.ravenroot.api.embed.EmbedProjectionResolution;
import ai.ravenroot.api.embed.EmbedProvisionCommand;
import ai.ravenroot.api.embed.EmbedProvisionOutcome;
import ai.ravenroot.api.embed.EmbedRegistrationAggregate;
import ai.ravenroot.api.embed.EmbedRegistrationAuthority;
import ai.ravenroot.api.embed.EmbedRegistrationResolution;
import ai.ravenroot.api.embed.EmbedSnapshotLifecycle;
import ai.ravenroot.api.embed.InMemoryEmbedRegistrationAuthority;
import ai.ravenroot.api.embed.EmbedTheme;
import ai.ravenroot.api.embed.VerifiedEmbedGraphGrant;
import ai.ravenroot.api.embed.VerifiedEmbedSessionGrant;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.embed.EmbedBrowserConfiguration;
import ai.ravenroot.server.embed.EmbedBrowserHttpHandler;
import ai.ravenroot.server.embed.EmbedViewerOrigin;
import ai.ravenroot.server.embed.P256EmbedProofVerifier;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.AuthenticationException;
import ai.ravenroot.server.spec.RouteTable;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbedBrowserHttpIntegrationTest {
    private static final String VIEWER = "https://viewer.example";
    private static final String PARENT = "https://parent.example";

    @Test
    void conditionalRouteTableSurfaceMatchesTheFiveLiveHandlerPaths() {
        var expected = Set.of(EmbedBrowserHttpHandler.CREATE_PATH,
                EmbedBrowserHttpHandler.ACKNOWLEDGEMENT_PATH, EmbedBrowserHttpHandler.LAUNCH_PATH,
                EmbedBrowserHttpHandler.EXCHANGE_PATH, EmbedBrowserHttpHandler.PROJECTION_PATH);
        var declared = RouteTable.ALL.stream().filter(route -> route.path().startsWith("/v1/embed/"))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        ai.ravenroot.server.spec.RouteDescriptor::path,
                        ai.ravenroot.server.spec.RouteDescriptor::registersContext));
        assertEquals(expected, declared.keySet());
        assertTrue(declared.values().stream().noneMatch(Boolean::booleanValue),
                "default-off conditional routes must not enter the unconditional registration set");
    }

    @Test
    void liveServerCompletesTheFourPhaseFlowWithoutCorsCookiesOrCallerCoordinates() throws Exception {
        try (var engine = new PekkoExecutionEngine("embed-http");
             var server = server(engine, false)) {
            server.start();
            var client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            var suffixedCreate = send(client, request(base + EmbedBrowserHttpHandler.CREATE_PATH + "/alias")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"registrationId\":\"reg\"}")));
            assertEmbedDenied(suffixedCreate);

            var extraCoordinate = send(client, request(base + EmbedBrowserHttpHandler.CREATE_PATH)
                    .header("Authorization", "Bearer workload")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"registrationId\":\"reg\",\"graphId\":\"attacker\"}")));
            assertEquals(400, extraCoordinate.statusCode());

            var themeCoordinate = send(client, request(base + EmbedBrowserHttpHandler.CREATE_PATH)
                    .header("Authorization", "Bearer workload")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"registrationId\":\"reg\",\"theme\":\"light\"}")));
            assertEquals(400, themeCoordinate.statusCode());

            var created = send(client, request(base + EmbedBrowserHttpHandler.CREATE_PATH)
                    .header("Authorization", "Bearer workload")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"registrationId\":\"reg\"}")));
            assertEquals(201, created.statusCode(), created.body());
            assertPrivate(created);
            String launchUrl = json(created.body(), "launchUrl");
            URI launchUri = URI.create(launchUrl);
            String launchPath = launchUri.getRawPath() + "?" + launchUri.getRawQuery();

            var suffixedLaunch = send(client, request(base + launchUri.getRawPath() + "/alias?"
                    + launchUri.getRawQuery()).header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Dest", "iframe").GET());
            assertEmbedDenied(suffixedLaunch);

            var launched = send(client, request(base + launchPath)
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Dest", "iframe").GET());
            assertEquals(200, launched.statusCode(), launched.body());
            assertEquals(List.of(), launched.headers().allValues("X-Frame-Options"));
            assertTrue(launched.headers().firstValue("Content-Security-Policy").orElseThrow()
                    .contains("frame-ancestors " + PARENT));
            assertTrue(launched.headers().firstValue("Content-Security-Policy").orElseThrow()
                    .contains("sandbox allow-scripts allow-same-origin"));
            assertTrue(launched.headers().firstValue("Content-Security-Policy").orElseThrow()
                    .contains("style-src 'self'"));
            assertFalse(launched.body().contains("graphId"));
            assertFalse(launched.body().contains("bearer"));
            assertTrue(launched.body().contains("\"grantRevision\":\"1\""));
            assertTrue(launched.body().contains("\"theme\":null"));
            assertFalse(launched.body().contains("data-theme="));
            assertTrue(launched.body().contains("\"channelId\":\""));
            assertTrue(launched.body().contains("src=\"" + EmbedBrowserHttpHandler.BOOTSTRAP_SCRIPT_PATH
                    + "\""));
            assertTrue(launched.body().contains("href=\"/embed-viewer.css\""));
            assertTrue(launched.body().contains("id=\"ravenroot-embed-viewer\""));
            assertTrue(launched.body().contains("data-viewer-mode"));
            assertTrue(launched.body().contains("data-viewer-minimap"));
            assertTrue(launched.body().contains("Start of embedded graph"));
            assertTrue(launched.body().contains("End of embedded graph"));
            String exchangeId = json(launched.body(), "exchangeId");
            String exchangeNonce = json(launched.body(), "challenge");
            String acknowledgementId = json(launched.body(), "acknowledgementId");
            String channelId = json(launched.body(), "channelId");
            String ackCorrelationId = "parent-ack-correlation";

            KeyPair pair = keyPair();
            ECPublicKey publicKey = (ECPublicKey) pair.getPublic();
            Instant exchangeTime = Instant.now();
            String exchangeBody = "{\"exchangeId\":\"" + exchangeId + "\",\"channelId\":\""
                    + channelId + "\",\"ackCorrelationId\":\"" + ackCorrelationId + "\",\"keyX\":\""
                    + coordinate(publicKey.getW().getAffineX()) + "\",\"keyY\":\""
                    + coordinate(publicKey.getW().getAffineY()) + "\",\"nonce\":\"" + exchangeNonce
                    + "\",\"jti\":\"exchange-jti\",\"issuedAt\":\"" + exchangeTime
                    + "\",\"signature\":\"" + exchangeSignature(pair, exchangeId, 1, exchangeNonce,
                    channelId, ackCorrelationId, "exchange-jti", exchangeTime) + "\"}";

            var beforeAck = send(client, viewerPost(base + EmbedBrowserHttpHandler.EXCHANGE_PATH,
                    VIEWER, exchangeBody));
            assertEmbedDenied(beforeAck);

            String ackBody = "{\"registrationId\":\"reg\",\"acknowledgementId\":\""
                    + acknowledgementId + "\",\"channelId\":\"" + channelId
                    + "\",\"correlationId\":\"" + ackCorrelationId + "\"}";
            var missingAckAuthentication = send(client, request(
                    base + EmbedBrowserHttpHandler.ACKNOWLEDGEMENT_PATH)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(ackBody)));
            assertPreAuthAckDenied(missingAckAuthentication);
            var invalidAckAuthentication = send(client, s2sPost(
                    base + EmbedBrowserHttpHandler.ACKNOWLEDGEMENT_PATH, "invalid", ackBody));
            assertPreAuthAckDenied(invalidAckAuthentication);
            for (String credential : List.of("wrong-subject", "wrong-issuer", "wrong-tenant", "wrong-scope")) {
                var deniedAck = send(client, s2sPost(base + EmbedBrowserHttpHandler.ACKNOWLEDGEMENT_PATH,
                        credential, ackBody));
                assertEquals(403, deniedAck.statusCode(), credential + ": " + deniedAck.body());
            }
            var suffixedAck = send(client, s2sPost(
                    base + EmbedBrowserHttpHandler.ACKNOWLEDGEMENT_PATH + "/alias", "workload", ackBody));
            assertEmbedDenied(suffixedAck);
            var acknowledged = send(client, s2sPost(base + EmbedBrowserHttpHandler.ACKNOWLEDGEMENT_PATH,
                    "workload", ackBody));
            assertEquals(200, acknowledged.statusCode(), acknowledged.body());
            assertEquals("{\"acknowledged\":true}", acknowledged.body());
            var ackReplay = send(client, s2sPost(base + EmbedBrowserHttpHandler.ACKNOWLEDGEMENT_PATH,
                    "workload", ackBody));
            assertEmbedDenied(ackReplay);

            var suffixedExchange = send(client, viewerPost(
                    base + EmbedBrowserHttpHandler.EXCHANGE_PATH + "/alias", VIEWER, exchangeBody));
            assertEmbedDenied(suffixedExchange);

            var foreign = send(client, viewerPost(base + EmbedBrowserHttpHandler.EXCHANGE_PATH,
                    "https://foreign.example", exchangeBody));
            assertEquals(403, foreign.statusCode());

            var missingOrigin = send(client, request(base + EmbedBrowserHttpHandler.EXCHANGE_PATH)
                    .header("Sec-Fetch-Site", "same-origin").header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Dest", "empty").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(exchangeBody)));
            assertEquals(403, missingOrigin.statusCode());

            var nullOrigin = send(client, viewerPost(base + EmbedBrowserHttpHandler.EXCHANGE_PATH,
                    "null", exchangeBody));
            assertEquals(403, nullOrigin.statusCode());

            var preflight = send(client, request(base + EmbedBrowserHttpHandler.EXCHANGE_PATH)
                    .header("Origin", VIEWER).header("Access-Control-Request-Method", "POST")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
            assertEquals(405, preflight.statusCode());
            assertEquals(List.of(), preflight.headers().allValues("Access-Control-Allow-Origin"));

            var exchanged = send(client, viewerPost(base + EmbedBrowserHttpHandler.EXCHANGE_PATH,
                    VIEWER, exchangeBody));
            assertEquals(200, exchanged.statusCode(), exchanged.body());
            assertPrivate(exchanged);
            String bearer = json(exchanged.body(), "bearer");
            String projectionNonce = json(exchanged.body(), "challenge");
            Instant projectionTime = Instant.now();
            String projectionBody = "{\"nonce\":\"" + projectionNonce
                    + "\",\"jti\":\"projection-jti\",\"issuedAt\":\"" + projectionTime
                    + "\",\"signature\":\"" + signature(pair, bearer, 1, projectionNonce,
                    "projection-jti", EmbedBrowserHttpHandler.PROJECTION_PATH, projectionTime) + "\"}";

            var suffixedProjection = send(client, viewerPost(
                    base + EmbedBrowserHttpHandler.PROJECTION_PATH + "/alias", VIEWER, projectionBody)
                    .header("Authorization", "Bearer " + bearer));
            assertEmbedDenied(suffixedProjection);

            var projected = send(client, viewerPost(base + EmbedBrowserHttpHandler.PROJECTION_PATH,
                    VIEWER, projectionBody).header("Authorization", "Bearer " + bearer));
            assertEquals(200, projected.statusCode(), projected.body());
            assertTrue(projected.body().contains("\"graphId\":\"graph\""));
            assertFalse(projected.body().contains("tenant"));
            assertPrivate(projected);

            var replay = send(client, viewerPost(base + EmbedBrowserHttpHandler.PROJECTION_PATH,
                    VIEWER, projectionBody).header("Authorization", "Bearer " + bearer));
            assertEquals(403, replay.statusCode());

            var general = send(client, request(base + "/health").GET());
            assertEquals("DENY", general.headers().firstValue("X-Frame-Options").orElseThrow());
        }
    }

    @Test
    void launchCarriesOnlyTheValidatedSessionThemeAndSetsItBeforeStylesLoad() throws Exception {
        var registrations = new InMemoryEmbedRegistrationAuthority();
        provision(registrations, command("light", PARENT, Optional.of(EmbedTheme.LIGHT)));
        try (var engine = new PekkoExecutionEngine("embed-http-theme");
             var server = server(engine, false, registrations)) {
            server.start();
            var client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            var created = send(client, request(base + EmbedBrowserHttpHandler.CREATE_PATH)
                    .header("Authorization", "Bearer workload")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"registrationId\":\"light\"}")));
            assertEquals(201, created.statusCode(), created.body());
            URI launchUri = URI.create(json(created.body(), "launchUrl"));
            var launched = send(client, request(base + launchUri.getRawPath() + "?" + launchUri.getRawQuery())
                    .header("Sec-Fetch-Mode", "navigate").header("Sec-Fetch-Dest", "iframe").GET());
            assertEquals(200, launched.statusCode(), launched.body());
            assertTrue(launched.body().contains("<html lang=\"en\" data-theme=\"light\"><head>"));
            assertTrue(launched.body().contains("\"theme\":\"light\""));
            assertTrue(launched.body().indexOf("data-theme=\"light\"")
                    < launched.body().indexOf("href=\"/embed-viewer.css\""));
        }
    }

    @Test
    void auditFailurePreventsTicketDisclosure() throws Exception {
        try (var engine = new PekkoExecutionEngine("embed-http-audit");
             var server = server(engine, true)) {
            server.start();
            var response = send(HttpClient.newHttpClient(), request("http://127.0.0.1:" + server.port()
                    + EmbedBrowserHttpHandler.CREATE_PATH)
                    .header("Authorization", "Bearer workload")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"registrationId\":\"reg\"}")));
            assertEquals(503, response.statusCode());
            assertFalse(response.body().contains("ticket"));
            assertFalse(response.body().contains("launchUrl"));
        }
    }

    @Test
    void customAuthorityCannotInjectANonCanonicalParentOriginIntoLaunchOrCsp() throws Exception {
        var graphGrant = new VerifiedEmbedGraphGrant("tenant", "resource", "deployment", 1,
                "graph", "version", "digest", "policy");
        var invalidOrigins = List.of(
                "*",
                "https://parent.example;frame-ancestors *",
                "https://parent.example/path",
                "https://user@parent.example",
                "HTTPS://parent.example",
                "https://Parent.example",
                "https://parent.example:443",
                "https://parent.example\r\nContent-Security-Policy: frame-ancestors *");
        // Provisioned through the real authority: an aggregate may legally carry any non-blank parent
        // origin, because canonicalising an origin is the HTTP boundary's job and this test is about
        // that boundary refusing one rather than about the store having pre-filtered it.
        var malicious = new InMemoryEmbedRegistrationAuthority();
        var registrations = new java.util.LinkedHashMap<String, EmbedRegistrationAggregate>();
        for (int index = 0; index < invalidOrigins.size(); index++) {
            String registrationId = "invalid-" + index;
            registrations.put(registrationId, provision(malicious,
                    command(registrationId, invalidOrigins.get(index), Optional.empty())));
        }

        try (var engine = new PekkoExecutionEngine("embed-http-origin-injection");
             var server = server(engine, false, malicious)) {
            server.start();
            var client = HttpClient.newHttpClient();
            String endpoint = "http://127.0.0.1:" + server.port() + EmbedBrowserHttpHandler.CREATE_PATH;
            for (var registration : registrations.entrySet()) {
                String registrationId = registration.getKey();
                var response = send(client, request(endpoint)
                        .header("Authorization", "Bearer workload")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"registrationId\":\"" + registrationId + "\"}")));
                assertEquals(403, response.statusCode(), registrationId + ": " + response.body());
                assertFalse(response.body().contains("launchUrl"));
                String csp = response.headers().firstValue("Content-Security-Policy").orElseThrow();
                assertTrue(csp.contains("frame-ancestors 'none'"));
                assertFalse(csp.contains(registration.getValue().sessionGrant().parentOrigin()));
            }
        }
    }

    @Test
    void customAuthorityCannotMakeParentAndViewerSameOriginOrCauseSideEffects() throws Exception {
        var backing = new InMemoryEmbedRegistrationAuthority();
        provision(backing, command("same-origin", VIEWER, Optional.empty()));
        var resolutionCalls = new AtomicInteger();
        var projectionCalls = new AtomicInteger();
        var malicious = new CountingAuthority(backing, resolutionCalls, projectionCalls);
        var auditAllows = new AtomicInteger();

        try (var engine = new PekkoExecutionEngine("embed-http-same-origin");
             var server = server(engine, false, malicious, auditAllows)) {
            server.start();
            var client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            var creation = send(client, request(base + EmbedBrowserHttpHandler.CREATE_PATH)
                    .header("Authorization", "Bearer workload")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"registrationId\":\"same-origin\"}")));
            assertEmbedDenied(creation);
            assertFalse(creation.body().contains("launchUrl"));
            assertFalse(creation.body().contains("ticket"));

            var launch = send(client, request(base + EmbedBrowserHttpHandler.LAUNCH_PATH + "?ticket=unused")
                    .header("Sec-Fetch-Mode", "navigate").header("Sec-Fetch-Dest", "iframe").GET());
            assertEmbedDenied(launch);
            assertEquals(1, resolutionCalls.get());
            assertEquals(0, auditAllows.get());
            assertEquals(0, projectionCalls.get());
        }
    }

    /** The graph coordinates every registration in this suite pins down. */
    private static VerifiedEmbedGraphGrant graphGrant() {
        return new VerifiedEmbedGraphGrant("tenant", "resource", "deployment", 1,
                "graph", "version", "digest", "policy");
    }

    private static EmbedProvisionCommand command(String registrationId, String parentOrigin,
                                                  Optional<EmbedTheme> theme) {
        var projection = new EmbedGraphProjection(EmbedGraphProjection.CURRENT_CONTRACT_VERSION,
                "graph", "version", "digest", List.of(), List.of());
        return new EmbedProvisionCommand(registrationId, 0, "issuer", "workload", "tenant", parentOrigin,
                Set.of(EmbedCapability.GRAPH_READ), theme, graphGrant(), EmbedSnapshotLifecycle.PUBLISHED,
                EmbedProjectionEligibility.allowed("policy"), projection);
    }

    private static EmbedRegistrationAggregate provision(EmbedRegistrationAuthority authority,
                                                         EmbedProvisionCommand command) {
        var outcome = authority.provision(command);
        if (outcome instanceof EmbedProvisionOutcome.Provisioned provisioned) return provisioned.aggregate();
        throw new AssertionError("the fixture registration was refused: " + outcome);
    }

    /**
     * Counts what the boundary asked the authority for, without becoming a second read: the projection
     * override delegates straight back to the port's default, so the payload still comes from the
     * captured aggregate. It exists to prove that a refused creation performs no further work.
     */
    private record CountingAuthority(EmbedRegistrationAuthority delegate, AtomicInteger resolutions,
                                     AtomicInteger projections) implements EmbedRegistrationAuthority {
        @Override public EmbedProvisionOutcome provision(EmbedProvisionCommand command) {
            return delegate.provision(command);
        }

        @Override public ai.ravenroot.api.embed.EmbedRevokeOutcome revoke(
                ai.ravenroot.api.embed.EmbedRevokeCommand command) {
            return delegate.revoke(command);
        }

        @Override public EmbedRegistrationResolution resolveCurrent(
                ai.ravenroot.api.security.RequestContext workload, String registrationId) {
            resolutions.incrementAndGet();
            return delegate.resolveCurrent(workload, registrationId);
        }

        @Override public boolean isCurrent(EmbedRegistrationAggregate captured) {
            return delegate.isCurrent(captured);
        }

        @Override public EmbedProjectionResolution resolveProjection(EmbedRegistrationAggregate captured,
                                                                     EmbedProjectionBudget budget) {
            projections.incrementAndGet();
            return EmbedRegistrationAuthority.super.resolveProjection(captured, budget);
        }
    }

    private static RavenrootServer server(PekkoExecutionEngine engine, boolean failingAudit) {
        var registrations = new InMemoryEmbedRegistrationAuthority();
        provision(registrations, command("reg", PARENT, Optional.empty()));
        return server(engine, failingAudit, registrations);
    }

    private static RavenrootServer server(PekkoExecutionEngine engine, boolean failingAudit,
                                           EmbedRegistrationAuthority registrations) {
        return server(engine, failingAudit, registrations, new AtomicInteger());
    }

    private static RavenrootServer server(PekkoExecutionEngine engine, boolean failingAudit,
                                           EmbedRegistrationAuthority registrations,
                                           AtomicInteger auditAllows) {
        var authorization = new DefaultAuthorizationService(event -> { });
        var projections = new AuthorizedEmbedGraphProjection(authorization, registrations);
        var config = new EmbedBrowserConfiguration(true, new EmbedViewerOrigin(VIEWER),
                new AuthorizedEmbedSessionCreation(authorization, registrations), registrations, projections,
                event -> {
                    if (failingAudit) throw new IllegalStateException("audit offline");
                    auditAllows.incrementAndGet();
                },
                Clock.systemUTC(), Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(2),
                Duration.ofMinutes(1), 16, 16, 32, 1, true);
        var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor());
        return new RavenrootServer(application,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                headers -> {
                    return switch (String.valueOf(headers.getFirst("Authorization"))) {
                        case "Bearer workload" -> new AuthenticatedPrincipal("workload",
                                AuthenticatedPrincipal.Type.WORKLOAD, "issuer", "tenant",
                                Set.of(Role.VIEWER), Set.of("ravenroot.embed.session.create"));
                        case "Bearer wrong-subject" -> new AuthenticatedPrincipal("other",
                                AuthenticatedPrincipal.Type.WORKLOAD, "issuer", "tenant",
                                Set.of(Role.VIEWER), Set.of("ravenroot.embed.session.create"));
                        case "Bearer wrong-issuer" -> new AuthenticatedPrincipal("workload",
                                AuthenticatedPrincipal.Type.WORKLOAD, "other", "tenant",
                                Set.of(Role.VIEWER), Set.of("ravenroot.embed.session.create"));
                        case "Bearer wrong-tenant" -> new AuthenticatedPrincipal("workload",
                                AuthenticatedPrincipal.Type.WORKLOAD, "issuer", "other",
                                Set.of(Role.VIEWER), Set.of("ravenroot.embed.session.create"));
                        case "Bearer wrong-scope" -> new AuthenticatedPrincipal("workload",
                                AuthenticatedPrincipal.Type.WORKLOAD, "issuer", "tenant",
                                Set.of(Role.VIEWER), Set.of());
                        default -> throw new AuthenticationException("missing");
                    };
                }, authorization, config);
    }

    private static HttpRequest.Builder request(String uri) { return HttpRequest.newBuilder(URI.create(uri)); }

    private static HttpRequest.Builder viewerPost(String uri, String origin, String body) {
        return request(uri).header("Origin", origin).header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors").header("Sec-Fetch-Dest", "empty")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static HttpRequest.Builder s2sPost(String uri, String credential, String body) {
        return request(uri).header("Authorization", "Bearer " + credential)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception {
        return send(client, request.build());
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void assertPrivate(HttpResponse<?> response) {
        assertEquals("private, no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(List.of(), response.headers().allValues("Access-Control-Allow-Origin"));
        assertEquals(List.of(), response.headers().allValues("Set-Cookie"));
    }

    private static void assertEmbedDenied(HttpResponse<String> response) {
        assertEquals(403, response.statusCode(), response.body());
        assertEquals("{\"error\":\"EMBED_SESSION_UNAVAILABLE\"}", response.body());
        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Security-Policy").orElseThrow()
                .contains("frame-ancestors 'none'"));
        assertPrivate(response);
    }

    private static void assertPreAuthAckDenied(HttpResponse<String> response) {
        assertEquals(401, response.statusCode(), response.body());
        assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertEquals("private, no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("no-cache", response.headers().firstValue("Pragma").orElseThrow());
        assertTrue(response.body().contains("\"code\":\"AUTHENTICATION_REQUIRED\""), response.body());
        assertFalse(response.body().contains("missing"), response.body());
        assertFalse(response.body().contains("acknowledgementId"), response.body());
    }

    private static KeyPair keyPair() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String signature(KeyPair pair, String bearer, long revision, String nonce,
                                    String jti, String path, Instant time) throws Exception {
        var signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(pair.getPrivate());
        signature.update(P256EmbedProofVerifier.payload(bearer, revision, nonce, jti, "POST", path, time));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    private static String exchangeSignature(KeyPair pair, String exchangeId, long revision, String nonce,
                                            String channelId, String ackCorrelationId, String jti,
                                            Instant time) throws Exception {
        var signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(pair.getPrivate());
        signature.update(P256EmbedProofVerifier.exchangePayload(exchangeId, revision, nonce, channelId,
                ackCorrelationId, jti, "POST", EmbedBrowserHttpHandler.EXCHANGE_PATH, time));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    private static String coordinate(BigInteger coordinate) {
        byte[] signed = coordinate.toByteArray();
        byte[] fixed = new byte[32];
        int source = signed.length == 33 && signed[0] == 0 ? 1 : 0;
        System.arraycopy(signed, source, fixed, fixed.length - (signed.length - source), signed.length - source);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fixed);
    }

    private static String json(String body, String field) {
        String marker = "\"" + field + "\":\"";
        int start = body.indexOf(marker);
        if (start < 0) throw new AssertionError("missing " + field + " in " + body);
        int value = start + marker.length();
        return body.substring(value, body.indexOf('"', value));
    }
}
