package ai.ravenroot.api.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void compatibilityFingerprintHasOneStableOwnerAndExcludesTerminalHistory() {
        assertEquals("", ExecutionEnginePolicy.FROZEN_LEGACY.compatibilityFingerprint());
        assertEquals("", new ExecutionEnginePolicy(10_000, Duration.ofSeconds(10), 9_999)
                .compatibilityFingerprint());

        var representative = new ExecutionEnginePolicy(31, Duration.ofNanos(7), 19);
        assertEquals("ead0181e10b298dceaf91db3fa2b59e46659dc8f959215df074b968baaf44a5a",
                representative.compatibilityFingerprint());
        assertTrue(representative.compatibilityFingerprint().matches("[0-9a-f]{64}"));
        assertEquals(representative.compatibilityFingerprint(),
                new ExecutionEnginePolicy(31, Duration.ofNanos(7), 29).compatibilityFingerprint());
        assertNotEquals(representative.compatibilityFingerprint(),
                new ExecutionEnginePolicy(32, Duration.ofNanos(7), 19).compatibilityFingerprint());
        assertNotEquals(representative.compatibilityFingerprint(),
                new ExecutionEnginePolicy(31, Duration.ofSeconds(1, 7), 19).compatibilityFingerprint());
    }
}
