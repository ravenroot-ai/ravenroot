package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.ExecutionEnginePolicy;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Operator-owned engine and graph-runner bounds resolved once during runtime composition. */
public record ExecutionRuntimeConfiguration(
        ExecutionEnginePolicy enginePolicy,
        Duration runnerShutdownStepBound) {

    public static final String MAX_STASHED_COMMANDS_PER_NODE_VARIABLE =
            "RAVENROOT_ENGINE_MAX_STASHED_COMMANDS_PER_NODE";
    public static final String LIFECYCLE_STEP_SECONDS_VARIABLE =
            "RAVENROOT_ENGINE_LIFECYCLE_STEP_SECONDS";
    public static final String TERMINAL_HISTORY_CAPACITY_VARIABLE =
            "RAVENROOT_ENGINE_TERMINAL_HISTORY_CAPACITY";
    public static final String RUNNER_SHUTDOWN_STEP_SECONDS_VARIABLE =
            "RAVENROOT_GRAPH_RUNNER_SHUTDOWN_STEP_SECONDS";

    private static final int MAX_STASHED_COMMANDS_PER_NODE =
            ExecutionEnginePolicy.FROZEN_LEGACY.maxStashedCommandsPerNode();
    private static final int MAX_LIFECYCLE_STEP_SECONDS = Math.toIntExact(
            ExecutionEnginePolicy.FROZEN_LEGACY.lifecycleStepBound().getSeconds());
    private static final int MAX_TERMINAL_HISTORY_CAPACITY =
            ExecutionEnginePolicy.FROZEN_LEGACY.terminalNodeHistoryCapacity();
    private static final int MAX_RUNNER_SHUTDOWN_STEP_SECONDS = 10;

    /** The effective configuration when every operator binding is absent or blank. */
    public static final ExecutionRuntimeConfiguration DEFAULTS = new ExecutionRuntimeConfiguration(
            ExecutionEnginePolicy.FROZEN_LEGACY,
            Duration.ofSeconds(MAX_RUNNER_SHUTDOWN_STEP_SECONDS));

    /** Preserves the API policy's positive-value freedom for direct Java composition. */
    public ExecutionRuntimeConfiguration {
        Objects.requireNonNull(enginePolicy, "enginePolicy");
        Objects.requireNonNull(runnerShutdownStepBound, "runnerShutdownStepBound");
        if (runnerShutdownStepBound.isZero() || runnerShutdownStepBound.isNegative()) {
            throw new IllegalArgumentException("runnerShutdownStepBound must be positive");
        }
    }

    /** Parses only the supplied map; core never reads ambient process configuration. */
    public static ExecutionRuntimeConfiguration fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        int maxStashedCommands = integer(environment, MAX_STASHED_COMMANDS_PER_NODE_VARIABLE,
                DEFAULTS.enginePolicy.maxStashedCommandsPerNode(), MAX_STASHED_COMMANDS_PER_NODE);
        int lifecycleStepSeconds = integer(environment, LIFECYCLE_STEP_SECONDS_VARIABLE,
                Math.toIntExact(DEFAULTS.enginePolicy.lifecycleStepBound().getSeconds()),
                MAX_LIFECYCLE_STEP_SECONDS);
        int terminalHistoryCapacity = integer(environment, TERMINAL_HISTORY_CAPACITY_VARIABLE,
                DEFAULTS.enginePolicy.terminalNodeHistoryCapacity(), MAX_TERMINAL_HISTORY_CAPACITY);
        int runnerShutdownStepSeconds = integer(environment, RUNNER_SHUTDOWN_STEP_SECONDS_VARIABLE,
                Math.toIntExact(DEFAULTS.runnerShutdownStepBound.getSeconds()),
                MAX_RUNNER_SHUTDOWN_STEP_SECONDS);

        return new ExecutionRuntimeConfiguration(
                new ExecutionEnginePolicy(maxStashedCommands, Duration.ofSeconds(lifecycleStepSeconds),
                        terminalHistoryCapacity),
                Duration.ofSeconds(runnerShutdownStepSeconds));
    }

    private static int integer(Map<String, String> environment, String name, int fallback, int maximum) {
        String raw = environment.get(name);
        if (raw == null || raw.isBlank()) return fallback;
        String normalized = raw.strip();
        if (normalized.isEmpty() || normalized.chars().anyMatch(character -> character < '0' || character > '9')) {
            throw invalid(name, maximum);
        }
        int value;
        try {
            value = Integer.parseInt(normalized);
        } catch (NumberFormatException invalid) {
            throw invalid(name, maximum);
        }
        if (value < 1 || value > maximum) throw invalid(name, maximum);
        return value;
    }

    private static IllegalArgumentException invalid(String name, int maximum) {
        return new IllegalArgumentException(name + " must be a whole number from 1 through " + maximum);
    }
}
