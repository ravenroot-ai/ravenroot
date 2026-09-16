package ai.ravenroot.api.runner;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.OpaquePayload;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class RunnerFixtures {
    static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");
    static final Duration TTL = Duration.ofSeconds(30);
    static final String TENANT = "tenant-a";
    static final String RUNNER = "runner-a";
    static final OpaquePayload EMPTY = OpaquePayload.of("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json");

    static RunnerPolicy policy() {
        return new RunnerPolicy(Set.of(RunnerPolicy.Capability.values()), Set.of("compiler", "search"),
                Set.of("registry"), Set.of("model-key"), Set.of("cache"),
                new RunnerPolicy.Limits(Duration.ofMinutes(10), 512_000_000, 8,
                        1_000_000, 10_000, 1_000, 512));
    }

    static AgentDefinition definition() {
        return definition(TENANT, "implement", false);
    }

    static AgentDefinition definition(String tenant, String command, boolean readOnly) {
        return new AgentDefinition(new AgentDefinition.Reference(tenant, "specialist", 1),
                "Approved agent instructions.", "runtime", "model", Map.of(command,
                new AgentCommand(command, readOnly, policy(), AgentCommand.STANDARD_OUTCOMES)),
                Set.of("development"), Set.of("sandboxed"), policy(), Duration.ofDays(7), "development-result");
    }

    static RunnerRegistration runner() {
        return new RunnerRegistration(1, TENANT, RUNNER, "sandboxed", Set.of("sandboxed"), policy());
    }

    static RunnerJobIdentity identity() {
        return identity(new ExecutionKey(TENANT, UUID.randomUUID()));
    }

    static RunnerJobIdentity identity(ExecutionKey execution) {
        return new RunnerJobIdentity(execution, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    static RunnerJob job() { return job(identity()); }

    static RunnerJob job(RunnerJobIdentity identity) {
        return RunnerJob.accept(identity, definition(), "implement", policy(), runner(), EMPTY,
                NOW, NOW.plusSeconds(600));
    }

    static RunnerResult result() { return result("continue"); }

    static RunnerResult result(String outcome) {
        return new RunnerResult(outcome, EMPTY, java.util.List.of(), UUID.randomUUID());
    }
}
