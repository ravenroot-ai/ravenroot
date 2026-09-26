package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.cli.remote.RemoteBackend;
import ai.ravenroot.core.deployment.DeploymentCoordinator;
import ai.ravenroot.core.deployment.DeploymentSingleFlight;
import ai.ravenroot.core.deployment.DurableLocalDeploymentControl;
import ai.ravenroot.core.deployment.ServiceShutdownIntent;
import ai.ravenroot.core.deployment.registry.InMemoryDeploymentRegistry;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Remote CLI proof for all four durable deployment commands and terminal cleanup. */
class RemoteDurableDeploymentsCliTest {
    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge source="start" target="end"/>
              </graph>
            </graphml>
            """;

    @Test
    void remoteCliUsesDurableGenerationMetadataAndParsesEveryLifecycleResult(@TempDir Path ui)
            throws Exception {
        var clock = Clock.systemUTC();
        try (var engine = new PekkoExecutionEngine("remote-durable-deployments-cli")) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                    BehaviorRegistry.standard(), new InMemoryArtifactRegistry(),
                    new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), null, 8);
            var registry = new InMemoryDeploymentRegistry(clock,
                    tenant -> DeploymentId.of(UUID.randomUUID().toString()));
            var coordinator = new DeploymentCoordinator(registry, application.localDeploymentTargets(),
                    new DeploymentSingleFlight(), ServiceShutdownIntent.RUNNING, "cli-owner",
                    Duration.ofMinutes(1), Duration.ofSeconds(1), clock);
            try (var server = new RavenrootServer(application,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), ui,
                    new DisabledLoopbackAuthenticator())) {
                server.installDurableDeploymentControl(
                        new DurableLocalDeploymentControl(application, registry, coordinator, clock));
                server.start();
                var backend = new RemoteBackend(URI.create("http://localhost:" + server.port() + "/"),
                        "test-token", Duration.ofSeconds(10));

                var registered = backend.registerDeployment("durable-cli",
                        GRAPH.getBytes(StandardCharsets.UTF_8));
                assertEquals(0L, registered.deploymentGeneration());
                var started = backend.startDeployment("durable-cli");
                assertEquals("READY", started.state());
                assertEquals(1L, started.deploymentGeneration());
                assertEquals("ACCEPTED", started.commandOutcome());

                var stopped = backend.stopDeployment("durable-cli", "maintenance");
                assertEquals("STOPPED", stopped.state());
                assertEquals(2L, stopped.deploymentGeneration());
                assertEquals("ACCEPTED", stopped.commandOutcome());

                var startedAgain = backend.startDeployment("durable-cli");
                assertEquals("READY", startedAgain.state());
                assertEquals(3L, startedAgain.deploymentGeneration());
                assertEquals("ACCEPTED", startedAgain.commandOutcome());

                var restarted = backend.restartDeployment("durable-cli");
                assertEquals("READY", restarted.state());
                assertEquals(4L, restarted.deploymentGeneration());
                assertEquals("ACCEPTED", restarted.commandOutcome());

                var removed = backend.undeployDeployment(
                        "durable-cli", "CANCEL_IN_FLIGHT", "retired");
                assertEquals("REMOVED", removed.state());
                assertEquals(5L, removed.deploymentGeneration());
                assertEquals("TERMINAL", removed.commandOutcome());
                assertTrue(backend.deployments().isEmpty());
                assertThrows(java.io.IOException.class, () -> backend.registerDeployment(
                        "durable-cli", GRAPH.getBytes(StandardCharsets.UTF_8)));
            }
        }
    }
}
