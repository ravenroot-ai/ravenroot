package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.deployment.*;
import ai.ravenroot.core.programming.*;
import ai.ravenroot.core.runtime.*;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteDeploymentRegistry;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.List;

/** Child process: abrupt termination exercises persisted registration and initial admission custody. */
public final class FirstRunRestartBrowserFixture {
    private FirstRunRestartBrowserFixture() { }
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]); int port = Integer.parseInt(args[1]);
        var clock = Clock.systemUTC();
        try (var engine = new PekkoExecutionEngine("first-run-restart");
             var store = new SqliteExecutionStore(output.resolve("executions.db"), clock);
             var registry = new SqliteDeploymentRegistry(output.resolve("deployments.db"), clock)) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), BehaviorRegistry.standard(),
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(),
                    store, 8, UnknownBehaviorPolicy.passThrough());
            var single = new DeploymentSingleFlight();
            var coordinator = new DeploymentCoordinator(registry, application.localDeploymentTargets(), single,
                    ServiceShutdownIntent.RUNNING, "restart-browser-owner", Duration.ofMinutes(1), Duration.ofSeconds(1), clock);
            var reconciler = new DeploymentReconciler(registry, application.localDeploymentTargets(), single, List.of("local"),
                    "restart-browser-owner", Duration.ofMinutes(1), Duration.ofSeconds(1), 32, clock);
            try (var control = new DurableLocalDeploymentControl(application, registry, coordinator, reconciler, Duration.ofSeconds(1), clock);
                 var server = new RavenrootServer(application, new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                         Path.of(args[2]), new DisabledLoopbackAuthenticator())) {
                server.installDurableDeploymentControl(control);
                server.start();
                Files.writeString(output.resolve("ready-" + args[3]), Long.toString(ProcessHandle.current().pid()));
                System.in.read();
            } finally { application.close(); }
        }
    }
}
