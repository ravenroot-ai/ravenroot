package ai.ravenroot.server;

import ai.ravenroot.core.runner.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/** Standalone designated runner process; server/operator credentials are never accepted here. */
public final class RunnerWorkerMain {
    private RunnerWorkerMain() { }
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("usage: RunnerWorkerMain <operator-owned-config.json>");
        byte[] bytes;
        try (var input = Files.newInputStream(Path.of(arguments[0]).toRealPath())) { bytes = input.readNBytes(1_048_577); }
        var config = RunnerJson.read(bytes);
        if (!config.keySet().equals(Set.of("tenantId", "registration", "endpoint", "tokenFile", "docker", "stateDirectory", "runtimeImages"))) {
            throw new IllegalArgumentException("invalid standalone runner configuration");
        }
        var registration = RunnerJson.registration(RunnerJson.text(config, "tenantId"), RunnerJson.map(config.get("registration")));
        Path credential = Path.of(RunnerJson.text(config, "tokenFile")).toRealPath();
        var permissions = Files.getPosixFilePermissions(credential);
        if (permissions.stream().anyMatch(value -> value.name().startsWith("GROUP_") || value.name().startsWith("OTHERS_"))) {
            throw new IllegalArgumentException("runner workload credential file must be owner-only");
        }
        var client = new RemoteRunnerClient(URI.create(RunnerJson.text(config, "endpoint")), () -> {
            try (var input = Files.newInputStream(credential, LinkOption.NOFOLLOW_LINKS)) {
                byte[] token = input.readNBytes(4097);
                if (token.length > 4096) throw new IllegalArgumentException("runner credential too large");
                return new String(token, java.nio.charset.StandardCharsets.UTF_8).strip();
            } catch (java.io.IOException unavailable) { throw new IllegalStateException("runner credential unavailable"); }
        });
        var images = new LinkedHashMap<String, String>();
        RunnerJson.map(config.get("runtimeImages")).forEach((name, value) -> {
            if (!(value instanceof String image)) throw new IllegalArgumentException("runner image digest required");
            images.put(name, image);
        });
        try (var driver = new LocalContainerRunner(registration, Path.of(RunnerJson.text(config, "docker")),
                Path.of(RunnerJson.text(config, "stateDirectory")), images, Clock.systemUTC(), client::upload);
             var worker = new RunnerWorker(client, driver);
             var telemetry = ai.ravenroot.observability.otel.TelemetrySupport.install(
                     ai.ravenroot.observability.otel.TelemetryConfiguration.fromEnvironment(System.getenv()),
                     new ai.ravenroot.core.runtime.ExecutionMonitor(), null, worker.telemetry()).orElse(() -> { })) {
            if (registration.capabilities().capabilities().contains(ai.ravenroot.api.runner.RunnerPolicy.Capability.WORKSPACE_WRITE)) {
                for (String image : images.values()) driver.verifyWorkspaceQuota(image);
            }
            worker.start();
            Runtime.getRuntime().addShutdownHook(new Thread(worker::close, "runner-shutdown"));
            new CountDownLatch(1).await();
        }
    }
}
