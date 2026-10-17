package ai.ravenroot.server;

import ai.ravenroot.api.application.NodeTemplateReferenceUnavailableException;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.server.palette.JdbcNodePaletteStore;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.AuthenticationException;
import ai.ravenroot.server.support.ForwardingRavenrootApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Live HTTP coverage for personal palette identity, sanitization, concurrency, and refusal mapping. */
final class NodePaletteHttpTest {
    private static final String PALETTES = "/v1/node-palettes";
    private static final Set<String> PALETTE_SCOPES = Set.of(
            AuthorizationAction.PALETTE_READ.requiredScope(),
            AuthorizationAction.PALETTE_MANAGE.requiredScope());

    @TempDir Path directory;

    @Test
    void authenticatesUsersAndHidesResourcesAcrossUserTenantAndWorkloadBoundaries() throws Exception {
        try (var harness = new Harness(directory.resolve("isolation.db"), directory)) {
            harness.start();
            assertEquals(401, harness.request("GET", PALETTES, null, null, null).statusCode());
            assertEquals(403, harness.request("GET", PALETTES, "workload", null, null).statusCode());

            var created = harness.request("POST", PALETTES, "alice-a", null, "{\"name\":\"Private\"}");
            assertEquals(200, created.statusCode(), created.body());
            String id = stringField(created.body(), "id");

            var sameUser = harness.request("GET", PALETTES, "alice-a", null, null);
            assertEquals(200, sameUser.statusCode());
            assertTrue(sameUser.body().contains(id), sameUser.body());
            for (String token : List.of("bob-a", "alice-b")) {
                var listing = harness.request("GET", PALETTES, token, null, null);
                assertEquals(200, listing.statusCode(), listing.body());
                assertFalse(listing.body().contains(id), listing.body());
                var probe = harness.request("PATCH", PALETTES + "/" + id, token, "1",
                        "{\"name\":\"Probe\"}");
                assertEquals(404, probe.statusCode(), probe.body());
                assertTrue(probe.body().contains("UNKNOWN_RESOURCE"), probe.body());
            }
        }
    }

    @Test
    void sanitizesResponsesFencesVersionsAndSeparatesReferenceRefusalsFromMalformedPayloads() throws Exception {
        try (var harness = new Harness(directory.resolve("contract.db"), directory)) {
            harness.start();
            var palette = harness.request("POST", PALETTES, "alice-a", null, "{\"name\":\"Reusable\"}");
            String paletteId = stringField(palette.body(), "id");

            String planted = "raw-secret-zqxv";
            String body = "{\"paletteId\":\"" + paletteId + "\",\"name\":\"Probe\",\"node\":{"
                    + "\"name\":\"Probe\",\"kind\":\"BEHAVIOR\",\"behavior\":\"palette.probe\","
                    + "\"properties\":{\"safe\":\"kept\",\"secret\":\"" + planted + "\","
                    + "\"adapter\":\"server-only\",\"undeclared\":\"drop-me\","
                    + "\"authority\":\"available\"}}}";
            var saved = harness.request("POST", PALETTES + "/templates", "alice-a", null, body);
            assertEquals(201, saved.statusCode(), saved.body());
            assertTrue(saved.body().contains("\"safe\":\"kept\""), saved.body());
            assertTrue(saved.body().contains("\"authority\":\"available\""), saved.body());
            for (String forbidden : List.of(planted, "server-only", "drop-me", "undeclared")) {
                assertFalse(saved.body().contains(forbidden), saved.body());
            }

            var renamed = harness.request("PATCH", PALETTES + "/" + paletteId, "alice-a", "1",
                    "{\"name\":\"Renamed\"}");
            assertEquals(200, renamed.statusCode(), renamed.body());
            var stale = harness.request("PATCH", PALETTES + "/" + paletteId, "alice-a", "1",
                    "{\"name\":\"Stale\"}");
            assertEquals(409, stale.statusCode(), stale.body());
            assertTrue(stale.body().contains("CONFLICT"), stale.body());

            harness.referenceUnavailable.set(true);
            var unavailable = harness.request("POST", PALETTES + "/templates", "alice-a", null,
                    body.replace("available", "missing-private-canary"));
            assertEquals(409, unavailable.statusCode(), unavailable.body());
            assertTrue(unavailable.body().contains("NODE_TEMPLATE_REFERENCE_UNAVAILABLE"), unavailable.body());
            assertTrue(unavailable.body().contains("choose an available plugin, profile, policy, or destination reference"),
                    unavailable.body());
            assertFalse(unavailable.body().contains("missing-private-canary"), unavailable.body());
            assertFalse(unavailable.body().contains("resolver-private-diagnostic"), unavailable.body());

            var malformed = harness.request("POST", PALETTES + "/templates", "alice-a", null,
                    body.replace("\"safe\":\"kept\"", "\"safe\":7"));
            assertEquals(400, malformed.statusCode(), malformed.body());
            assertTrue(malformed.body().contains("INVALID_REQUEST"), malformed.body());
            assertFalse(malformed.body().contains("NODE_TEMPLATE_REFERENCE_UNAVAILABLE"), malformed.body());
        }
    }

