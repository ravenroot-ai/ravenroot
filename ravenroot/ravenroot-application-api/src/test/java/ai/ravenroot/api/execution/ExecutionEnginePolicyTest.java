package ai.ravenroot.api.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionEnginePolicyTest {

    @Test
    void frozenLegacyPreservesTheThreeExistingEngineValues() {
        assertEquals(10_000, ExecutionEnginePolicy.FROZEN_LEGACY.maxStashedCommandsPerNode());
        assertEquals(Duration.ofSeconds(10), ExecutionEnginePolicy.FROZEN_LEGACY.lifecycleStepBound());
        assertEquals(1_024, ExecutionEnginePolicy.FROZEN_LEGACY.terminalNodeHistoryCapacity());
    }

    @Test
    void constructorRejectsNonPositiveValuesAndAMissingDuration() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionEnginePolicy(0, Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionEnginePolicy(-1, Duration.ofSeconds(1), 1));
        assertThrows(NullPointerException.class,
                () -> new ExecutionEnginePolicy(1, null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionEnginePolicy(1, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionEnginePolicy(1, Duration.ofNanos(-1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionEnginePolicy(1, Duration.ofSeconds(1), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionEnginePolicy(1, Duration.ofSeconds(1), -1));
    }

    @Test
    void positiveProgrammaticValuesAreNotLimitedByDeploymentConfiguration() {
        var beyondEnvironmentCaps = new ExecutionEnginePolicy(
                10_001, Duration.ofSeconds(11), 1_025);
        assertEquals(10_001, beyondEnvironmentCaps.maxStashedCommandsPerNode());
        assertEquals(Duration.ofSeconds(11), beyondEnvironmentCaps.lifecycleStepBound());
        assertEquals(1_025, beyondEnvironmentCaps.terminalNodeHistoryCapacity());

        assertEquals(Duration.ofNanos(1),
                new ExecutionEnginePolicy(1, Duration.ofNanos(1), 1).lifecycleStepBound());
        Duration huge = Duration.ofSeconds(Long.MAX_VALUE, 999_999_999);
        assertEquals(huge, new ExecutionEnginePolicy(1, huge, 1).lifecycleStepBound());
    }
}
