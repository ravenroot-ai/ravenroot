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