    private static String stringField(String json, String field) {
        var matcher = java.util.regex.Pattern.compile("\\\"" + field + "\\\":\\\"([^\\\"]+)\\\"").matcher(json);
        if (!matcher.find()) throw new AssertionError("missing " + field + " in " + json);
        return matcher.group(1);
    }

    private static final class Harness implements AutoCloseable {
        private final PekkoExecutionEngine engine = new PekkoExecutionEngine("palette-http-" + UUID.randomUUID());
        private final RavenrootServer server;
        private final AtomicBoolean referenceUnavailable = new AtomicBoolean();

        private Harness(Path database, Path ui) {
            try (var ignored = new SqliteExecutionStore(database, Clock.systemUTC())) { }
            var delegate = new DefaultRavenrootApplication(engine, new ExecutionMonitor());
            var application = new PaletteApplication(delegate, referenceUnavailable);
            server = new RavenrootServer(application,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), ui, headers -> {
                String authorization = headers.getFirst("Authorization");
                if (authorization == null || !authorization.startsWith("Bearer ")) {
                    throw new AuthenticationException("missing test credential");
                }
                return principal(authorization.substring("Bearer ".length()));
            });
            server.installNodePalettes(JdbcNodePaletteStore.sqlite(database, Clock.systemUTC()));
        }

        private void start() throws Exception { server.start(); }

        private HttpResponse<String> request(String method, String path, String token, String version, String body)
                throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                    .header("Accept", "application/json");
            if (token != null) builder.header("Authorization", "Bearer " + token);
            if (version != null) builder.header("If-Match", version);
            if (body != null) builder.header("Content-Type", "application/json");
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        @Override public void close() {
            server.close();
            engine.close();
        }

        private static AuthenticatedPrincipal principal(String token) {
            if ("workload".equals(token)) {
                return new AuthenticatedPrincipal("worker", AuthenticatedPrincipal.Type.WORKLOAD,
                        "issuer", "tenant-a", Set.of(Role.DEVELOPER), PALETTE_SCOPES);
            }
            return switch (token) {
                case "alice-a" -> user("alice", "tenant-a");
                case "bob-a" -> user("bob", "tenant-a");
                case "alice-b" -> user("alice", "tenant-b");
                default -> user("unknown", "tenant-a");
            };
        }

        private static AuthenticatedPrincipal user(String subject, String tenant) {
            return new AuthenticatedPrincipal(subject, AuthenticatedPrincipal.Type.USER, "issuer", tenant,
                    Set.of(Role.DEVELOPER), PALETTE_SCOPES);
        }
    }

    private static final class PaletteApplication extends ForwardingRavenrootApplication {
        private final AtomicBoolean unavailable;

        private PaletteApplication(DefaultRavenrootApplication delegate, AtomicBoolean unavailable) {
            super(delegate);
            this.unavailable = unavailable;
        }

        @Override public List<NodeTypeDescriptor> nodeTypes() {
            return List.of(new NodeTypeDescriptor("palette.probe", "Palette probe", "Test", "", "actor", false,
                    List.of(
                            NodePropertyDescriptor.optional("safe", "Safe", NodePropertyType.STRING, "", ""),
                            NodePropertyDescriptor.optional("authority", "Authority", NodePropertyType.STRING, "", ""),
                            NodePropertyDescriptor.required("secret", "Secret", NodePropertyType.SECRET_REFERENCE, ""),
                            new NodePropertyDescriptor("adapter", "Adapter", NodePropertyType.STRING, true,
                                    "", "", List.of(), true)), Set.of()));
        }

        @Override public void validateNodeTemplateReferences(String tenantId, String behavior,
                                                             java.util.Map<String, String> properties) {
            if (unavailable.get() || properties.getOrDefault("authority", "").startsWith("missing")) {
                throw new NodeTemplateReferenceUnavailableException(
                        new IllegalArgumentException("resolver-private-diagnostic"));
            }
        }
    }
}
