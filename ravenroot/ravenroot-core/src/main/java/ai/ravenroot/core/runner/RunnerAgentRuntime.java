package ai.ravenroot.core.runner;

import ai.ravenroot.api.runner.RunnerAssignment;
import java.time.Duration;
import java.util.*;

/** Trusted worker-side model broker and the finite budgets passed to the isolated Agent. */
public record RunnerAgentRuntime(int modelTurns, int toolCalls, long modelTokens, int tokensPerTurn,
                                 int protocolBytes, int toolOutputBytes, int listedFiles,
                                 List<String> testCommand, Map<String, String> skills,
                                 ModelGateway models) {
    @FunctionalInterface public interface ModelGateway {
        Map<String, Object> complete(RunnerAssignment assignment, Map<String, Object> request,
                                     int outputTokens, Duration timeout) throws Exception;
        /** Fence future model dispatch and cancel any pending transport for this job. */
        default void cancel(RunnerAssignment assignment) { }
        /** Fence every job using the graph resource, including ephemeral children. */
        default void cancelWorkspace(RunnerAssignment assignment) { }
        /** Drop transient stop bookkeeping only after matching retained ownership is released. */
        default void released(ai.ravenroot.api.runner.RunnerWorkspaceRelease release) { }
    }
    public RunnerAgentRuntime {
        if (modelTurns < 1 || toolCalls < 1 || modelTokens < 1 || tokensPerTurn < 1
                || protocolBytes < 1 || toolOutputBytes < 1 || listedFiles < 1) {
            throw new IllegalArgumentException("Agent runtime budgets must be positive");
        }
        testCommand = List.copyOf(testCommand); skills = Map.copyOf(skills); Objects.requireNonNull(models);
        if (testCommand.stream().anyMatch(value -> value.indexOf('\0') >= 0)) throw new IllegalArgumentException("invalid test argv");
    }
    public Map<String, Object> budgets(Duration remaining) {
        return Map.of("modelTurns", modelTurns, "toolCalls", toolCalls, "modelTokens", modelTokens,
                "tokensPerTurn", tokensPerTurn, "protocolBytes", protocolBytes, "toolOutputBytes", toolOutputBytes,
                "listedFiles", listedFiles, "wallTimeSeconds", Math.max(1, remaining.toSeconds()));
    }
    /** Definition ceilings can narrow, but never widen, the worker's operator-owned budgets. */
    public RunnerAgentRuntime boundedBy(ai.ravenroot.api.runner.AgentDefinition definition) {
        var limits = definition.budgets();
        var bodies = new LinkedHashMap<>(skills); bodies.putAll(definition.skillInstructions());
        return new RunnerAgentRuntime(Math.min(modelTurns, limits.modelTurns()), Math.min(toolCalls, limits.toolCalls()),
                Math.min(modelTokens, limits.modelTokens()), Math.min(tokensPerTurn, limits.tokensPerTurn()),
                protocolBytes, toolOutputBytes, listedFiles, testCommand, bodies, models);
    }
    /** The same operator configuration is used by the worker and native acceptance harness. */
    public static RunnerAgentRuntime fromConfiguration(Map<String, Object> value,
                                                        ai.ravenroot.api.security.SecretProvider secrets) {
        var profiles = new LinkedHashMap<String, RunnerModelGateway.Profile>();
        RunnerJson.map(value.get("models")).forEach((name, raw) -> {
            var profile = RunnerJson.map(raw);
            profiles.put(name, new RunnerModelGateway.Profile(java.net.URI.create(RunnerJson.text(profile, "endpoint")),
                    RunnerJson.text(profile, "model"), profile.containsKey("credentialReference") ? RunnerJson.text(profile, "credentialReference") : null,
                    Math.toIntExact(RunnerJson.number(profile, "maxConcurrency")), Math.toIntExact(RunnerJson.number(profile, "maxRequestBytes")),
                    Math.toIntExact(RunnerJson.number(profile, "maxResponseBytes"))));
        });
        var skills = new LinkedHashMap<String, String>();
        RunnerJson.map(value.get("skills")).forEach((name, body) -> {
            if (!(body instanceof String text)) throw new IllegalArgumentException("operator skill text required");
            skills.put(name, text);
        });
        if (!(value.get("testCommand") instanceof List<?> command) || command.stream().anyMatch(item -> !(item instanceof String)))
            throw new IllegalArgumentException("operator test argv required");
        return new RunnerAgentRuntime(Math.toIntExact(RunnerJson.number(value, "modelTurns")), Math.toIntExact(RunnerJson.number(value, "toolCalls")),
                RunnerJson.number(value, "modelTokens"), Math.toIntExact(RunnerJson.number(value, "tokensPerTurn")),
                Math.toIntExact(RunnerJson.number(value, "protocolBytes")), Math.toIntExact(RunnerJson.number(value, "toolOutputBytes")),
                Math.toIntExact(RunnerJson.number(value, "listedFiles")), command.stream().map(String.class::cast).toList(), skills,
                new RunnerModelGateway(profiles, secrets, Duration.parse(RunnerJson.text(value, "connectTimeout"))));
    }
}
