package ai.ravenroot.server;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real Chromium and four distinct JVMs sharing SQLite and one browser workspace. */
class FirstRunPersistenceBrowserTest {
    @Test void firstRunRebindsAcrossActualProcessCrashesAtAdmissionBoundaries() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("ravenroot.deploymentPersistence.browserTest"), "requires built UI and Playwright");
        Path ui = Path.of(System.getProperty("user.dir"), "../ravenroot-ui").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(ui.resolve("dist/index.html")));
        Path output = Files.createDirectories(Path.of("target", "first-run-persistence-browser", UUID.randomUUID().toString())).toAbsolutePath();
        int port;
        try (var socket = new java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        Process server = null, browser = null;
        var pids = new HashSet<Long>();
        try (var changes = output.getFileSystem().newWatchService()) {
            output.register(changes, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            server = startServer(output, port, ui, 0); pids.add(server.pid());
            awaitFile(output.resolve("ready-0"), changes, server);
            var driver = new ProcessBuilder(ui.resolve("node_modules/.bin/playwright").toString(), "test", "first-run-restart.spec.js",
                    "--config=playwright.deployment-persistence.config.js").directory(ui.toFile()).redirectErrorStream(true)
                    .redirectOutput(output.resolve("playwright.log").toFile());
            driver.environment().put("RAVENROOT_DEPLOYMENT_PERSISTENCE_ORIGIN", "http://127.0.0.1:" + port);
            driver.environment().put("RAVENROOT_DEPLOYMENT_PERSISTENCE_OUTPUT_DIR", output.resolve("playwright-results").toString());
            driver.environment().put("RAVENROOT_FIRST_RUN_RESTART_DIR", output.toString());
            browser = driver.start();
            for (int round = 0; round < 3; round++) {
                awaitFile(output.resolve("restart-" + round), changes, browser);
                server.destroyForcibly(); assertTrue(server.waitFor(10, TimeUnit.SECONDS));
                server = startServer(output, port, ui, round + 1); pids.add(server.pid());
                awaitFile(output.resolve("ready-" + (round + 1)), changes, server);
            }
            assertTrue(browser.waitFor(90, TimeUnit.SECONDS), "browser timeout: " + output);
            assertEquals(0, browser.exitValue(), () -> read(output.resolve("playwright.log")));
            assertEquals(4, pids.size(), "every restart must be a different JVM");
        } finally {
            if (browser != null && browser.isAlive()) browser.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            if (server != null && server.isAlive()) server.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }
    @Test void firstTraversalHasConfirmedObservationEvenWithoutDrawing() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("ravenroot.deploymentPersistence.browserTest"), "requires built UI and Playwright");
        Path ui = Path.of(System.getProperty("user.dir"), "../ravenroot-ui").toAbsolutePath().normalize();
        Path output = Files.createDirectories(Path.of("target", "observation-admission-browser", UUID.randomUUID().toString())).toAbsolutePath();
        var drawingUnavailable = new java.util.concurrent.atomic.AtomicBoolean();
        var observationDenied = new java.util.concurrent.atomic.AtomicBoolean();
        var control = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        control.createContext("/drawing", exchange -> {
            drawingUnavailable.set(exchange.getRequestURI().getQuery().equals("unavailable"));
            exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        control.createContext("/observation", exchange -> {
            observationDenied.set(exchange.getRequestURI().getQuery().equals("denied"));
            exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        try (var engine = new ai.ravenroot.pekko.PekkoExecutionEngine("observation-admission");
             var store = new ai.ravenroot.persistence.sqlite.SqliteExecutionStore(output.resolve("execution.db"), java.time.Clock.systemUTC())) {
            var application = new ai.ravenroot.core.runtime.DefaultRavenrootApplication(engine,
                    new ai.ravenroot.core.runtime.ExecutionMonitor(), ai.ravenroot.core.runtime.BehaviorRegistry.standard(),
                    new ai.ravenroot.core.programming.InMemoryArtifactRegistry(), new ai.ravenroot.core.programming.DisabledProgramRuntime(),
                    ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store, 8, ai.ravenroot.core.runtime.UnknownBehaviorPolicy.passThrough());
            var observed = (ai.ravenroot.api.application.RavenrootApplication) java.lang.reflect.Proxy.newProxyInstance(
                    application.getClass().getClassLoader(), new Class<?>[]{ai.ravenroot.api.application.RavenrootApplication.class},
                    (proxy, method, arguments) -> {
                        if (drawingUnavailable.get() && method.getName().equals("localDeploymentView"))
                            throw new ai.ravenroot.api.application.DeploymentViewException(
                                    ai.ravenroot.api.application.DeploymentViewException.Reason.PROJECTION_UNAVAILABLE);
                        try { return method.invoke(application, arguments); }
                        catch (java.lang.reflect.InvocationTargetException reflected) { throw reflected.getCause(); }
                    });
            int observationPort;
            try (var socket = new java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { observationPort = socket.getLocalPort(); }
            try (var server = new RavenrootServer(observed, new InetSocketAddress(InetAddress.getLoopbackAddress(), observationPort), ui.resolve("dist"),
                    headers -> new ai.ravenroot.server.security.AuthenticatedPrincipal("observation-browser",
                            ai.ravenroot.server.security.AuthenticatedPrincipal.Type.USER, "fixture", "local",
                            Set.of(ai.ravenroot.api.security.Role.OPERATOR), Arrays.stream(ai.ravenroot.api.security.AuthorizationAction.values())
                                    .filter(ai.ravenroot.api.security.AuthorizationAction::available)
                                    .filter(action -> !observationDenied.get() || action != ai.ravenroot.api.security.AuthorizationAction.DEPLOYMENT_OBSERVE)
                                    .map(ai.ravenroot.api.security.AuthorizationAction::requiredScope).collect(java.util.stream.Collectors.toSet())))) {
                server.start(); control.start();
                var driver = new ProcessBuilder(ui.resolve("node_modules/.bin/playwright").toString(), "test", "observation-admission.spec.js",
                        "--config=playwright.deployment-persistence.config.js").directory(ui.toFile()).redirectErrorStream(true)
                        .redirectOutput(output.resolve("playwright.log").toFile());
                driver.environment().put("RAVENROOT_DEPLOYMENT_PERSISTENCE_ORIGIN", "http://127.0.0.1:" + server.port());
                driver.environment().put("RAVENROOT_DEPLOYMENT_PERSISTENCE_OUTPUT_DIR", output.resolve("playwright-results").toString());
                driver.environment().put("RAVENROOT_OBSERVATION_CONTROL_ORIGIN", "http://127.0.0.1:" + control.getAddress().getPort());
                driver.environment().put("RAVENROOT_OBSERVATION_EVIDENCE_DIR", output.toString());
                Process browser = driver.start();
                try {
                    assertTrue(browser.waitFor(180, TimeUnit.SECONDS), "observation browser timeout: " + output);
                    assertEquals(0, browser.exitValue(), () -> read(output.resolve("playwright.log")));
                } finally { if (browser.isAlive()) browser.destroyForcibly().waitFor(10, TimeUnit.SECONDS); }
            } finally { application.close(); }
        } finally { control.stop(0); }
        System.out.println("observation-admission evidence=" + output);
    }

    @Test void sdkRestoreRegistersPeriodicRecoveryWithoutHttpSerialization() throws Exception {
        Path output = Files.createTempDirectory("sdk-restore-recovery");
        var clock = java.time.Clock.systemUTC();
        try (var engine = new ai.ravenroot.pekko.PekkoExecutionEngine("sdk-restore-recovery");
             var actual = new ai.ravenroot.persistence.sqlite.SqliteDeploymentRegistry(output.resolve("registry.db"), clock)) {
            var observed = new java.util.concurrent.CountDownLatch(1); var armed = new java.util.concurrent.atomic.AtomicBoolean();
            var registry = (ai.ravenroot.api.deployment.registry.DeploymentRegistry) java.lang.reflect.Proxy.newProxyInstance(
                    actual.getClass().getClassLoader(), new Class<?>[]{ai.ravenroot.api.deployment.registry.DeploymentRegistry.class},
                    (proxy, method, arguments) -> {
                        if (armed.get() && method.getName().equals("get")) observed.countDown();
                        try { return method.invoke(actual, arguments); } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    });
            var app = new ai.ravenroot.core.runtime.DefaultRavenrootApplication(engine, new ai.ravenroot.core.runtime.ExecutionMonitor());
            var single = new ai.ravenroot.core.deployment.DeploymentSingleFlight();
            var coordinator = new ai.ravenroot.core.deployment.DeploymentCoordinator(registry, app.localDeploymentTargets(), single,
                    ai.ravenroot.core.deployment.ServiceShutdownIntent.RUNNING, "sdk-host", Duration.ofMinutes(1), Duration.ofSeconds(1), clock);
            var reconciler = new ai.ravenroot.core.deployment.DeploymentReconciler(registry, app.localDeploymentTargets(), single, List.of(),
                    "sdk-host", Duration.ofMinutes(1), Duration.ofSeconds(1), 32, clock);
            var identity = new ai.ravenroot.api.security.SecurityContext("sdk", "local", "operator", ai.ravenroot.api.security.PrincipalType.USER, "fixture");
            String registryId, graphVersion;
            try (var first = new ai.ravenroot.core.deployment.DurableLocalDeploymentControl(app, registry, coordinator, clock)) {
                var registered = first.register(identity, "sdk-only", "<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\"><key id=\"k\" for=\"node\" attr.name=\"kind\" attr.type=\"string\"/><graph id=\"g\" edgedefault=\"directed\"><node id=\"start\"><data key=\"k\">START</data></node><node id=\"end\"><data key=\"k\">END</data></node><edge source=\"start\" target=\"end\"/></graph></graphml>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                registryId = registered.durable().deploymentId().value(); graphVersion = registered.local().graphVersion().orElseThrow();
                coordinator.submit("local", registered.durable().deploymentId(), new ai.ravenroot.api.deployment.lifecycle.LifecycleCommand.Start("activate", 1,
                        ai.ravenroot.api.deployment.registry.DeploymentRegistry.UpdateStrategy.STOP_FIRST), ai.ravenroot.api.deployment.registry.GenerationExpectation.exactly(0));
            }
            app.undeployLocalDeployment("local", "sdk-only").toCompletableFuture().join();
            try (var second = new ai.ravenroot.core.deployment.DurableLocalDeploymentControl(app, registry, coordinator, reconciler, Duration.ofMillis(10), clock)) {
                assertEquals("READY", app.restoreLocalDeployment(identity, "sdk-only", registryId, graphVersion).orElseThrow().state().name());
                armed.set(true); second.startRecovery();
                assertTrue(observed.await(5, TimeUnit.SECONDS), "SDK-only restored alias must join periodic recovery without HTTP status serialization");
            } finally { app.close(); }
        }
    }

    private static Process startServer(Path output, int port, Path ui, int round) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                System.getProperty("java.class.path"), FirstRunRestartBrowserFixture.class.getName(), output.toString(),
                Integer.toString(port), ui.resolve("dist").toString(), Integer.toString(round))
                .redirectErrorStream(true).redirectOutput(output.resolve("server-" + round + ".log").toFile()).start();
    }
    private static void awaitFile(Path expected, WatchService changes, Process process) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!Files.exists(expected)) {
            assertTrue(process.isAlive(), () -> "process exited before " + expected + ": " + read(expected.getParent().resolve("playwright.log")));
            long remaining = deadline - System.nanoTime();
            assertTrue(remaining > 0, "timeout waiting for " + expected);
            var key = changes.poll(remaining, TimeUnit.NANOSECONDS);
            assertNotNull(key, "timeout waiting for " + expected); key.pollEvents(); key.reset();
        }
    }
    private static String read(Path path) { try { return Files.readString(path); } catch (Exception failure) { return path.toString(); } }
}
