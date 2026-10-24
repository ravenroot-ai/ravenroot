package ai.ravenroot.cli;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.history.*;
import ai.ravenroot.api.payload.*;
import ai.ravenroot.api.security.*;
import ai.ravenroot.core.programming.*;
import ai.ravenroot.core.runtime.*;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.server.RavenrootServer;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import ai.ravenroot.cli.remote.RemoteBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentHistoryCliParityTest {
    @TempDir Path directory;
    @Test void embeddedAndRemoteUseTheSameInitialIdentitiesAndHistoryPages() throws Exception {
        try (var engine = new PekkoExecutionEngine("history-cli");
             var store = new SqliteExecutionStore(directory.resolve("history.db"), Clock.systemUTC())) {
            var monitor = new ExecutionMonitor(); var ended = new CountDownLatch(1);
            monitor.subscribe(event -> { if (event.type() == ExecutionEventType.EXECUTION_COMPLETED) ended.countDown(); });
            var app = new DefaultRavenrootApplication(engine, monitor, new BehaviorRegistry(), new InMemoryArtifactRegistry(),
                    new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), store, 8, UnknownBehaviorPolicy.passThrough());
            var context = new RequestContext("request", "operator", PrincipalType.USER, "issuer", "local", Set.of(Role.PLATFORM_ADMIN),
                    Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available).map(AuthorizationAction::requiredScope).collect(java.util.stream.Collectors.toSet()));
            var authorized = new AuthorizedRavenrootApplication(app, new DefaultAuthorizationService(event -> {}), event -> {}, true);
            var embedded = new EmbeddedBackend(authorized, context);
            embedded.registerDeployment("document", GRAPH.getBytes(StandardCharsets.UTF_8)); embedded.startDeployment("document");
            var view = app.localDeploymentView("local", "document").orElseThrow().source();
            var source = new DeploymentHistorySource("document", view.incarnationId(), view.graphVersion());
            try (var server = new RavenrootServer(app, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, new DisabledLoopbackAuthenticator())) {
                server.start(); var remote = new RemoteBackend(URI.create("http://127.0.0.1:" + server.port()), "local-test", Duration.ofSeconds(10));
                var first = object(embedded.initialStart(source, "embedded", "{}".getBytes(StandardCharsets.UTF_8)));
                assertEquals(object(embedded.observeInitialStart(source, "observe")), object(remote.observeInitialStart(source, "observe")));
                var second = object(remote.initialStart(source, "remote", "{\"ignoredReplacement\":true}".getBytes(StandardCharsets.UTF_8)));
                assertEquals(first.entries().get("processInstanceId"), second.entries().get("processInstanceId"));
                assertEquals(first.entries().get("traversalId"), second.entries().get("traversalId"));
                assertEquals(PayloadValue.of("DUPLICATE"), second.entries().get("outcome"));
                assertTrue(ended.await(5, TimeUnit.SECONDS));
                String process = ((PayloadValue.TextValue) first.entries().get("processInstanceId")).value();
                String traversal = ((PayloadValue.TextValue) first.entries().get("traversalId")).value();
                var query = Map.of("view", "visits", "processInstanceId", process, "traversalId", traversal, "limit", "1");
                var page = embedded.deploymentHistory(source, query);
                assertEquals(object(page), object(remote.deploymentHistory(source, query)));
                String cursor = ((PayloadValue.TextValue) object(page).entries().get("nextCursor")).value();
                var nextQuery = new LinkedHashMap<>(query); nextQuery.put("cursor", cursor);
                assertEquals(object(embedded.deploymentHistory(source, nextQuery)), object(remote.deploymentHistory(source, nextQuery)));
                var output = new java.io.ByteArrayOutputStream(); var errors = new java.io.ByteArrayOutputStream();
                var cli = new RavenrootCli(remote, new java.io.PrintStream(output), new java.io.PrintStream(errors));
                assertEquals(0, cli.run("deployments", "history", "document", "--incarnation", source.incarnationId(),
                        "--graph-version", source.graphVersion(), "--view", "visits", "--process-instance-id", process,
                        "--traversal-id", traversal, "--limit", "1"), errors.toString(StandardCharsets.UTF_8));
                assertEquals(object(page), object(output.toString(StandardCharsets.UTF_8).strip()));
                output.reset();
                assertEquals(0, cli.run("deployments", "observe-initial", "document", "--incarnation", source.incarnationId(),
                        "--graph-version", source.graphVersion(), "--request-id", "cli-observe"), errors.toString(StandardCharsets.UTF_8));
                assertEquals(object(embedded.observeInitialStart(source, "cli-observe")), object(output.toString(StandardCharsets.UTF_8).strip()));
            }
        }
    }
    @Test void durableRestoreEmbeddedRemoteAndCliUseOnlyTheSameSavedRegistrationAuthority() throws Exception {
        try (var engine = new PekkoExecutionEngine("restore-cli");
             var registry = new ai.ravenroot.core.deployment.registry.InMemoryDeploymentRegistry(Clock.systemUTC(),
                     tenant -> ai.ravenroot.api.deployment.DeploymentId.of(UUID.randomUUID().toString()))) {
            var app = new DefaultRavenrootApplication(engine, new ExecutionMonitor());
            var context = new RequestContext("restore-cli", "operator", PrincipalType.USER, "issuer", "local", Set.of(Role.PLATFORM_ADMIN),
                    Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available).map(AuthorizationAction::requiredScope).collect(java.util.stream.Collectors.toSet()));
            var authorized = new AuthorizedRavenrootApplication(app, new DefaultAuthorizationService(event -> {}), event -> {}, true);
            var embedded = new EmbeddedBackend(authorized, context);
            var single = new ai.ravenroot.core.deployment.DeploymentSingleFlight();
            var coordinator = new ai.ravenroot.core.deployment.DeploymentCoordinator(registry, app.localDeploymentTargets(), single,
                    ai.ravenroot.core.deployment.ServiceShutdownIntent.RUNNING, "cli-owner", Duration.ofMinutes(1), Duration.ofSeconds(1), Clock.systemUTC());
            try (var control = new ai.ravenroot.core.deployment.DurableLocalDeploymentControl(app, registry, coordinator, Clock.systemUTC())) {
                var registered = control.register(SecurityContext.of(context), "restore", GRAPH.getBytes(StandardCharsets.UTF_8));
                String identity = registered.durable().deploymentId().value(), version = registered.local().graphVersion().orElseThrow();
                app.undeployLocalDeployment("local", "restore").toCompletableFuture().join();
                var restored = embedded.restoreDeployment("restore", identity, version);
                assertEquals(identity, restored.registrationIdentity());
                try (var server = new RavenrootServer(app, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, new DisabledLoopbackAuthenticator())) {
                    var install = RavenrootServer.class.getDeclaredMethod("installDurableDeploymentControl", ai.ravenroot.core.deployment.DurableLocalDeploymentControl.class);
                    install.setAccessible(true); install.invoke(server, control); server.start();
                    var remote = new RemoteBackend(URI.create("http://127.0.0.1:" + server.port()), "local-test", Duration.ofSeconds(10));
                    assertEquals(restored.registrationIdentity(), remote.restoreDeployment("restore", identity, version).registrationIdentity());
                    var output = new java.io.ByteArrayOutputStream(); var errors = new java.io.ByteArrayOutputStream();
                    var cli = new RavenrootCli(remote, new java.io.PrintStream(output), new java.io.PrintStream(errors));
                    assertEquals(0, cli.run("deployments", "restore", "restore", "--registration-identity", identity, "--graph-version", version), errors.toString(StandardCharsets.UTF_8));
                    assertTrue(output.toString(StandardCharsets.UTF_8).contains("registration-identity=" + identity));
                }
            } finally { app.close(); }
        }
    }

    @Test void embeddedRemoteAndCliPreviewUseSameBoundedSampleEvaluation() throws Exception {
        try (var engine = new PekkoExecutionEngine("preview-cli")) {
            var app = new DefaultRavenrootApplication(engine, new ExecutionMonitor());
            var context = new RequestContext("preview-request", "operator", PrincipalType.USER, "issuer", "local", Set.of(Role.PLATFORM_ADMIN),
                    Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available).map(AuthorizationAction::requiredScope).collect(java.util.stream.Collectors.toSet()));
            var authorized = new AuthorizedRavenrootApplication(app, new DefaultAuthorizationService(event -> {}), event -> {}, true);
            var embedded = new EmbeddedBackend(authorized, context);
            try (var server = new RavenrootServer(app, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, new DisabledLoopbackAuthenticator())) {
                server.start();
                var remote = new RemoteBackend(URI.create("http://127.0.0.1:" + server.port()), "local-test", Duration.ofSeconds(10));
                String sample = """
                        {"properties":{"correlation.mode":"ACCEPT_OR_CREATE","correlation.selector":"/metadata/ingestionId"},
                         "sample":{"metadata":{"ingestionId":"ingestion-42"}}}
                        """;
                byte[] request = sample.getBytes(StandardCharsets.UTF_8);
                assertEquals(object(embedded.previewCorrelation(request)), object(remote.previewCorrelation(request)));
                var file = directory.resolve("sample.json"); java.nio.file.Files.write(file, request);
                var output = new java.io.ByteArrayOutputStream(); var errors = new java.io.ByteArrayOutputStream();
                var cli = new RavenrootCli(remote, new java.io.PrintStream(output), new java.io.PrintStream(errors));
                assertEquals(0, cli.run("correlation-preview", file.toString()), errors.toString(StandardCharsets.UTF_8));
                assertEquals(object(embedded.previewCorrelation(request)), object(output.toString(StandardCharsets.UTF_8).strip()));
                output.reset();
                java.nio.file.Files.writeString(file, sample.replace("\"ingestion-42\"", "{\"secret\":\"do-not-echo\"}"));
                assertEquals(1, cli.run("correlation-preview", file.toString()));
                assertTrue(output.toString(StandardCharsets.UTF_8).contains("NOT_SCALAR_TEXT"));
                assertFalse(output.toString(StandardCharsets.UTF_8).contains("do-not-echo"));
            }
        }
    }
    private static PayloadValue.MapValue object(String json) { return (PayloadValue.MapValue) PayloadJson.read(json.getBytes(StandardCharsets.UTF_8), PayloadLimits.DEFAULTS); }
    private static final String GRAPH = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <graph id="history" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="end"><data key="kind">END</data></node>
                <node id="error"><data key="kind">ERROR</data></node>
                <edge source="start" target="end"/>
              </graph>
            </graphml>
            """;
}
