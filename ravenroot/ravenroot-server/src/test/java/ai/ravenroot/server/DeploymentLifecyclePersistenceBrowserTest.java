package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.deployment.lifecycle.LifecycleCommand;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.GenerationExpectation;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.deployment.DeploymentCoordinator;
import ai.ravenroot.core.deployment.DeploymentSingleFlight;
import ai.ravenroot.core.deployment.DurableLocalDeploymentControl;
import ai.ravenroot.core.deployment.ServiceShutdownIntent;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.UnknownBehaviorPolicy;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteDeploymentRegistry;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real browser/JVM/SQLite proof for durable deployment controls in the shipped Deployments window. */
class DeploymentLifecyclePersistenceBrowserTest {
    private static final byte[] GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge source="start" target="end"/>
              </graph>
            </graphml>
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final SecurityContext OPERATOR = new SecurityContext(
            "deployment-restart-proof", "local", "operator", PrincipalType.USER, "test");

    @Test
    void browserReproducesLegacyStopThenCompletesDurableLifecycleAgainstSqlite() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("ravenroot.deploymentPersistence.browserTest"),
                "run scripts/verify-deployment-persistence-browser.sh for the real browser proof");
        Path ui = Path.of(System.getProperty("user.dir")).resolve("../ravenroot-ui")
                .toAbsolutePath().normalize();
        Path distribution = ui.resolve("dist");
        assertTrue(Files.isRegularFile(distribution.resolve("index.html")),
                "the built editor is required; run npm run build in ravenroot-ui first");
        Path output = Files.createDirectories(Path.of(System.getProperty("user.dir"), "target",
                "deployment-persistence-browser", UUID.randomUUID().toString()));
        Path database = output.resolve("deployment.db");

        try (var engine = new PekkoExecutionEngine("deployment-persistence-browser");
             var registry = new SqliteDeploymentRegistry(database, Clock.systemUTC())) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                    BehaviorRegistry.standard(), new InMemoryArtifactRegistry(),
                    new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), null, 8,
                    UnknownBehaviorPolicy.passThrough());
            var clock = Clock.systemUTC();
            var coordinator = new DeploymentCoordinator(registry, application.localDeploymentTargets(),
                    new DeploymentSingleFlight(), ServiceShutdownIntent.RUNNING, "browser-owner",
                    Duration.ofMinutes(1), Duration.ofSeconds(1), clock);
            try (var server = new RavenrootServer(application,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), freePort()), distribution,
                    new DisabledLoopbackAuthenticator())) {
                server.installDurableDeploymentControl(
                        new DurableLocalDeploymentControl(application, registry, coordinator, clock));
                server.start();
                runBrowser(ui, output, "http://127.0.0.1:" + server.port());
            }
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM deployment WHERE generation = 5 AND tombstone_reason = ?")) {
            statement.setString(1, "browser persistence proof");
            try (var rows = statement.executeQuery()) {
                assertEquals(1, rows.getInt(1),
                        "the UI's terminal Undeploy must remain recorded in SQLite");
            }
        }
    }

    @Test
    void durableAliasesRecoverCurrentIntentAndRefuseTombstoneAfterFullRestartAndLedgerPurge()
            throws Exception {
        Path database = Files.createTempFile("ravenroot-deployment-alias", ".db");
        Clock clock = Clock.systemUTC();
        String liveIdentity;
        try (var engine = new PekkoExecutionEngine("deployment-alias-before-restart");
             var registry = new SqliteDeploymentRegistry(database, clock,
                     tenant -> ai.ravenroot.api.deployment.DeploymentId.of(UUID.randomUUID().toString()),
                     Duration.ofNanos(1))) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                    BehaviorRegistry.standard(), new InMemoryArtifactRegistry(),
                    new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), null, 8,
                    UnknownBehaviorPolicy.passThrough());
            var coordinator = new DeploymentCoordinator(registry, application.localDeploymentTargets(),
                    new DeploymentSingleFlight(), ServiceShutdownIntent.RUNNING, "alias-owner-before",
                    Duration.ofMinutes(1), Duration.ofSeconds(1), clock);
            try (var control = new DurableLocalDeploymentControl(application, registry, coordinator, clock);
                 var server = new RavenrootServer(application,
                         new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), Files.createTempDirectory("ui"),
                         new DisabledLoopbackAuthenticator())) {
                server.installDurableDeploymentControl(control);
                server.start();
                var live = control.register(OPERATOR, "live-after-restart", GRAPH);
                liveIdentity = live.durable().deploymentId().value();
                control.register(OPERATOR, "removed-after-restart", GRAPH);
                assertEquals(2, registry.purgeExpiredCommandRecords("local").toCompletableFuture().join(),
                        "the safety proof must actually remove both CREATE ledger rows");

                control.submit("local", "live-after-restart",
                        new LifecycleCommand.Start("start-live", 1,
                                DeploymentRegistry.UpdateStrategy.STOP_FIRST),
                        GenerationExpectation.exactly(0)).orElseThrow();
                control.submit("local", "removed-after-restart",
                        new LifecycleCommand.Start("start-removed", 1,
                                DeploymentRegistry.UpdateStrategy.STOP_FIRST),
                        GenerationExpectation.exactly(0)).orElseThrow();
                control.submit("local", "removed-after-restart",
                        new LifecycleCommand.Undeploy("remove",
                                LifecycleCommand.Undeploy.Disposition.CANCEL_IN_FLIGHT, "retired"),
                        GenerationExpectation.exactly(1)).orElseThrow();
            } finally {
                application.close();
            }
        }

        try (var engine = new PekkoExecutionEngine("deployment-alias-after-restart");
             var registry = new SqliteDeploymentRegistry(database, clock)) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                    BehaviorRegistry.standard(), new InMemoryArtifactRegistry(),
                    new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), null, 8,
                    UnknownBehaviorPolicy.passThrough());
            var coordinator = new DeploymentCoordinator(registry, application.localDeploymentTargets(),
                    new DeploymentSingleFlight(), ServiceShutdownIntent.RUNNING, "alias-owner-after",
                    Duration.ofMinutes(1), Duration.ofSeconds(1), clock);
            try (var control = new DurableLocalDeploymentControl(application, registry, coordinator, clock)) {
                var recovered = control.register(OPERATOR, "live-after-restart", GRAPH);
                assertEquals(liveIdentity, recovered.durable().deploymentId().value());
                assertEquals(1, recovered.durable().generation());
                assertEquals(DeploymentRegistry.DesiredKind.RUNNING, recovered.durable().desired().kind());

                assertThrows(IllegalStateException.class,
                        () -> control.register(OPERATOR, "removed-after-restart", GRAPH));
                assertTrue(application.localDeployment("local", "removed-after-restart").isEmpty(),
                        "a tombstoned alias must be refused before any process-local runtime is published");
                var replay = control.submit("local", "removed-after-restart",
                        new LifecycleCommand.Undeploy("remove",
                                LifecycleCommand.Undeploy.Disposition.CANCEL_IN_FLIGHT, "retired"),
                        GenerationExpectation.exactly(1)).orElseThrow();
                assertTrue(replay instanceof ai.ravenroot.api.deployment.lifecycle.DeploymentCommandOutcome.Replayed,
                        "the retained identity keeps exact terminal replay reachable after restart");
            } finally {
                application.close();
            }
        } finally {
            Files.deleteIfExists(database);
        }
    }

    private static int freePort() throws IOException {
        try (var socket = new java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void runBrowser(Path ui, Path output, String origin) throws Exception {
        Path executable = ui.resolve("node_modules/.bin/playwright");
        if (!Files.isExecutable(executable)) {
            throw new IllegalStateException("the installed Playwright binary is required for the real harness");
        }
        var builder = new ProcessBuilder(executable.toString(), "test",
                "--config=playwright.deployment-persistence.config.js")
                .directory(ui.toFile()).redirectErrorStream(true)
                .redirectOutput(output.resolve("playwright-driver.log").toFile());
        builder.environment().put("RAVENROOT_DEPLOYMENT_PERSISTENCE_ORIGIN", origin);
        builder.environment().put("RAVENROOT_DEPLOYMENT_PERSISTENCE_OUTPUT_DIR",
                Files.createDirectories(output.resolve("playwright")).toString());
        Process process = builder.start();
        if (!process.waitFor(4, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("the browser lifecycle did not finish; see " + output);
        }
        assertEquals(0, process.exitValue(), () -> {
            try {
                return "the browser lifecycle failed; driver log:\n"
                        + Files.readString(output.resolve("playwright-driver.log"));
            } catch (IOException unreadable) {
                return "the browser lifecycle failed and its log is unreadable at " + output;
            }
        });
    }
}
