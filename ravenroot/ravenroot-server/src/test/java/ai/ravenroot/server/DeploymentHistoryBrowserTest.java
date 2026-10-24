package ai.ravenroot.server;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.security.*;
import ai.ravenroot.core.programming.*;
import ai.ravenroot.core.runtime.*;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.*;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Shipped native UI and HTTP history reads against API-seeded, then reopened, SQLite data. */
class DeploymentHistoryBrowserTest {
    private static final String GRAPH = """
        <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
        <key id="kind" for="node" attr.name="kind" attr.type="string"/>
        <key id="x" for="node" attr.name="layoutX" attr.type="double"/>
        <key id="y" for="node" attr.name="layoutY" attr.type="double"/>
        <key id="w" for="node" attr.name="layoutWidth" attr.type="double"/>
        <key id="h" for="node" attr.name="layoutHeight" attr.type="double"/>
        <key id="joins" for="graph" attr.name="join.semantics" attr.type="string"/>
        <graph id="history" edgedefault="directed"><data key="joins">declared</data>
        <node id="start"><data key="x">80</data><data key="y">100</data><data key="w">90</data><data key="h">60</data><data key="kind">START</data></node>
        <node id="worker"><data key="x">280</data><data key="y">100</data><data key="w">90</data><data key="h">60</data><data key="kind">PASSTHROUGH</data></node>
        <node id="end"><data key="x">480</data><data key="y">100</data><data key="w">90</data><data key="h">60</data><data key="kind">END</data></node>
        <node id="error"><data key="x">480</data><data key="y">250</data><data key="w">90</data><data key="h">60</data><data key="kind">ERROR</data></node>
        <edge id="route" source="start" target="worker"/>
        <edge id="loop" source="worker" target="worker"/>
        <edge id="finish" source="worker" target="end"/>
        </graph></graphml>
        """;
    @Test void thousandsOfHistoriesAndRepeatedVisitsStayBoundedAcrossActualHttpAndChromium() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("ravenroot.deploymentPersistence.browserTest"), "requires built UI and Chromium");
        Path ui = Path.of(System.getProperty("user.dir"), "../ravenroot-ui").toAbsolutePath().normalize();
        Path output = Files.createDirectories(Path.of("target", "history-monitor-browser", UUID.randomUUID().toString())).toAbsolutePath();
        var identity = new SecurityContext("history-browser", "local", "operator", PrincipalType.USER, "fixture");
        boolean seeded = false;
        for (int cap : new int[]{10, 1}) {
            var d = BusinessHistoryPolicy.DEFAULTS;
            var policy = new BusinessHistoryPolicy(d.maxHistoriesPerScope(), d.maxOpenJoinsPerHistory(), d.maxRetainedJoinsPerHistory(),
                    d.maxContributionsPerJoin(), d.maxJoinPayloadBytes(), cap, d.historyRetention(), d.joinDeadline(), d.selection(), d.maxJoinCasAttempts(), d.readTimeout(), d.response());
            try (var engine = new PekkoExecutionEngine("history-browser-" + cap);
                 var store = new SqliteExecutionStore(output.resolve("history.db"), Clock.systemUTC(), SqliteStoreConfig.defaults().withBusinessHistories(policy))) {
                var app = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), BehaviorRegistry.standard(), new InMemoryArtifactRegistry(),
                        new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), store, 8, UnknownBehaviorPolicy.passThrough());
                try {
                    app.registerLocalDeployment(identity, "history-browser", new java.io.ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)));
                    app.startLocalDeployment(identity, "history-browser").toCompletableFuture().get(10, TimeUnit.SECONDS);
                    var binding = app.localDeploymentView("local", "history-browser").orElseThrow().source();
                    if (!seeded) { seed(store, binding.graphVersion()); seeded = true; }
                    try (var registry = new SqliteDeploymentRegistry(output.resolve("deployments.db"), Clock.systemUTC())) {
                        var single = new ai.ravenroot.core.deployment.DeploymentSingleFlight();
                        var coordinator = new ai.ravenroot.core.deployment.DeploymentCoordinator(registry, app.localDeploymentTargets(), single,
                                ai.ravenroot.core.deployment.ServiceShutdownIntent.RUNNING, "history-host", Duration.ofMinutes(1), Duration.ofSeconds(1), Clock.systemUTC());
                        var reconciler = new ai.ravenroot.core.deployment.DeploymentReconciler(registry, app.localDeploymentTargets(), single, List.of("local"),
                                "history-host", Duration.ofMinutes(1), Duration.ofSeconds(1), 32, Clock.systemUTC());
                    int port; try (var socket = new java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
                    try (var control = new ai.ravenroot.core.deployment.DurableLocalDeploymentControl(app, registry, coordinator, reconciler, Duration.ofSeconds(1), Clock.systemUTC());
                         var server = new RavenrootServer(app, new InetSocketAddress(InetAddress.getLoopbackAddress(), port), ui.resolve("dist"), new DisabledLoopbackAuthenticator())) {
                        server.installDurableDeploymentControl(control); server.start();
                        var driver = new ProcessBuilder(ui.resolve("node_modules/.bin/playwright").toString(), "test", "history-monitor.spec.js", "--config=playwright.deployment-persistence.config.js")
                                .directory(ui.toFile()).redirectErrorStream(true).redirectOutput(output.resolve("playwright-cap-" + cap + ".log").toFile());
                        driver.environment().put("RAVENROOT_DEPLOYMENT_PERSISTENCE_ORIGIN", "http://127.0.0.1:" + server.port());
                        driver.environment().put("RAVENROOT_HISTORY_PAGE_CAP", Integer.toString(cap));
                        driver.environment().put("RAVENROOT_HISTORY_EVIDENCE_DIR", output.toString());
                        driver.environment().put("RAVENROOT_DEPLOYMENT_PERSISTENCE_OUTPUT_DIR", output.resolve("results-cap-" + cap).toString());
                        var browser = driver.start();
                        try { assertTrue(browser.waitFor(90, TimeUnit.SECONDS), "Chromium timeout: " + output);
                            assertEquals(0, browser.exitValue(), () -> read(output.resolve("playwright-cap-" + cap + ".log")));
                        } finally { if (browser.isAlive()) browser.destroyForcibly().waitFor(10, TimeUnit.SECONDS); }
                    }
                }
                } finally { app.close(); }
            }
        }
        System.out.println("history-browser evidence=" + output + " histories=2000 caps=10,1 reopenedSQLite=true");
    }
    private static void seed(SqliteExecutionStore store, String version) throws Exception {
        var scope = new BusinessScope("local", "history-browser", version);
        Instant now = Instant.now();
        for (int i = 0; i < 2000; i++) {
            UUID process = UUID.randomUUID(), traversal = i == 0 ? new UUID(0, 1) : UUID.randomUUID(), second = new UUID(0, 2);
            var visits = new LinkedHashMap<UUID, NodeInvocation>(); UUID parent = null;
            int count = i == 0 ? 1000 : 1;
            for (int visit = 0; visit < count; visit++) {
                UUID invocation = UUID.randomUUID(); visits.put(invocation, new NodeInvocation(invocation, "worker", parent == null ? Set.of() : Set.of(parent),
                        NodeInvocationStatus.COMPLETED, List.of(new NodeAttempt(UUID.randomUUID(), 1, NodeAttemptStatus.FAILED, null, null),
                        new NodeAttempt(UUID.randomUUID(), 2, NodeAttemptStatus.COMPLETED, NodeAttemptCompletion.SUCCEEDED, null)))); parent = invocation;
            }
            if (i == 0) { for (String node : List.of("start", "end")) { var value = completedNode(node); visits.put(value.invocationId(), value); } }
            var traversals = new LinkedHashMap<UUID, Traversal>(); traversals.put(traversal, new Traversal(traversal, "worker", TraversalStatus.COMPLETED, visits));
            if (i == 0) { var started = completedNode("start"); traversals.put(second, new Traversal(second, "start", TraversalStatus.COMPLETED, Map.of(started.invocationId(), started))); }
            var key = new ExecutionKey("local", process);
            var batch = ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent()).recordOrigin(ExecutionOrigin.of("history-browser", null, "transport-" + i))
                    .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(process, ProcessInstanceStatus.COMPLETED, traversals), new GraphVersionPin(version)));
            if (i == 0) for (int event = 0; event < 999; event++) batch.publish(EventEnvelope.of(UUID.randomUUID(), "local", "EDGE_TRAVERSED", process, traversal,
                    null, null, null, "edge-" + event, version, now, EdgeTraversalEventData.payload("loop")));
            if (i == 0) for (String edge : List.of("route", "finish")) batch.publish(EventEnvelope.of(UUID.randomUUID(), "local", "EDGE_TRAVERSED", process, traversal, null, null, null, "edge-" + edge, version, now, EdgeTraversalEventData.payload(edge)));
            store.apply(batch.build()).toCompletableFuture().get(10, TimeUnit.SECONDS);
            var context = new ApplicationCorrelationContext(String.format(Locale.ROOT, "operation-%04d", i), Optional.empty(), Optional.empty(), "round", Optional.of("same-file.csv"));
            store.recordCorrelatedTraversal(new CorrelatedTraversal(scope, context, process, traversal, "worker", CorrelatedTraversal.Cause.SOURCE_EVENT,
                    TraversalStatus.COMPLETED, null, now, now, now), CorrelationMapping.Mode.ACCEPT_OR_CREATE).toCompletableFuture().get(10, TimeUnit.SECONDS);
            store.settleCorrelatedTraversal("local", process, traversal, TraversalStatus.COMPLETED, null).toCompletableFuture().get(10, TimeUnit.SECONDS);
            if (i == 0) store.recordCorrelatedTraversal(new CorrelatedTraversal(scope, context, process, second, "start", CorrelatedTraversal.Cause.INITIAL_START,
                    TraversalStatus.COMPLETED, null, now.plusMillis(1), now.plusMillis(1), now.plusMillis(1)), CorrelationMapping.Mode.ACCEPT_OR_CREATE).toCompletableFuture().get(10, TimeUnit.SECONDS);
            if (i == 0) store.settleCorrelatedTraversal("local", process, second, TraversalStatus.COMPLETED, null).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
    private static NodeInvocation completedNode(String node) {
        UUID id = UUID.randomUUID(); return new NodeInvocation(id, node, Set.of(), NodeInvocationStatus.COMPLETED,
                List.of(new NodeAttempt(UUID.randomUUID(), 1, NodeAttemptStatus.COMPLETED, NodeAttemptCompletion.SUCCEEDED, null)));
    }
    private static String read(Path path) { try { return Files.readString(path); } catch (Exception failure) { return path.toString(); } }
}
