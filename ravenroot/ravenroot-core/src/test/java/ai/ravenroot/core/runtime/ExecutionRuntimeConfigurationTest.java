package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.ExecutionEnginePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionRuntimeConfigurationTest {

    @Test
    void absentAndJavaBlankBindingsPreserveTheFrozenRuntimeValues() {
        assertEquals(ExecutionEnginePolicy.FROZEN_LEGACY,
                ExecutionRuntimeConfiguration.DEFAULTS.enginePolicy());
        assertEquals(Duration.ofSeconds(10),
                ExecutionRuntimeConfiguration.DEFAULTS.runnerShutdownStepBound());
        assertEquals(ExecutionRuntimeConfiguration.DEFAULTS,
                ExecutionRuntimeConfiguration.fromEnvironment(Map.of()));
        assertEquals(ExecutionRuntimeConfiguration.DEFAULTS,
                ExecutionRuntimeConfiguration.fromEnvironment(Map.of(
                        ExecutionRuntimeConfiguration.MAX_STASHED_COMMANDS_PER_NODE_VARIABLE, " \t\u2003 ",
                        ExecutionRuntimeConfiguration.LIFECYCLE_STEP_SECONDS_VARIABLE, "",
                        ExecutionRuntimeConfiguration.TERMINAL_HISTORY_CAPACITY_VARIABLE, " \n ",
                        ExecutionRuntimeConfiguration.RUNNER_SHUTDOWN_STEP_SECONDS_VARIABLE, "\u2003")));
    }

    @Test
    void eachBindingMapsToItsIndependentPolicyComponent() {
        ExecutionRuntimeConfiguration configured = ExecutionRuntimeConfiguration.fromEnvironment(Map.of(
                ExecutionRuntimeConfiguration.MAX_STASHED_COMMANDS_PER_NODE_VARIABLE, " 7 ",
                ExecutionRuntimeConfiguration.LIFECYCLE_STEP_SECONDS_VARIABLE, " 3 ",
                ExecutionRuntimeConfiguration.TERMINAL_HISTORY_CAPACITY_VARIABLE, " 5 ",
                ExecutionRuntimeConfiguration.RUNNER_SHUTDOWN_STEP_SECONDS_VARIABLE, " 2 "));

        assertEquals(7, configured.enginePolicy().maxStashedCommandsPerNode());
        assertEquals(Duration.ofSeconds(3), configured.enginePolicy().lifecycleStepBound());
        assertEquals(5, configured.enginePolicy().terminalNodeHistoryCapacity());
        assertEquals(Duration.ofSeconds(2), configured.runnerShutdownStepBound());
    }

    @ParameterizedTest
    @MethodSource("bindings")
    void everyBindingAcceptsItsMinimumAndExactDeploymentMaximum(
            String name, int maximum, ToIntFunction<ExecutionRuntimeConfiguration> value) {
        assertEquals(1, value.applyAsInt(ExecutionRuntimeConfiguration.fromEnvironment(Map.of(name, "1"))));
        assertEquals(1, value.applyAsInt(ExecutionRuntimeConfiguration.fromEnvironment(
                Map.of(name, "\u200301\u2003"))));
        assertEquals(maximum, value.applyAsInt(ExecutionRuntimeConfiguration.fromEnvironment(
                Map.of(name, Integer.toString(maximum)))));
    }

    @ParameterizedTest
    @MethodSource("bindings")
    void everyNonblankBindingUsesTheSameUnsignedBoundedCauseFreeParser(
            String name, int maximum, ToIntFunction<ExecutionRuntimeConfiguration> ignored) {
        assertInvalid(name, "0", maximum);
        assertInvalid(name, "-1", maximum);
        assertInvalid(name, "+1", maximum);
        assertInvalid(name, "1.5", maximum);
        assertInvalid(name, "\u0661", maximum);
        assertInvalid(name, "\u00a0", maximum);
        assertInvalid(name, Integer.toString(maximum + 1), maximum);
        assertInvalid(name, "2147483648", maximum);
        assertInvalid(name, "operator-secret-not-a-number", maximum);
    }

    @Test
    void directCompositionRetainsPositiveProgrammaticFreedom() {
        ExecutionEnginePolicy enginePolicy = new ExecutionEnginePolicy(
                10_001, Duration.ofNanos(1), 1_025);
        Duration huge = Duration.ofSeconds(Long.MAX_VALUE, 999_999_999);

        assertEquals(huge, new ExecutionRuntimeConfiguration(enginePolicy, huge).runnerShutdownStepBound());
        assertThrows(NullPointerException.class, () -> new ExecutionRuntimeConfiguration(null, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> new ExecutionRuntimeConfiguration(enginePolicy, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionRuntimeConfiguration(enginePolicy, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionRuntimeConfiguration(enginePolicy, Duration.ofNanos(-1)));
    }

    private static Stream<Arguments> bindings() {
        return Stream.of(
                Arguments.of(ExecutionRuntimeConfiguration.MAX_STASHED_COMMANDS_PER_NODE_VARIABLE, 10_000,
                        (ToIntFunction<ExecutionRuntimeConfiguration>)
                                configuration -> configuration.enginePolicy().maxStashedCommandsPerNode()),
                Arguments.of(ExecutionRuntimeConfiguration.LIFECYCLE_STEP_SECONDS_VARIABLE, 10,
                        (ToIntFunction<ExecutionRuntimeConfiguration>) configuration ->
                                Math.toIntExact(configuration.enginePolicy().lifecycleStepBound().getSeconds())),
                Arguments.of(ExecutionRuntimeConfiguration.TERMINAL_HISTORY_CAPACITY_VARIABLE, 1_024,
                        (ToIntFunction<ExecutionRuntimeConfiguration>)
                                configuration -> configuration.enginePolicy().terminalNodeHistoryCapacity()),
                Arguments.of(ExecutionRuntimeConfiguration.RUNNER_SHUTDOWN_STEP_SECONDS_VARIABLE, 10,
                        (ToIntFunction<ExecutionRuntimeConfiguration>) configuration ->
                                Math.toIntExact(configuration.runnerShutdownStepBound().getSeconds())));
    }

    private static void assertInvalid(String name, String raw, int maximum) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ExecutionRuntimeConfiguration.fromEnvironment(Map.of(name, raw)));
        assertEquals(name + " must be a whole number from 1 through " + maximum, failure.getMessage());
        assertNull(failure.getCause());
        if (!raw.chars().allMatch(character -> character >= '0' && character <= '9')) {
            assertFalse(failure.getMessage().contains(raw));
        }
    }
}
