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
        var keys = new HashSet<>(config.keySet()); keys.remove("worker"); keys.remove("agentRuntime");
        if (!keys.equals(Set.of("tenantId", "registration", "endpoint", "tokenFile", "docker", "stateDirectory", "runtimeImages"))) {
            throw new IllegalArgumentException("invalid standalone runner configuration");
        }
        var workerConfiguration = configuration(config.get("worker"));
        var registrationDocument = new LinkedHashMap<>(RunnerJson.map(config.get("registration")));
        String instance = System.getenv("RAVENROOT_RUNNER_INSTANCE");
        registrationDocument.put("runnerId", instanceValue(RunnerJson.text(registrationDocument, "runnerId"), instance));
        var registration = RunnerJson.registration(RunnerJson.text(config, "tenantId"), registrationDocument);
        Path credential = Path.of(instanceValue(RunnerJson.text(config, "tokenFile"), instance)).toRealPath();
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
        }, workerConfiguration);
        var images = new LinkedHashMap<String, String>();
        RunnerJson.map(config.get("runtimeImages")).forEach((name, value) -> {
            if (!(value instanceof String image)) throw new IllegalArgumentException("runner image digest required");
            images.put(name, image);
        });
        try (var driver = new LocalContainerRunner(registration, Path.of(RunnerJson.text(config, "docker")),
                Path.of(RunnerJson.text(config, "stateDirectory")), images, Clock.systemUTC(), client::upload, workerConfiguration);
             var worker = new RunnerWorker(client, driver, workerConfiguration);
             var telemetry = ai.ravenroot.observability.otel.TelemetrySupport.install(
                     ai.ravenroot.observability.otel.TelemetryConfiguration.fromEnvironment(System.getenv()),
                     new ai.ravenroot.core.runtime.ExecutionMonitor(), null, worker.telemetry()).orElse(() -> { })) {
            if (registration.capabilities().capabilities().contains(ai.ravenroot.api.runner.RunnerPolicy.Capability.WORKSPACE_WRITE)) {
                for (String image : images.values()) driver.verifyWorkspaceQuota(image);
            }
            if (config.containsKey("agentRuntime")) driver.withAgentRuntime(agentRuntime(RunnerJson.map(config.get("agentRuntime"))));
            worker.start();
            Runtime.getRuntime().addShutdownHook(new Thread(worker::close, "runner-shutdown"));
            new CountDownLatch(1).await();
        }
    }

    static RunnerAgentRuntime agentRuntime(Map<String, Object> value) {
        return RunnerAgentRuntime.fromConfiguration(value, new ai.ravenroot.core.security.EnvironmentCredentialResolver());
    }
    static String instanceValue(String configured, String instance) {
        if (!configured.contains("{instance}")) return configured;
        if (instance == null || !instance.matches("[a-z][a-z0-9._-]{0,63}"))
            throw new IllegalArgumentException("operator worker instance is required by configuration");
        return configured.replace("{instance}", instance);
    }

    static RunnerWorkerConfiguration configuration(Object value) {
        var defaults = RunnerWorkerConfiguration.defaults();
        if (value == null) return defaults;
        var properties = RunnerJson.map(value);
        if (!Set.of("maxConcurrentJobs", "pollInterval", "heartbeatInterval", "cleanupTimeout", "shutdownTimeout",
                "leaseTtl", "availabilityTtl", "httpConnectTimeout", "httpRequestTimeout", "maxResponseBytes",
                "driverCommandTimeout", "driverOutputTimeout", "maxSupervisorOutputBytes")
                .containsAll(properties.keySet())) throw new IllegalArgumentException("unknown worker setting");
        return new RunnerWorkerConfiguration(properties.containsKey("maxConcurrentJobs")
                ? Math.toIntExact(RunnerJson.number(properties, "maxConcurrentJobs")) : defaults.maxConcurrentJobs(),
                duration(properties, "pollInterval", defaults.pollInterval()),
                duration(properties, "heartbeatInterval", defaults.heartbeatInterval()),
                duration(properties, "cleanupTimeout", defaults.cleanupTimeout()),
                duration(properties, "shutdownTimeout", defaults.shutdownTimeout()),
                duration(properties, "leaseTtl", defaults.leaseTtl()), duration(properties, "availabilityTtl", defaults.availabilityTtl()),
                duration(properties, "httpConnectTimeout", defaults.httpConnectTimeout()), duration(properties, "httpRequestTimeout", defaults.httpRequestTimeout()),
                properties.containsKey("maxResponseBytes") ? Math.toIntExact(RunnerJson.number(properties, "maxResponseBytes")) : defaults.maxResponseBytes(),
                duration(properties, "driverCommandTimeout", defaults.driverCommandTimeout()),
                duration(properties, "driverOutputTimeout", defaults.driverOutputTimeout()),
                properties.containsKey("maxSupervisorOutputBytes") ? Math.toIntExact(RunnerJson.number(properties, "maxSupervisorOutputBytes")) : defaults.maxSupervisorOutputBytes());
    }
    private static java.time.Duration duration(Map<String, Object> value, String key, java.time.Duration fallback) {
        return value.containsKey(key) ? java.time.Duration.parse(RunnerJson.text(value, key)) : fallback;
    }
}
