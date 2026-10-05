package ai.ravenroot.server;

import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.extensions.amqp091.AmqpConsumeNodeBehavior;
import ai.ravenroot.extensions.amqp091.AmqpPaletteTestPackage;
import ai.ravenroot.extensions.amqp091.AmqpPublishNodeBehavior;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.server.palette.JdbcNodePaletteStore;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.AuthenticationException;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Live HTTP classification coverage with the real AMQP behavior implementations. */
final class AmqpNodePaletteHttpClassificationTest {
    private static final String TENANT = "tenant-a";
    private static final String PROFILE = "orders";
    private static final String PALETTES = "/v1/node-palettes";
    private static final Set<String> PALETTE_SCOPES = Set.of(
            AuthorizationAction.PALETTE_READ.requiredScope(),
            AuthorizationAction.PALETTE_MANAGE.requiredScope());

    @TempDir Path directory;

    @Test
    void realPublishAndConsumeBehaviorsSeparateMalformedSettingsFromUnavailableAuthority() throws Exception {
        try (var harness = new Harness(directory.resolve("amqp-palettes.db"), directory)) {
            harness.start();
            var palette = harness.request("POST", PALETTES, "{\"name\":\"AMQP\"}");
            assertEquals(200, palette.statusCode(), palette.body());
            String paletteId = stringField(palette.body(), "id");

            var malformedPublish = harness.request("POST", PALETTES + "/templates",
                    template(paletteId, "Malformed publish", AmqpPublishNodeBehavior.BEHAVIOR,
                            Map.of("brokerProfile", PROFILE, "mandatory", "false")));
            assertInvalidRequest(malformedPublish);

            var missingPublishProfile = harness.request("POST", PALETTES + "/templates",
                    template(paletteId, "Missing publish profile", AmqpPublishNodeBehavior.BEHAVIOR,
                            Map.of("brokerProfile", "missing-private-canary")));
            assertUnavailableReference(missingPublishProfile, "missing-private-canary");

            var forbiddenPublishDestination = harness.request("POST", PALETTES + "/templates",
                    template(paletteId, "Forbidden publish destination", AmqpPublishNodeBehavior.BEHAVIOR,
                            Map.of("brokerProfile", PROFILE, "exchange", "private-exchange-canary")));
            assertUnavailableReference(forbiddenPublishDestination, "private-exchange-canary");

            var malformedConsume = harness.request("POST", PALETTES + "/templates",
                    template(paletteId, "Malformed consume", AmqpConsumeNodeBehavior.BEHAVIOR,
                            Map.of("brokerProfile", PROFILE, "prefetch", "not-a-number")));
            assertInvalidRequest(malformedConsume);

            var missingConsumePolicy = harness.request("POST", PALETTES + "/templates",
                    template(paletteId, "Missing consume policy", AmqpConsumeNodeBehavior.BEHAVIOR,
                            Map.of("brokerProfile", "missing-policy-canary")));
            assertUnavailableReference(missingConsumePolicy, "missing-policy-canary");

            var forbiddenQueue = harness.request("POST", PALETTES + "/templates",
                    template(paletteId, "Forbidden queue", AmqpConsumeNodeBehavior.BEHAVIOR,
                            Map.of("brokerProfile", PROFILE, "queue", "private-queue-canary")));
            assertUnavailableReference(forbiddenQueue, "private-queue-canary");

            var listing = harness.request("GET", PALETTES, null);
            assertEquals(200, listing.statusCode(), listing.body());
            for (String refusedName : List.of("Malformed publish", "Missing publish profile",
                    "Forbidden publish destination", "Malformed consume", "Missing consume policy",
                    "Forbidden queue")) {
                assertFalse(listing.body().contains(refusedName), listing.body());
            }
        }
    }

    private static void assertInvalidRequest(HttpResponse<String> response) {
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("INVALID_REQUEST"), response.body());
        assertFalse(response.body().contains("NODE_TEMPLATE_REFERENCE_UNAVAILABLE"), response.body());
    }

    private static void assertUnavailableReference(HttpResponse<String> response, String privateCanary) {
        assertEquals(409, response.statusCode(), response.body());
        assertTrue(response.body().contains("NODE_TEMPLATE_REFERENCE_UNAVAILABLE"), response.body());
        assertTrue(response.body().contains("choose an available plugin, profile, policy, or destination reference"),
                response.body());
        assertFalse(response.body().contains(privateCanary), response.body());
    }

    private static String template(String paletteId, String name, String behavior,
                                   Map<String, String> properties) {
        String values = properties.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":\"" + entry.getValue() + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"paletteId\":\"" + paletteId + "\",\"name\":\"" + name + "\",\"node\":{"
                + "\"name\":\"" + name + "\",\"kind\":\"BEHAVIOR\",\"behavior\":\"" + behavior
                + "\",\"properties\":{" + values + "}}}";
    }

    private static String stringField(String json, String field) {
        var matcher = java.util.regex.Pattern.compile("\\\"" + field + "\\\":\\\"([^\\\"]+)\\\"")
                .matcher(json);
        if (!matcher.find()) throw new AssertionError("missing " + field + " in " + json);
        return matcher.group(1);
    }

    private static final class Harness implements AutoCloseable {
        private final PekkoExecutionEngine engine = new PekkoExecutionEngine("amqp-palette-http-" + UUID.randomUUID());
        private final RavenrootServer server;

        private Harness(Path database, Path ui) {
            try (var ignored = new SqliteExecutionStore(database, Clock.systemUTC())) { }
            BehaviorRegistry behaviors = NodePackages.register(new BehaviorRegistry(),
                    AmqpPaletteTestPackage.create(TENANT, PROFILE));
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), behaviors);
            server = new RavenrootServer(application,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), ui, headers -> {
                String authorization = headers.getFirst("Authorization");
                if (!"Bearer author".equals(authorization)) {
                    throw new AuthenticationException("missing test credential");
                }
                return new AuthenticatedPrincipal("alice", AuthenticatedPrincipal.Type.USER, "issuer", TENANT,
                        Set.of(Role.DEVELOPER), PALETTE_SCOPES);
            });
            server.installNodePalettes(JdbcNodePaletteStore.sqlite(database, Clock.systemUTC()));
        }

        private void start() throws Exception { server.start(); }

        private HttpResponse<String> request(String method, String path, String body) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer author");
            if (body != null) builder.header("Content-Type", "application/json");
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        @Override public void close() {
            server.close();
            engine.close();
        }
    }
}
