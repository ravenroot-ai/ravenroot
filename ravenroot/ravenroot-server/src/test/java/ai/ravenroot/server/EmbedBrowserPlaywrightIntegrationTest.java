package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.LocalDeploymentState;
import ai.ravenroot.api.embed.AuthorizedEmbedGraphProjection;
import ai.ravenroot.api.embed.AuthorizedEmbedSessionCreation;
import ai.ravenroot.api.embed.EmbedCapability;
import ai.ravenroot.api.embed.EmbedGraphProjection;
import ai.ravenroot.api.embed.EmbedProjectionEligibility;
import ai.ravenroot.api.embed.EmbedProvisionCommand;
import ai.ravenroot.api.embed.EmbedProvisionOutcome;
import ai.ravenroot.api.embed.EmbedRevokeCommand;
import ai.ravenroot.api.embed.EmbedSnapshotLifecycle;
import ai.ravenroot.api.embed.InMemoryEmbedRegistrationAuthority;
import ai.ravenroot.api.embed.EmbedTheme;
import ai.ravenroot.api.embed.VerifiedEmbedGraphGrant;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.embed.EmbedBrowserConfiguration;
import ai.ravenroot.server.embed.EmbedViewerOrigin;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.AuthenticationException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in orchestration of the real server and the three-origin Playwright boundary fixture. */
class EmbedBrowserPlaywrightIntegrationTest {
    @Test
    void realBrowserExercisesTheProductionHandlerAndBootstrapAcrossThreeHttpsOrigins(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("ravenroot.embed.browserTest"),
                "run through the ravenroot-ui test:e2e:embed script");
        Path ui = locateUi();
        assertTrue(Files.isRegularFile(ui.resolve("dist/embed-bootstrap.js")),
                "the UI build must contain the production embed bootstrap");
        assertTrue(Files.isRegularFile(ui.resolve("dist/embed-viewer.js")),
                "the UI build must contain the isolated viewer entry");
        assertTrue(Files.isRegularFile(ui.resolve("dist/embed-viewer.css")),
                "the UI build must contain the isolated viewer shell styles");

        var ports = availablePorts(3);
        int parentPort = ports.get(0);
        int viewerPort = ports.get(1);
        int foreignPort = ports.get(2);
        String parentOrigin = "https://127.0.0.1:" + parentPort;
        String viewerOrigin = "https://127.0.0.1:" + viewerPort;
        String foreignOrigin = "https://127.0.0.1:" + foreignPort;

