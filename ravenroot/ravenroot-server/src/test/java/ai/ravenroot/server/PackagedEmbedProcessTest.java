package ai.ravenroot.server;

import ai.ravenroot.api.embed.EmbedCapability;
import ai.ravenroot.api.embed.EmbedGraphProjection;
import ai.ravenroot.api.embed.EmbedProjectionBudget;
import ai.ravenroot.api.embed.EmbedProjectionEligibility;
import ai.ravenroot.api.embed.EmbedProvisionCommand;
import ai.ravenroot.api.embed.EmbedProvisionOutcome;
import ai.ravenroot.api.embed.EmbedRevokeCommand;
import ai.ravenroot.api.embed.EmbedRevokeOutcome;
import ai.ravenroot.api.embed.EmbedSnapshotLifecycle;
import ai.ravenroot.api.embed.VerifiedEmbedGraphGrant;
import ai.ravenroot.persistence.sqlite.SqliteEmbedRegistrationStore;
import ai.ravenroot.server.embed.EmbedBrowserHttpHandler;
import ai.ravenroot.server.embed.P256EmbedProofVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packaged process, forked for real, serves an embed against the durable authority.
 *
 * <h2>Why a forked process rather than an in-JVM composition</h2>
 * <p>{@code EmbedBrowserHttpIntegrationTest} already drives the five routes against a
 * {@link RavenrootServer} this test code assembled. What it cannot establish is that
 * {@link RavenrootServerMain} — the thing an operator actually runs — assembles the same server from
 * environment variables alone, opens the SQLite registration authority, and enables the routes
 * instead of refusing before the bind. That was the whole of the relevant contract, and only a real
 * {@code main(String[])} with a real environment can show it is over.</p>
 *
 * <h2>The registration is written by a different process than the one that serves it</h2>
 * <p>The store is provisioned here, in the test JVM, and read there, in the child. That is the
 * operator CLI's relationship to the running server in miniature, and it is what makes the
 * revocation step at the end mean something: the child is not restarted between the projection
 * succeeding and the revocation taking effect.</p>
 */
class PackagedEmbedProcessTest {

    private static final String VIEWER = "https://viewer.example";
    private static final String PARENT = "https://parent.example";
    /** {@code LocalTokenAuthenticator}'s fixed identity; the registration is provisioned to match it. */
    private static final String ISSUER = "urn:ravenroot:local";
    private static final String SUBJECT = "local-workload";
    private static final String TENANT = "local";
    private static final String TOKEN = "packaged-embed-process-test-token-0123456789";

    @TempDir
    Path root;

    @Test
    void theProcessEnablesTheFiveRoutesCompletesTheFlowAndHonoursALiveRevocation() throws Exception {
        provisionRegistration();
        int port = freePort();
        Process child = startServer(port);
        try {
            awaitListening(child, port);
            var client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;

            // 1. create-session: the workload supplies only the registration id.
            var created = send(client, request(base + EmbedBrowserHttpHandler.CREATE_PATH)
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"registrationId\":\"packaged-reg\"}")));
            assertEquals(201, created.statusCode(), created.body());
            URI launchUri = URI.create(json(created.body(), "launchUrl"));

            // 2. launch: one-use ticket, framed only by the registration's parent origin.
            var launched = send(client, request(base + launchUri.getRawPath() + "?"
                    + launchUri.getRawQuery())
                    .header("Sec-Fetch-Mode", "navigate").header("Sec-Fetch-Dest", "iframe").GET());
            assertEquals(200, launched.statusCode(), launched.body());
            assertTrue(launched.headers().firstValue("Content-Security-Policy").orElseThrow()
                    .contains("frame-ancestors " + PARENT));
            assertTrue(launched.body().contains("\"grantRevision\":\"1\""), launched.body());
            String exchangeId = json(launched.body(), "exchangeId");
            String challenge = json(launched.body(), "challenge");
            String channelId = json(launched.body(), "channelId");
            String acknowledgementId = json(launched.body(), "acknowledgementId");
            String correlationId = "packaged-ack-correlation";

