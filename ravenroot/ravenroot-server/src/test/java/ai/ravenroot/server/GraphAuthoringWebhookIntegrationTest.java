package ai.ravenroot.server;

import static org.junit.jupiter.api.Assertions.*;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.authoring.GraphAuthoringRepository;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.core.persistence.InMemoryGraphDefinitionStore;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.UnknownBehaviorPolicy;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.authoring.EmptyPublishedGraphArtifactCatalogFixture;
import ai.ravenroot.server.authoring.ForwardingGraphAuthoringRepository;
import ai.ravenroot.server.authoring.GraphAuthoringConfiguration;
import ai.ravenroot.server.authoring.GraphAuthoringWebhookConfiguration;
import ai.ravenroot.server.authoring.GraphAuthoringWebhookHttpApi;
import ai.ravenroot.server.authoring.ReconciledGraphAuthoringRepository;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Real HTTP listener and shipped composition; deterministic repository, never a live provider. */
@Timeout(60)
class GraphAuthoringWebhookIntegrationTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    private static final String KEY = "provider-delivery-fixture-secret-32";
    private static final String NEW_KEY = "rotated-provider-fixture-secret-32";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test
    void defaultOffAndExplicitCompositionRejectUnconfiguredDeliveries() throws Exception {
        try (var engine = engine();
                var server = server(engine, 0)) {
            server.start();
            assertEquals(
                    404,
                    send(server.port(), "{}", Map.of("Authorization", "Bearer anything"))
                            .statusCode());
        }
        assertFalse(GraphAuthoringWebhookConfiguration.fromEnvironment(Map.of()).enabled());
        var invalid =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                GraphAuthoringWebhookConfiguration.fromEnvironment(
                                        Map.of(
                                                "RAVENROOT_GRAPH_AUTHORING_WEBHOOK_CREDENTIAL_REF",
                                                "webhook",
                                                "RAVENROOT_GRAPH_AUTHORING_WEBHOOK_AUTH",
                                                "secret-selection-canary")));
        assertFalse(invalid.getMessage().contains("secret-selection-canary"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EmbeddedGraphAuthoringConfiguration.disabled()
                                .withWebhooks(
                                        new GraphAuthoringWebhookConfiguration(
                                                "webhook",
                                                GraphAuthoringWebhookConfiguration.Authentication
                                                        .SIGNATURE)));
    }

    @Test
    void configuredRefCollisionsAreRefusedBeforeWebhookComposition() {
        var env = new java.util.HashMap<>(environment(true));
        env.put("RAVENROOT_GRAPH_AUTHORING_TENANT_NAMESPACES", "tenant-a=a/b,tenant-b=a-b");
        assertThrows(
                IllegalArgumentException.class,
                () -> GraphAuthoringConfiguration.fromEnvironment(env));
        env.put("RAVENROOT_GRAPH_AUTHORING_TENANT_NAMESPACES", "tenant-a=a/b");
        env.put("RAVENROOT_GRAPH_AUTHORING_RELEASE_BRANCH", "draft/a-b");
        assertThrows(
                IllegalArgumentException.class,
                () -> GraphAuthoringConfiguration.fromEnvironment(env));
    }

    @Test
    void githubAndGitlabDeliveriesAuthenticateFreshAndMapOnlyConfiguredRefs() throws Exception {
        for (boolean github : List.of(true, false)) {
            try (var fixture = new Fixture(github, false, Duration.ofSeconds(60))) {
                String body = fixture.payload("platform/graphs", "refs/heads/draft/customers-a");
                assertEquals(202, fixture.deliver(body, "one").statusCode());
                await(() -> fixture.state.calls("tenant-a") == 1);
                assertEquals(0, fixture.state.calls("tenant-b"));
                // Duplicates are authenticated first, then bounded delivery-ID retention suppresses
                // work.
                assertEquals(202, fixture.deliver(body, "one").statusCode());
                assertEquals(1, fixture.state.calls("tenant-a"));
                assertEquals(
                        403,
                        fixture.deliver(
                                        body + " ",
                                        "tampered",
                                        fixture.headers(body, "tampered", KEY, 0))
                                .statusCode());
                assertEquals(
                        403,
                        fixture.deliver(body, "bearer", Map.of("Authorization", "Bearer user"))
                                .statusCode());
                var cookie = new java.util.HashMap<>(fixture.headers(body, "cookie", KEY, 0));
                cookie.put("Cookie", "user=session");
                assertEquals(403, fixture.deliver(body, "cookie", cookie).statusCode());
                assertEquals(
                        403,
                        fixture.deliver(
                                        fixture.payload("other/graphs", "refs/heads/main"),
                                        "project")
                                .statusCode());
                assertEquals(
                        403,
                        fixture.deliver(
                                        fixture.payload(
                                                "platform/graphs",
                                                "refs/heads/draft/customers-unknown"),
                                        "tenant")
                                .statusCode());
                assertEquals(
                        403,
                        fixture.deliver(fixture.payload("platform/graphs", "refs/tags/main"), "tag")
                                .statusCode());
                assertEquals(
                        202,
                        fixture.deliver(
                                        fixture.payload("platform/graphs", "refs/heads/main"),
                                        "release")
                                .statusCode());
                await(
                        () ->
                                fixture.state.calls("tenant-a") == 2
                                        && fixture.state.calls("tenant-b") == 1);
                fixture.key.set(NEW_KEY);
                assertEquals(
                        403,
                        fixture.deliver(body, "rotated", fixture.headers(body, "rotated", KEY, 0))
                                .statusCode());
                assertEquals(202, fixture.deliver(body, "rotated").statusCode());
                await(() -> fixture.state.calls("tenant-a") == 3);
                fixture.key.set(null);
                assertEquals(
                        403,
                        fixture.deliver(
                                        body,
                                        "revoked",
                                        fixture.headers(body, "revoked", NEW_KEY, 0))
                                .statusCode());
                assertEquals(
                        11,
                        fixture.resolutions.get(),
                        "every authenticated delivery, including duplicates, resolves fresh"
                                + " material");
            }
        }
    }

    @Test
    void gitlabSignaturesRejectExpiredFutureAndDowngradeWhileLegacyIsExplicit() throws Exception {
        try (var fixture = new Fixture(false, false, Duration.ofSeconds(60))) {
            String body = fixture.payload("platform/graphs", "refs/heads/main");
            assertEquals(
                    403,
                    fixture.deliver(body, "old", fixture.headers(body, "old", KEY, -301))
                            .statusCode());
            assertEquals(
                    403,
                    fixture.deliver(body, "future", fixture.headers(body, "future", KEY, 301))
                            .statusCode());
            assertEquals(
                    403,
                    fixture.deliver(
                                    body,
                                    "downgrade",
                                    Map.of(
                                            "X-Gitlab-Event",
                                            "Push Hook",
                                            "X-Gitlab-Token",
                                            KEY,
                                            "X-Gitlab-Event-UUID",
                                            "downgrade"))
                            .statusCode());
            assertEquals(202, fixture.deliver(body, "signed").statusCode());
        }
        try (var fixture = new Fixture(false, true, Duration.ofSeconds(60))) {
            assertEquals(
                    202,
                    fixture.deliver(fixture.payload("platform/graphs", "refs/heads/main"), "legacy")
                            .statusCode());
            await(() -> fixture.state.calls("tenant-b") == 1);
        }
    }

    @Test
    void concurrentSignalsDuringReconciliationCoalesceAndLostEventsRecover() throws Exception {
        for (boolean github : List.of(true, false)) {
            try (var fixture = new Fixture(github, false, Duration.ofSeconds(60))) {
                var blocked =
                        new CompletableFuture<
                                GraphAuthoringRepository.Page<
                                        GraphAuthoringRepository.DocumentSummary>>();
                fixture.state.hold.set(blocked);
                fixture.state.desired.set(1);
                String body = fixture.payload("platform/graphs", "refs/heads/draft/customers-a");
                assertEquals(202, fixture.deliver(body, "initial").statusCode());
                await(() -> fixture.state.calls("tenant-a") == 1);
                fixture.state.desired.set(2);
                var calls = new ArrayList<CompletableFuture<HttpResponse<String>>>();
                for (int i = 0; i < 50; i++) {
                    String id = "concurrent-" + i;
                    calls.add(
                            CLIENT.sendAsync(
                                    fixture.request(body, fixture.headers(body, id, KEY, 0)),
                                    HttpResponse.BodyHandlers.ofString()));
                }
                CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new))
                        .get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(
                        calls.stream()
                                .allMatch(
                                        call ->
                                                Set.of(202, 429)
                                                        .contains(call.join().statusCode())));
                assertTrue(calls.stream().anyMatch(call -> call.join().statusCode() == 202));
                fixture.state.hold.set(null);
                blocked.complete(new GraphAuthoringRepository.Page<>(List.of(), ""));
                await(() -> fixture.state.observed.get() == 2);
                assertEquals(2, fixture.state.calls("tenant-a"));
                assertEquals(0, fixture.state.calls("tenant-b"));
            }
            try (var fixture = new Fixture(github, false, Duration.ofSeconds(1))) {
                fixture.state.desired.set(7); // No HTTP delivery: the periodic path is recovery.
                await(() -> fixture.state.observed.get() == 7);
                assertTrue(fixture.state.calls("tenant-a") >= 1);
            }
        }
    }

    @Test
    void rawByteLimitsMalformedBodiesIdentityReuseAndCloseAreBounded() throws Exception {
        var fixture = new Fixture(true, false, Duration.ofSeconds(60));
        try (fixture) {
            String body = fixture.payload("platform/graphs", "refs/heads/main");
            assertEquals(413, fixture.deliver("x".repeat(256 * 1024 + 1), "large").statusCode());
            assertEquals(400, fixture.deliver("[", "json").statusCode());
            assertEquals(202, fixture.deliver(body, "same-id").statusCode());
            assertEquals(
                    400,
                    fixture.deliver(
                                    fixture.payload(
                                            "platform/graphs", "refs/heads/draft/customers-a"),
                                    "same-id")
                            .statusCode());
            fixture.state.hold.set(new CompletableFuture<>());
            assertEquals(202, fixture.deliver(body, "closing").statusCode());
        }
        assertEquals(1, fixture.state.closed.get());
        assertEquals(1, fixture.decoratedClosed.get());
        try (var socket = new ServerSocket(fixture.port, 1, InetAddress.getLoopbackAddress())) {
            assertTrue(socket.isBound());
        }
    }

    @Test
    void slowBodiesRetainAdmissionUntilOwnedHandlersTerminate() throws Exception {
        try (var fixture = new Fixture(true, false, Duration.ofSeconds(60))) {
            var field = RavenrootServer.class.getDeclaredField("graphAuthoringWebhooks");
            field.setAccessible(true);
            var receiver = (GraphAuthoringWebhookHttpApi) field.get(fixture.server);
            var sockets = new ArrayList<java.net.Socket>();
            try {
                for (int i = 0; i < 16; i++) {
                    var socket =
                            new java.net.Socket(InetAddress.getLoopbackAddress(), fixture.port);
                    sockets.add(socket);
                    socket.getOutputStream()
                            .write(
                                    ("POST "
                                                    + GraphAuthoringWebhookHttpApi.PATH
                                                    + " HTTP/1.1\r\n"
                                                    + "Host: localhost\r\n"
                                                    + "Content-Type: application/json\r\n"
                                                    + "Content-Length: 1\r\n\r\n")
                                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
                await(() -> receiver.statistics().get("activeRequests") == 16);
                assertEquals(
                        429,
                        fixture.deliver(
                                        fixture.payload("platform/graphs", "refs/heads/main"),
                                        "overflow")
                                .statusCode());
                // Clients remain open and send no body. Deadline interruption/close must terminate
                // the actual socket readers before their admission slots can be reused.
                await(() -> receiver.statistics().get("activeRequests") == 0);
                assertEquals(0, receiver.statistics().get("pending"));
                assertEquals(0, receiver.statistics().get("queuedWorkers"));
                assertEquals(
                        202,
                        fixture.deliver(
                                        fixture.payload("platform/graphs", "refs/heads/main"),
                                        "after-timeout")
                                .statusCode());
            } finally {
                for (var socket : sockets) socket.close();
            }
        }
    }

    @Test
    @Timeout(90)
    void packagedMainUsesTheShippedOptInReceiverForBothProviders() throws Exception {
        for (boolean github : List.of(true, false)) {
            int port;
            try (var reserved = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                port = reserved.getLocalPort();
            }
            var directory = temporary.resolve(github ? "github" : "gitlab");
            java.nio.file.Files.createDirectories(directory);
            var log = directory.resolve("process.log");
            String classpath =
                    java.util.Arrays.stream(
                                    System.getProperty("java.class.path")
                                            .split(java.io.File.pathSeparator))
                            .map(path -> java.nio.file.Path.of(path).toAbsolutePath().toString())
                            .collect(
                                    java.util.stream.Collectors.joining(
                                            java.io.File.pathSeparator));
            var builder =
                    new ProcessBuilder(
                                    java.nio.file.Path.of(
                                                    System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-cp",
                                    classpath,
                                    RavenrootServerMain.class.getName())
                            .directory(directory.toFile())
                            .redirectErrorStream(true)
                            .redirectOutput(log.toFile());
            var environment = builder.environment();
            environment.keySet().removeIf(name -> name.startsWith("RAVENROOT_"));
            environment.putAll(environment(github));
            environment.put("RAVENROOT_PORT", Integer.toString(port));
            environment.put("RAVENROOT_BIND_ADDRESS", "127.0.0.1");
            environment.put("RAVENROOT_AUTH_MODE", "local-token");
            environment.put("RAVENROOT_AUTH_LOCAL_TOKEN", "packaged-user-fixture-local-token");
            environment.put("RAVENROOT_PROGRAM_RUNTIME", "disabled");
            environment.put("RAVENROOT_EXECUTION_STORE_DIR", directory.resolve("store").toString());
            environment.put(
                    "RAVENROOT_ARTIFACT_STORE_DIR", directory.resolve("artifacts").toString());
            environment.put(
                    github
                            ? "RAVENROOT_GRAPH_AUTHORING_GITHUB_API_BASE"
                            : "RAVENROOT_GRAPH_AUTHORING_GITLAB_API_BASE",
                    github ? "https://127.0.0.1:9" : "https://127.0.0.1:9/api/v4");
            environment.put("RAVENROOT_GRAPH_ARTIFACT_BASE_URL", "https://127.0.0.1:9/");
            environment.put("RAVENROOT_GRAPH_AUTHORING_WEBHOOK_CREDENTIAL_REF", "webhook");
            environment.put(
                    "RAVENROOT_CREDENTIAL_"
                            + HexFormat.of()
                                    .withUpperCase()
                                    .formatHex("provider".getBytes(StandardCharsets.UTF_8)),
                    KEY);
            environment.put(
                    "RAVENROOT_CREDENTIAL_"
                            + HexFormat.of()
                                    .withUpperCase()
                                    .formatHex("webhook".getBytes(StandardCharsets.UTF_8)),
                    github
                            ? KEY
                            : "whsec_"
                                    + Base64.getEncoder()
                                            .encodeToString(KEY.getBytes(StandardCharsets.UTF_8)));
            var process = builder.start();
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
                boolean ready = false;
                while (process.isAlive() && System.nanoTime() < deadline && !ready) {
                    try {
                        ready =
                                CLIENT.send(
                                                        HttpRequest.newBuilder(
                                                                        URI.create(
                                                                                "http://localhost:"
                                                                                        + port
                                                                                        + "/health"))
                                                                .timeout(Duration.ofMillis(300))
                                                                .GET()
                                                                .build(),
                                                        HttpResponse.BodyHandlers.discarding())
                                                .statusCode()
                                        == 200;
                    } catch (java.io.IOException unavailable) {
                        Thread.sleep(20);
                    }
                }
                assertTrue(
                        ready,
                        () -> {
                            try {
                                return java.nio.file.Files.readString(log);
                            } catch (Exception unavailable) {
                                return "packaged process did not bind";
                            }
                        });
                String body =
                        github
                                ? "{\"ref\":\"refs/heads/draft/customers-a\",\"repository\":{\"full_name\":\"platform/graphs\"}}"
                                : "{\"object_kind\":\"push\",\"ref\":\"refs/heads/draft/customers-a\",\"project\":{\"path_with_namespace\":\"platform/graphs\"}}";
                var mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                var headers = new java.util.HashMap<String, String>();
                if (github) {
                    headers.put("X-GitHub-Event", "push");
                    headers.put("X-GitHub-Delivery", "packaged");
                    headers.put(
                            "X-Hub-Signature-256",
                            "sha256="
                                    + HexFormat.of()
                                            .formatHex(
                                                    mac.doFinal(
                                                            body.getBytes(
                                                                    StandardCharsets.UTF_8))));
                } else {
                    String time = Long.toString(java.time.Instant.now().getEpochSecond());
                    headers.put("X-Gitlab-Event", "Push Hook");
                    headers.put("webhook-id", "packaged");
                    headers.put("webhook-timestamp", time);
                    headers.put(
                            "webhook-signature",
                            "v1,"
                                    + Base64.getEncoder()
                                            .encodeToString(
                                                    mac.doFinal(
                                                            ("packaged." + time + "." + body)
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8))));
                }
                assertEquals(
                        403,
                        send(
                                        port,
                                        body,
                                        Map.of(
                                                "Authorization",
                                                "Bearer packaged-user-fixture-local-token"))
                                .statusCode());
                assertEquals(202, send(port, body, headers).statusCode());
                assertEquals(202, send(port, body, headers).statusCode());
                assertEquals(403, send(port, body + " ", headers).statusCode());
            } finally {
                process.destroy();
                if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
                }
            }
            try (var rebound = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                assertTrue(rebound.isBound());
            }
        }
    }

    @Test
    void invalidWebhookStartupClosesCreatedArchiveAndReleasesListener() throws Exception {
        var state = new State();
        var env = environment(true);
        var configuration = GraphAuthoringConfiguration.fromEnvironment(env);
        var embedded =
                EmbeddedGraphAuthoringConfiguration.forTest(
                                configuration,
                                ref ->
                                        ref.equals("provider")
                                                ? Optional.of(new SecretValue(KEY.toCharArray()))
                                                : Optional.empty(),
                                (c, r, p, a) ->
                                        new ReconciledGraphAuthoringRepository(
                                                state.repository(),
                                                configuration.tenantNamespaces().keySet(),
                                                Duration.ofSeconds(60)),
                                EmptyPublishedGraphArtifactCatalogFixture::create)
                        .withWebhooks(
                                new GraphAuthoringWebhookConfiguration(
                                        "missing",
                                        GraphAuthoringWebhookConfiguration.Authentication
                                                .SIGNATURE));
        int port;
        try (var reserved = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = reserved.getLocalPort();
        }
        try (var engine = engine()) {
            var server = server(engine, port);
            assertThrows(
                    IllegalStateException.class, () -> server.configureGraphAuthoring(embedded));
            server.close();
        }
        assertEquals(1, state.closed.get());
        try (var rebound = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            assertTrue(rebound.isBound());
        }
    }

    private static final class State {
        final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        final AtomicReference<
                        CompletableFuture<
                                GraphAuthoringRepository.Page<
                                        GraphAuthoringRepository.DocumentSummary>>>
                hold = new AtomicReference<>();
        final AtomicInteger desired = new AtomicInteger(),
                observed = new AtomicInteger(),
                closed = new AtomicInteger();

        int calls(String tenant) {
            return counts.getOrDefault(tenant, new AtomicInteger()).get();
        }

        GraphAuthoringRepository repository() {
            return (GraphAuthoringRepository)
                    java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {GraphAuthoringRepository.class},
                            (proxy, method, args) -> {
                                return switch (method.getName()) {
                                    case "capabilities" ->
                                            Set.of(GraphAuthoringRepository.Capability.LIST);
                                    case "close" -> {
                                        closed.incrementAndGet();
                                        yield null;
                                    }
                                    case "list" -> {
                                        var actor = (GraphAuthoringRepository.Actor) args[0];
                                        counts.computeIfAbsent(
                                                        actor.tenantId(),
                                                        ignored -> new AtomicInteger())
                                                .incrementAndGet();
                                        int current = desired.get();
                                        var paused = hold.get();
                                        var result =
                                                paused == null
                                                        ? CompletableFuture.completedFuture(
                                                                new GraphAuthoringRepository.Page<
                                                                        GraphAuthoringRepository
                                                                                .DocumentSummary>(
                                                                        List.of(), ""))
                                                        : paused;
                                        yield result.thenApply(
                                                page -> {
                                                    observed.set(current);
                                                    return page;
                                                });
                                    }
                                    default -> throw new UnsupportedOperationException();
                                };
                            });
        }
    }

    private static final class Fixture implements AutoCloseable {
        final boolean github, legacy;
        final State state = new State();
        final AtomicReference<String> key = new AtomicReference<>(KEY);
        final AtomicInteger resolutions = new AtomicInteger(),
                decoratedClosed = new AtomicInteger();
        final PekkoExecutionEngine engine = engine();
        final RavenrootServer server;
        final int port;

        Fixture(boolean github, boolean legacy, Duration interval) throws Exception {
            this.github = github;
            this.legacy = legacy;
            var environment = new java.util.HashMap<>(environment(github));
            environment.put("RAVENROOT_GRAPH_AUTHORING_WEBHOOK_CREDENTIAL_REF", "webhook");
            environment.put(
                    "RAVENROOT_GRAPH_AUTHORING_WEBHOOK_AUTH", legacy ? "TOKEN" : "SIGNATURE");
            environment.put("RAVENROOT_GRAPH_AUTHORING_REQUEST_TIMEOUT_MILLIS", "2000");
            var source = GraphAuthoringConfiguration.fromEnvironment(environment);
            CredentialResolver resolver =
                    ref -> {
                        if (ref.equals("provider"))
                            return Optional.of(new SecretValue(KEY.toCharArray()));
                        resolutions.incrementAndGet();
                        String current = key.get();
                        if (current == null) return Optional.empty();
                        if (!github && !legacy)
                            current =
                                    "whsec_"
                                            + Base64.getEncoder()
                                                    .encodeToString(
                                                            current.getBytes(
                                                                    StandardCharsets.UTF_8));
                        return Optional.of(new SecretValue(current.toCharArray()));
                    };
            // Identical typed settings parser is used by packaged Main's fromEnvironment call.
            var settings = GraphAuthoringWebhookConfiguration.fromEnvironment(environment);
            var embedded =
                    EmbeddedGraphAuthoringConfiguration.forTest(
                                    source,
                                    resolver,
                                    (c, r, p, a) ->
                                            new ReconciledGraphAuthoringRepository(
                                                    state.repository(),
                                                    source.tenantNamespaces().keySet(),
                                                    interval),
                                    EmptyPublishedGraphArtifactCatalogFixture::create)
                            .withWebhooks(settings)
                            .decorateRepository(
                                    delegate ->
                                            new ForwardingGraphAuthoringRepository(delegate) {
                                                @Override
                                                public void close() {
                                                    decoratedClosed.incrementAndGet();
                                                }
                                            });
            server = server(engine, 0).configureGraphAuthoring(embedded);
            server.start();
            port = server.port();
        }

        String payload(String project, String ref) {
            return github
                    ? "{\"ref\":\""
                            + ref
                            + "\",\"repository\":{\"full_name\":\""
                            + project
                            + "\"},\"tenant\":\"attacker\"}"
                    : "{\"object_kind\":\"push\",\"ref\":\""
                            + ref
                            + "\",\"project\":{\"path_with_namespace\":\""
                            + project
                            + "\"},\"tenant\":\"attacker\"}";
        }

        Map<String, String> headers(String body, String id, String secret, long offset)
                throws Exception {
            var result = new java.util.HashMap<String, String>();
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (github) {
                result.put("X-GitHub-Event", "push");
                result.put("X-GitHub-Delivery", id);
                result.put(
                        "X-Hub-Signature-256",
                        "sha256="
                                + HexFormat.of()
                                        .formatHex(
                                                mac.doFinal(
                                                        body.getBytes(StandardCharsets.UTF_8))));
            } else {
                result.put("X-Gitlab-Event", "Push Hook");
                if (legacy) {
                    result.put("X-Gitlab-Token", secret);
                    result.put("X-Gitlab-Event-UUID", id);
                } else {
                    String timestamp =
                            Long.toString(java.time.Instant.now().getEpochSecond() + offset);
                    result.put("webhook-id", id);
                    result.put("webhook-timestamp", timestamp);
                    result.put(
                            "webhook-signature",
                            "v1,invalid v1,"
                                    + Base64.getEncoder()
                                            .encodeToString(
                                                    mac.doFinal(
                                                            (id + "." + timestamp + "." + body)
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8))));
                }
            }
            return result;
        }

        HttpRequest request(String body, Map<String, String> headers) {
            var builder =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://localhost:"
                                                    + port
                                                    + GraphAuthoringWebhookHttpApi.PATH))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(builder::header);
            return builder.build();
        }

        HttpResponse<String> deliver(String body, String id) throws Exception {
            return deliver(body, id, headers(body, id, key.get() == null ? KEY : key.get(), 0));
        }

        HttpResponse<String> deliver(String body, String id, Map<String, String> headers)
                throws Exception {
            return CLIENT.send(request(body, headers), HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() {
            server.close();
            engine.close();
        }
    }

    private static HttpResponse<String> send(int port, String body, Map<String, String> headers)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:"
                                                + port
                                                + GraphAuthoringWebhookHttpApi.PATH))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> environment(boolean github) {
        var env = new java.util.HashMap<String, String>();
        env.put("RAVENROOT_GRAPH_AUTHORING_MODE", "GIT");
        env.put("RAVENROOT_GRAPH_AUTHORING_PROVIDER", github ? "GITHUB" : "GITLAB");
        env.put("RAVENROOT_GRAPH_AUTHORING_REPOSITORY_OWNER", "platform");
        env.put("RAVENROOT_GRAPH_AUTHORING_REPOSITORY_NAME", "graphs");
        env.put("RAVENROOT_GRAPH_AUTHORING_GITLAB_PROJECT", "platform/graphs");
        env.put(
                github
                        ? "RAVENROOT_GRAPH_AUTHORING_GITHUB_API_BASE"
                        : "RAVENROOT_GRAPH_AUTHORING_GITLAB_API_BASE",
                github ? "https://api.github.com" : "https://gitlab.example/api/v4");
        env.put("RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_MODE", "PAT");
        env.put("RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_REFERENCE", "provider");
        env.put(
                "RAVENROOT_GRAPH_AUTHORING_TENANT_NAMESPACES",
                "tenant-a=customers/a,tenant-b=customers/b");
        env.put("RAVENROOT_GRAPH_ARTIFACT_BASE_URL", "https://artifacts.example.test/");
        env.put("RAVENROOT_GRAPH_AUTHORING_RETRY_LIMIT", "0");
        return env;
    }

    private static PekkoExecutionEngine engine() {
        return new PekkoExecutionEngine("webhook-fixture-" + UUID.randomUUID());
    }

    private static RavenrootServer server(PekkoExecutionEngine engine, int port) throws Exception {
        var application =
                new DefaultRavenrootApplication(
                        engine,
                        new ExecutionMonitor(),
                        BehaviorRegistry.standard(),
                        new InMemoryArtifactRegistry(),
                        new DisabledProgramRuntime(),
                        ExecutionIdentitySource.randomUuids(),
                        null,
                        0,
                        UnknownBehaviorPolicy.passThrough(),
                        new InMemoryGraphDefinitionStore(Clock.systemUTC()));
        return new RavenrootServer(
                application,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                null,
                new DisabledLoopbackAuthenticator());
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "condition did not complete within bounded wait");
    }
}