        var registrations = registrations(parentOrigin);
        var applicationReference = new AtomicReference<DefaultRavenrootApplication>();
        try (var engine = new PekkoExecutionEngine("embed-browser-playwright");
             var server = server(engine, ui.resolve("dist"), viewerOrigin, parentOrigin, registrations,
                     directory.resolve("executions.db"), applicationReference)) {
            server.start();
            var control = com.sun.net.httpserver.HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            control.createContext("/revoke", exchange -> {
                var outcome = registrations.revoke(new EmbedRevokeCommand(
                        "live-registration", "tenant", 1));
                byte[] body = outcome.getClass().getSimpleName().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            control.createContext("/replace", exchange -> {
                try {
                    replaceStaleDeployment(applicationReference.get());
                    exchange.sendResponseHeaders(204, -1);
                } catch (Exception failure) {
                    exchange.sendResponseHeaders(500, -1);
                }
                exchange.close();
            });
            control.start();
            Path processOutput = Files.createTempFile("ravenroot-embed-playwright-", ".log");
            var process = new ProcessBuilder(ui.resolve("node_modules/.bin/playwright").toString(),
                    "test", "--config=playwright.embed.config.js")
                    .directory(ui.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(processOutput.toFile());
            process.environment().put("RR_EMBED_BACKEND_ORIGIN", "http://127.0.0.1:" + server.port());
            process.environment().put("RR_EMBED_PARENT_ORIGIN", parentOrigin);
            process.environment().put("RR_EMBED_VIEWER_ORIGIN", viewerOrigin);
            process.environment().put("RR_EMBED_FOREIGN_ORIGIN", foreignOrigin);
            process.environment().put("RR_EMBED_CONTROL_ORIGIN",
                    "http://127.0.0.1:" + control.getAddress().getPort());
            Process browser = process.start();
            try {
                assertTrue(browser.waitFor(90, TimeUnit.SECONDS), "Playwright did not terminate in 90 seconds");
                assertEquals(0, browser.exitValue(), "Playwright embed boundary suite failed:\n"
                        + Files.readString(processOutput));
            } finally {
                if (browser.isAlive()) {
                    browser.destroy();
                    if (!browser.waitFor(5, TimeUnit.SECONDS)) browser.destroyForcibly();
                }
                control.stop(0);
                Files.deleteIfExists(processOutput);
            }
        }
    }

    private static InMemoryEmbedRegistrationAuthority registrations(String parent) {
        // The payload the viewer renders is captured into each registration at provision time; there
        // is no read-time projection lambda any more, because there is no second read to configure.
        var registrations = new InMemoryEmbedRegistrationAuthority();
        for (var registration : List.of("browser-registration", "theme-auto-light", "theme-auto-dark",
                "theme-invalid")) {
            provision(registrations, registration, parent, Optional.empty());
        }
        provision(registrations, "theme-light", parent, Optional.of(EmbedTheme.LIGHT));
        provision(registrations, "theme-dark", parent, Optional.of(EmbedTheme.DARK));
        var live = registrations.provision(EmbedProvisionCommand.deploymentV2WithExecutionCapability(
                "live-registration", 0, "browser-issuer", "browser-workload", "tenant", parent,
                Optional.empty(), "orders", true));
        if (!(live instanceof EmbedProvisionOutcome.Provisioned)) {
            throw new AssertionError("the live fixture registration was refused: " + live);
        }
        var stale = registrations.provision(EmbedProvisionCommand.deploymentV2WithExecutionCapability(
                "live-stale-registration", 0, "browser-issuer", "browser-workload", "tenant", parent,
                Optional.empty(), "orders-stale", true));
        if (!(stale instanceof EmbedProvisionOutcome.Provisioned)) {
            throw new AssertionError("the stale-version fixture registration was refused: " + stale);
        }
        return registrations;
    }

    private static RavenrootServer server(PekkoExecutionEngine engine, Path ui, String viewer, String parent,
                                          InMemoryEmbedRegistrationAuthority registrations,
                                          Path executionDatabase,
                                          AtomicReference<DefaultRavenrootApplication> applicationReference)
            throws Exception {
        var authorization = new DefaultAuthorizationService(event -> { });
        var projections = new AuthorizedEmbedGraphProjection(authorization, registrations);
        var config = new EmbedBrowserConfiguration(true, new EmbedViewerOrigin(viewer),
                new AuthorizedEmbedSessionCreation(authorization, registrations), registrations, projections,
                event -> { }, Clock.systemUTC(), Duration.ofMinutes(1), Duration.ofMinutes(1),
                Duration.ofMinutes(2), Duration.ofMinutes(1), 16, 16, 32, 1, true);
        var environment = BehaviorEnvironment.safeDefaults();
        var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                BehaviorRegistry.standard(environment), environment.artifacts(), environment.programRuntime(),
                ExecutionIdentitySource.randomUuids(),
                new SqliteExecutionStore(executionDatabase, Clock.systemUTC()), 2);
        applicationReference.set(application);
        application.registerLocalDeployment(operator("embed-browser-register"), "orders",
                new ByteArrayInputStream(LIVE_GRAPH.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var started = application.startLocalDeployment(operator("embed-browser-start"), "orders")
                .toCompletableFuture().get(10, TimeUnit.SECONDS).orElseThrow();
        assertEquals(LocalDeploymentState.READY, started.state(),
                "the live browser fixture must expose only a successfully started deployment");
        application.registerLocalDeployment(operator("embed-browser-stale-register"), "orders-stale",
                new ByteArrayInputStream(LIVE_GRAPH.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(LocalDeploymentState.READY,
                application.startLocalDeployment(operator("embed-browser-stale-start"), "orders-stale")
                        .toCompletableFuture().get(10, TimeUnit.SECONDS).orElseThrow().state());
        return new RavenrootServer(application,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), ui,
                headers -> {
                    if (!"Bearer browser-workload".equals(headers.getFirst("Authorization"))) {
                        throw new AuthenticationException("missing");
                    }
                    return new AuthenticatedPrincipal("browser-workload", AuthenticatedPrincipal.Type.WORKLOAD,
                            "browser-issuer", "tenant", Set.of(Role.VIEWER),
                            Set.of("ravenroot.embed.session.create"));
                }, authorization, config);
    }

    private static SecurityContext operator(String requestId) {
        return new SecurityContext(requestId, "tenant", "operator", PrincipalType.USER, "browser-issuer");
    }

    private static void replaceStaleDeployment(DefaultRavenrootApplication application) throws Exception {
        application.undeployLocalDeployment("tenant", "orders-stale")
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        var security = operator("embed-browser-replace");
        application.registerLocalDeployment(security, "orders-stale", new ByteArrayInputStream(
                LIVE_GRAPH_REPLACEMENT.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        application.startLocalDeployment(security, "orders-stale")
                .toCompletableFuture().get(10, TimeUnit.SECONDS).orElseThrow();
    }

    private static final String LIVE_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="label" for="node" attr.name="label" attr.type="string"/>
              <key id="classification" for="node" attr.name="classification" attr.type="string"/>
              <key id="arrangement" for="graph" attr.name="ravenroot.designArrangement" attr.type="string"/>
              <graph id="browser-secret-topology" edgedefault="directed">
                <data key="arrangement">flow</data>
                <node id="start"><data key="kind">START</data><data key="label">Live start</data></node>
                <node id="end"><data key="kind">END</data><data key="label">Live end</data>
                  <data key="classification">quartz-worker</data></node>
                <edge id="live-edge" source="start" target="end"/>
              </graph>
            </graphml>
            """;

    private static final String LIVE_GRAPH_REPLACEMENT = LIVE_GRAPH.replace(
            "<data key=\"label\">Live end</data>", "<data key=\"label\">Replacement end</data>");

    private static void provision(InMemoryEmbedRegistrationAuthority registrations, String registrationId,
                                  String parent, Optional<EmbedTheme> theme) {
        var graphGrant = new VerifiedEmbedGraphGrant("tenant", "resource", "deployment", 1,
                "browser-secret-graph", "version", "digest", "policy");
        var projection = new EmbedGraphProjection(EmbedGraphProjection.CURRENT_CONTRACT_VERSION,
                "browser-secret-graph", "version", "digest", List.of(
                        new EmbedGraphProjection.Node("start", "START",
                                new EmbedGraphProjection.Layout(80, 120, 84, 84)),
                        new EmbedGraphProjection.Node("behavior", "BEHAVIOR",
                                new EmbedGraphProjection.Layout(260, 120, 120, 56)),
                        new EmbedGraphProjection.Node("end", "END",
                                new EmbedGraphProjection.Layout(440, 120, 84, 84))),
                List.of(new EmbedGraphProjection.Edge("start", "behavior"),
                        new EmbedGraphProjection.Edge("behavior", "end")));
        var outcome = registrations.provision(new EmbedProvisionCommand(registrationId, 0, "browser-issuer",
                "browser-workload", "tenant", parent, Set.of(EmbedCapability.GRAPH_READ), theme,
                graphGrant, EmbedSnapshotLifecycle.PUBLISHED, EmbedProjectionEligibility.allowed("policy"),
                projection));
        if (!(outcome instanceof EmbedProvisionOutcome.Provisioned)) {
            throw new AssertionError("the fixture registration was refused: " + outcome);
        }
    }

    private static List<Integer> availablePorts(int count) throws Exception {
        var sockets = new java.util.ArrayList<ServerSocket>(count);
        try {
            for (int index = 0; index < count; index++) {
                var socket = new ServerSocket();
                socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                sockets.add(socket);
            }
            return sockets.stream().map(ServerSocket::getLocalPort).toList();
        } finally {
            for (var socket : sockets) socket.close();
        }
    }

    private static Path locateUi() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            Path nested = candidate.resolve("ravenroot/ravenroot-ui");
            if (Files.isRegularFile(nested.resolve("package.json"))) return nested;
            Path sibling = candidate.resolve("ravenroot-ui");
            if (Files.isRegularFile(sibling.resolve("package.json"))) return sibling;
            if (Files.isRegularFile(candidate.resolve("package.json"))
                    && "ravenroot-ui".equals(candidate.getFileName().toString())) return candidate;
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("cannot locate ravenroot-ui from " + Path.of("").toAbsolutePath());
    }
}