            // 3. server-to-server acknowledgement from the embedding application.
            var acknowledged = send(client, request(base + EmbedBrowserHttpHandler.ACKNOWLEDGEMENT_PATH)
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"registrationId\":\"packaged-reg\","
                            + "\"acknowledgementId\":\"" + acknowledgementId + "\",\"channelId\":\""
                            + channelId + "\",\"correlationId\":\"" + correlationId + "\"}")));
            assertEquals(200, acknowledged.statusCode(), acknowledged.body());

            // 4. exchange: the viewer proves possession of a P-256 key and receives a bearer.
            KeyPair pair = keyPair();
            var publicKey = (ECPublicKey) pair.getPublic();
            Instant exchangeAt = Instant.now();
            String exchangeBody = "{\"exchangeId\":\"" + exchangeId + "\",\"channelId\":\"" + channelId
                    + "\",\"ackCorrelationId\":\"" + correlationId + "\",\"keyX\":\""
                    + coordinate(publicKey.getW().getAffineX()) + "\",\"keyY\":\""
                    + coordinate(publicKey.getW().getAffineY()) + "\",\"nonce\":\"" + challenge
                    + "\",\"jti\":\"packaged-exchange\",\"issuedAt\":\"" + exchangeAt
                    + "\",\"signature\":\"" + exchangeSignature(pair, exchangeId, 1, challenge,
                    channelId, correlationId, "packaged-exchange", exchangeAt) + "\"}";
            var exchanged = send(client, viewerPost(base + EmbedBrowserHttpHandler.EXCHANGE_PATH,
                    exchangeBody));
            assertEquals(200, exchanged.statusCode(), exchanged.body());
            String bearer = json(exchanged.body(), "bearer");
            String projectionNonce = json(exchanged.body(), "challenge");

            // 5. projection: the admitted payload, and only that.
            var projected = send(client, viewerPost(base + EmbedBrowserHttpHandler.PROJECTION_PATH,
                    projectionBody(pair, bearer, projectionNonce, "packaged-projection"))
                    .header("Authorization", "Bearer " + bearer));
            assertEquals(200, projected.statusCode(), projected.body());
            assertTrue(projected.body().contains("\"graphId\":\"packaged-graph\""), projected.body());
            assertTrue(projected.body().contains("\"id\":\"start\""), projected.body());
            for (String forbidden : List.of("tenant", "parent.example", "behavior", "properties",
                    "bearer", "ticket", "registrationId")) {
                assertFalse(projected.body().contains(forbidden), forbidden + " reached the browser");
            }

            // A revocation written by another process is honoured by this one without a restart.
            revokeRegistration();
            var afterRevocation = send(client, viewerPost(base + EmbedBrowserHttpHandler.PROJECTION_PATH,
                    projectionBody(pair, bearer, projectionNonce, "packaged-after-revocation"))
                    .header("Authorization", "Bearer " + bearer));
            assertEquals(403, afterRevocation.statusCode(), afterRevocation.body());
            assertEquals("{\"error\":\"EMBED_SESSION_UNAVAILABLE\"}", afterRevocation.body());
        } finally {
            child.destroyForcibly();
            child.waitFor(30, TimeUnit.SECONDS);
        }
    }

    // ---------------------------------------------------------------- fixture

    private void provisionRegistration() {
        var graphGrant = new VerifiedEmbedGraphGrant(TENANT, "packaged-resource", "packaged-deployment",
                1, "packaged-graph", "v1", "sha256:packaged", "packaged-policy");
        var projection = new EmbedGraphProjection(EmbedGraphProjection.CURRENT_CONTRACT_VERSION,
                "packaged-graph", "v1", "sha256:packaged",
                List.of(new EmbedGraphProjection.Node("start", "START",
                                new EmbedGraphProjection.Layout(10, 20, 30, 40)),
                        new EmbedGraphProjection.Node("end", "END", null)),
                List.of(new EmbedGraphProjection.Edge("start", "end")));
        try (var store = SqliteEmbedRegistrationStore.openUnder(storeDirectory(), Clock.systemUTC(),
                EmbedProjectionBudget.DEFAULTS)) {
            assertInstanceOf(EmbedProvisionOutcome.Provisioned.class,
                    store.provision(new EmbedProvisionCommand("packaged-reg", 0, ISSUER, SUBJECT,
                            TENANT, PARENT, Set.of(EmbedCapability.GRAPH_READ), Optional.empty(),
                            graphGrant, EmbedSnapshotLifecycle.PUBLISHED,
                            EmbedProjectionEligibility.allowed("packaged-policy"), projection)));
        }
    }

    private void revokeRegistration() {
        try (var store = SqliteEmbedRegistrationStore.openUnder(storeDirectory(), Clock.systemUTC(),
                EmbedProjectionBudget.DEFAULTS)) {
            assertInstanceOf(EmbedRevokeOutcome.Revoked.class,
                    store.revoke(new EmbedRevokeCommand("packaged-reg", TENANT, 1)));
        }
    }

    private Path storeDirectory() {
        return root.resolve("embed");
    }

    private Process startServer(int port) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        var command = List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                RavenrootServerMain.class.getName());
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("RAVENROOT_PORT", Integer.toString(port));
        environment.put("RAVENROOT_AUTH_MODE", "local-token");
        environment.put("RAVENROOT_AUTH_LOCAL_TOKEN", TOKEN);
        environment.put("RAVENROOT_EMBED_ENABLED", "true");
        environment.put("RAVENROOT_EMBED_REGISTRATION_DIR", storeDirectory().toString());
        environment.put("RAVENROOT_EMBED_VIEWER_ORIGIN", VIEWER);
        environment.put("RAVENROOT_EMBED_SINGLE_PROCESS_ACKNOWLEDGED", "true");
        environment.put("RAVENROOT_REPLICAS", "1");
        environment.put("RAVENROOT_AUDIT_DIR", root.resolve("audit").toString());
        environment.put("RAVENROOT_ARTIFACT_STORE_DIR", root.resolve("artifact-store").toString());
        environment.put("RAVENROOT_CREDENTIAL_DIR", root.resolve("credentials").toString());
        environment.put("RAVENROOT_EXECUTION_STORE_ENABLED", "false");
        environment.put("RAVENROOT_PROGRAM_RUNTIME", "disabled");
        return builder.start();
    }

    /**
     * Waits for the process's own «listening» line rather than polling the port.
     *
     * <p>A poll would also succeed against a port some other process happens to hold, and would say
     * nothing about whether this one refused at startup. The transcript is kept so a failure reports
     * what the child actually said — including a {@code startup_refused} line, which is the single
     * most likely way this test breaks.</p>
     */
    private void awaitListening(Process child, int port) throws Exception {
        var transcript = new ArrayList<String>();
        var output = new BufferedReader(new InputStreamReader(child.getInputStream(),
                StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            if (!output.ready()) {
                if (!child.isAlive()) {
                    throw new AssertionError("the packaged server exited before listening; transcript: "
                            + transcript);
                }
                Thread.sleep(50);
                continue;
            }
            String line = output.readLine();
            if (line == null) break;
            transcript.add(line);
            if (line.contains("listening on http://") && line.contains(":" + port)) {
                assertTrue(transcript.stream().anyMatch(entry -> entry.contains("\"embed-browser\"")
                                && entry.contains("\"enabled\":true")),
                        "the process must announce that it enabled the embed browser; transcript: "
                                + transcript);
                // Drained on a daemon thread from here on: the child keeps logging, and a full pipe
                // buffer would block it mid-request and look like a server hang.
                Thread drain = new Thread(() -> {
                    try {
                        while (output.readLine() != null) {
                            // Discarded on purpose; the assertions above are the ones that matter.
                        }
                    } catch (java.io.IOException closed) {
                        // The child was destroyed; nothing to report.
                    }
                });
                drain.setDaemon(true);
                drain.start();
                return;
            }
        }
        throw new AssertionError("the packaged server never reported listening; transcript: " + transcript);
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ---------------------------------------------------------------- http

    private static HttpRequest.Builder request(String uri) {
        return HttpRequest.newBuilder(URI.create(uri));
    }

    private static HttpRequest.Builder viewerPost(String uri, String body) {
        return request(uri).header("Origin", VIEWER).header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors").header("Sec-Fetch-Dest", "empty")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request)
            throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String projectionBody(KeyPair pair, String bearer, String nonce, String jti)
            throws Exception {
        Instant at = Instant.now();
        return "{\"nonce\":\"" + nonce + "\",\"jti\":\"" + jti + "\",\"issuedAt\":\"" + at
                + "\",\"signature\":\"" + signature(pair, bearer, 1, nonce, jti,
                EmbedBrowserHttpHandler.PROJECTION_PATH, at) + "\"}";
    }

    private static KeyPair keyPair() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String signature(KeyPair pair, String bearer, long revision, String nonce, String jti,
                                    String path, Instant time) throws Exception {
        var signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(pair.getPrivate());
        signature.update(P256EmbedProofVerifier.payload(bearer, revision, nonce, jti, "POST", path, time));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    private static String exchangeSignature(KeyPair pair, String exchangeId, long revision, String nonce,
                                            String channelId, String correlationId, String jti,
                                            Instant time) throws Exception {
        var signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(pair.getPrivate());
        signature.update(P256EmbedProofVerifier.exchangePayload(exchangeId, revision, nonce, channelId,
                correlationId, jti, "POST", EmbedBrowserHttpHandler.EXCHANGE_PATH, time));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    private static String coordinate(BigInteger coordinate) {
        byte[] signed = coordinate.toByteArray();
        byte[] fixed = new byte[32];
        int source = signed.length == 33 && signed[0] == 0 ? 1 : 0;
        System.arraycopy(signed, source, fixed, fixed.length - (signed.length - source),
                signed.length - source);
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
