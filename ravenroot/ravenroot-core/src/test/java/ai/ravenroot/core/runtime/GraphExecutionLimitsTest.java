package ai.ravenroot.core.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphExecutionLimitsTest {

    @Test
    void environmentCanNarrowOrRaiseLimitsOnlyWithinSupportedCeilings() {
        GraphExecutionLimits limits = GraphExecutionLimits.fromEnvironment(Map.of(
                GraphExecutionLimits.MAX_NODES_VARIABLE, "17",
                GraphExecutionLimits.MAX_FAN_OUT_VARIABLE, "7",
                GraphExecutionLimits.MAX_TRAVERSAL_STEPS_VARIABLE, "200000",
                GraphExecutionLimits.MAX_RECOVERY_DELIVERIES_VARIABLE, "3"));

        assertEquals(17, limits.graphMl().maxNodes());
        assertEquals(7, limits.maxFanOut());
        assertEquals(200_000, limits.maxTraversalSteps());
        assertEquals(3, limits.maxRecoveryDeliveriesPerAttempt());
    }

    @Test
    void invalidValuesNeverEscapeTheirVariableNameOrSupportedCeiling() {
        assertThrows(IllegalArgumentException.class, () -> GraphExecutionLimits.fromEnvironment(
                Map.of(GraphExecutionLimits.MAX_FAN_OUT_VARIABLE, "0")));
        assertThrows(IllegalArgumentException.class, () -> GraphExecutionLimits.fromEnvironment(
                Map.of(GraphExecutionLimits.MAX_TRAVERSAL_STEPS_VARIABLE,
                        Long.toString(GraphExecutionLimits.HARD_MAX_TRAVERSAL_STEPS + 1))));
        assertThrows(IllegalArgumentException.class, () -> GraphExecutionLimits.fromEnvironment(
                Map.of(GraphExecutionLimits.MAX_FAN_OUT_VARIABLE, "not-a-number")));
    }
}
