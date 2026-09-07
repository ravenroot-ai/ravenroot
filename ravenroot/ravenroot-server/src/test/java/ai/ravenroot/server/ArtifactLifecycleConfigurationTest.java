package ai.ravenroot.server;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactLifecycleConfigurationTest {
    @Test
    void defaultsDualControlToDisabled() {
        assertFalse(ArtifactLifecycleConfiguration.fromEnvironment(Map.of()).dualControl());
        for (String blank : List.of("", " ", "\t", "\n")) {
            assertFalse(ArtifactLifecycleConfiguration.fromEnvironment(
                    Map.of(ArtifactLifecycleConfiguration.DUAL_CONTROL_ENV, blank)).dualControl());
        }
        var nullValue = new HashMap<String, String>();
        nullValue.put(ArtifactLifecycleConfiguration.DUAL_CONTROL_ENV, null);
        assertFalse(ArtifactLifecycleConfiguration.fromEnvironment(nullValue).dualControl());
    }

    @Test
    void acceptsOnlyCanonicalBooleanValues() {
        assertTrue(ArtifactLifecycleConfiguration.fromEnvironment(
                Map.of(ArtifactLifecycleConfiguration.DUAL_CONTROL_ENV, "true")).dualControl());
        assertFalse(ArtifactLifecycleConfiguration.fromEnvironment(
                Map.of(ArtifactLifecycleConfiguration.DUAL_CONTROL_ENV, "false")).dualControl());
        for (String invalid : List.of(" true", "true ", "\tfalse",
                "TRUE", "True", "FALSE", "False", "yes")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> ArtifactLifecycleConfiguration.fromEnvironment(
                    Map.of(ArtifactLifecycleConfiguration.DUAL_CONTROL_ENV, invalid)),
                    () -> "must reject raw non-canonical value: " + invalid.replace("\n", "\\n"));
            org.junit.jupiter.api.Assertions.assertNull(failure.getCause());
        }
    }
}
