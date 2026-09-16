package ai.ravenroot.server;

import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.runner.*;
import ai.ravenroot.core.runner.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

/** Explicit opt-in deployment authority. Configuration is local operator data, never graph input. */
record RunnerPlaneConfiguration(String issuer, Path artifactDirectory, Map<String, RunnerPolicy> policies,
                                List<AgentDefinition> definitions, List<RunnerRegistration> runners) {
    static RunnerPlaneConfiguration fromEnvironment(Map<String, String> environment) {
        try { return read(environment); }
        catch (IOException unavailable) { throw new IllegalArgumentException("runner configuration is unavailable", unavailable); }
    }
    RunnerArtifactStore artifacts() {
        try { return new RunnerArtifactStore(artifactDirectory); }
        catch (IOException unavailable) { throw new IllegalArgumentException("runner artifact volume is unavailable", unavailable); }
    }
    private static RunnerPlaneConfiguration read(Map<String, String> environment) throws IOException {
        String configured = environment.get("RAVENROOT_RUNNER_CONFIG");
        if (configured == null || configured.isBlank()) return null;
        Path path = Path.of(configured).toRealPath();
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(1_048_577); }
        if (bytes.length > 1_048_576) throw new IllegalArgumentException("runner configuration quota exceeded");
        var root = RunnerJson.read(bytes);
        if (!root.keySet().equals(Set.of("protocolVersion", "runnerIssuer", "artifactDirectory", "tenants"))
                || RunnerJson.number(root, "protocolVersion") != 1) throw new IllegalArgumentException("invalid runner configuration");
        var policies = new LinkedHashMap<String, RunnerPolicy>();
        var definitions = new ArrayList<AgentDefinition>(); var runners = new ArrayList<RunnerRegistration>();
        for (var entry : RunnerJson.map(root.get("tenants")).entrySet()) {
            var value = RunnerJson.map(entry.getValue());
            policies.put(entry.getKey(), RunnerJson.policy(RunnerJson.map(value.get("policy"))));
            if (!(value.get("definitions") instanceof List<?> agents) || !(value.get("runners") instanceof List<?> registrations)) {
                throw new IllegalArgumentException("runner configuration catalogs required");
            }
            for (var agent : agents) definitions.add(RunnerJson.definition(entry.getKey(), RunnerJson.map(agent)));
            for (var runner : registrations) runners.add(RunnerJson.registration(entry.getKey(), RunnerJson.map(runner)));
        }
        if (policies.isEmpty() || policies.size() > 256) throw new IllegalArgumentException("invalid runner tenant configuration");
        return new RunnerPlaneConfiguration(RunnerJson.text(root, "runnerIssuer"), Path.of(RunnerJson.text(root, "artifactDirectory")),
                Map.copyOf(policies), List.copyOf(definitions), List.copyOf(runners));
    }
    RunnerJobService service(ExecutionStore store, Clock clock) {
        if (store == null || !store.supports(StoreCapability.DURABLE)) {
            throw new IllegalArgumentException("server runner plane requires a durable execution store");
        }
        if (definitions.stream().anyMatch(definition -> definition.workspaceRetention().compareTo(store.terminalRetention()) >= 0)) {
            throw new IllegalArgumentException("workspace retention must leave a cleanup window before process retention");
        }
        return new RunnerJobService(store, clock, definitions, runners, policies);
    }
}
