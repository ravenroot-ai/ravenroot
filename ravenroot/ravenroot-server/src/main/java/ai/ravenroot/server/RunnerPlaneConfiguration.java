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
                                List<AgentDefinition> definitions, List<RunnerRegistration> runners,
                                List<WorkspaceProfile> workspaceProfiles, RunnerControlConfiguration control) {
    RunnerPlaneConfiguration(String issuer, Path artifactDirectory, Map<String, RunnerPolicy> policies,
                             List<AgentDefinition> definitions, List<RunnerRegistration> runners) {
        this(issuer, artifactDirectory, policies, definitions, runners, List.of(), RunnerControlConfiguration.defaults());
    }
    RunnerPlaneConfiguration(String issuer, Path artifactDirectory, Map<String, RunnerPolicy> policies,
                             List<AgentDefinition> definitions, List<RunnerRegistration> runners,
                             List<WorkspaceProfile> workspaceProfiles) {
        this(issuer, artifactDirectory, policies, definitions, runners, workspaceProfiles, RunnerControlConfiguration.defaults());
    }
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
        if (!Set.of("protocolVersion", "runnerIssuer", "artifactDirectory", "tenants", "control").containsAll(root.keySet())
                || !root.keySet().containsAll(Set.of("protocolVersion", "runnerIssuer", "artifactDirectory", "tenants"))
                || RunnerJson.number(root, "protocolVersion") != 1) throw new IllegalArgumentException("invalid runner configuration");
        var policies = new LinkedHashMap<String, RunnerPolicy>();
        var definitions = new ArrayList<AgentDefinition>(); var runners = new ArrayList<RunnerRegistration>();
        var profiles = new ArrayList<WorkspaceProfile>();
        for (var entry : RunnerJson.map(root.get("tenants")).entrySet()) {
            var value = RunnerJson.map(entry.getValue());
            policies.put(entry.getKey(), RunnerJson.policy(RunnerJson.map(value.get("policy"))));
            if (!(value.get("definitions") instanceof List<?> agents) || !(value.get("runners") instanceof List<?> registrations)) {
                throw new IllegalArgumentException("runner configuration catalogs required");
            }
            for (var agent : agents) definitions.add(RunnerJson.definition(entry.getKey(), RunnerJson.map(agent)));
            for (var runner : registrations) runners.add(RunnerJson.registration(entry.getKey(), RunnerJson.map(runner)));
            Object configuredProfiles = value.getOrDefault("workspaceProfiles", List.of());
            if (!(configuredProfiles instanceof List<?> workspaceProfiles)) throw new IllegalArgumentException("workspace profile list required");
            for (var profile : workspaceProfiles) profiles.add(RunnerJson.workspaceProfile(entry.getKey(), RunnerJson.map(profile)));
        }
        if (policies.isEmpty()) throw new IllegalArgumentException("runner tenant configuration required");
        var defaults = RunnerControlConfiguration.defaults();
        var configuredControl = RunnerJson.map(root.getOrDefault("control", Map.of()));
        if (!Set.of("continuationThreads", "continuationQueue", "recoveryPageSize", "recoveryInterval", "continuationLease", "nodeTimeout")
                .containsAll(configuredControl.keySet())) throw new IllegalArgumentException("unknown runner control setting");
        var control = new RunnerControlConfiguration(
                integer(configuredControl, "continuationThreads", defaults.continuationThreads()),
                integer(configuredControl, "continuationQueue", defaults.continuationQueue()),
                integer(configuredControl, "recoveryPageSize", defaults.recoveryPageSize()),
                duration(configuredControl, "recoveryInterval", defaults.recoveryInterval()),
                duration(configuredControl, "continuationLease", defaults.continuationLease()),
                duration(configuredControl, "nodeTimeout", defaults.nodeTimeout()));
        return new RunnerPlaneConfiguration(RunnerJson.text(root, "runnerIssuer"), Path.of(RunnerJson.text(root, "artifactDirectory")),
                Map.copyOf(policies), List.copyOf(definitions), List.copyOf(runners), List.copyOf(profiles), control);
    }
    private static int integer(Map<String, Object> values, String key, int fallback) {
        return values.containsKey(key) ? Math.toIntExact(RunnerJson.number(values, key)) : fallback;
    }
    private static java.time.Duration duration(Map<String, Object> values, String key, java.time.Duration fallback) {
        return values.containsKey(key) ? java.time.Duration.parse(RunnerJson.text(values, key)) : fallback;
    }
    RunnerJobService service(ExecutionStore store, Clock clock) {
        if (store == null || !store.supports(StoreCapability.DURABLE)) {
            throw new IllegalArgumentException("server runner plane requires a durable execution store");
        }
        if (definitions.stream().anyMatch(definition -> definition.workspaceRetention().compareTo(store.terminalRetention()) >= 0)
                || workspaceProfiles.stream().anyMatch(profile -> profile.retention().compareTo(store.terminalRetention()) >= 0)) {
            throw new IllegalArgumentException("workspace retention must leave a cleanup window before process retention");
        }
        return new RunnerJobService(store, clock, definitions, runners, policies, workspaceProfiles, control);
    }
}
