package ai.ravenroot.api.programming;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProgramAuthoringLimitsTest {
    @Test
    void propertyPresenceShadowsEnvironmentAndBlankSelectsTheDefault() {
        var properties = new Properties();
        properties.setProperty(ProgramAuthoringLimits.MAX_SOURCE_BYTES_PROPERTY, "  ");
        properties.setProperty(ProgramAuthoringLimits.MAX_BUILD_REQUEST_BYTES_PROPERTY, "2097152");
        properties.setProperty(ProgramAuthoringLimits.MAX_PROGRAMS_PER_BUILD_PROPERTY, "2");

        var limits = ProgramAuthoringLimits.resolve(properties, Map.of(
                ProgramAuthoringLimits.MAX_SOURCE_BYTES_ENV, "128",
                ProgramAuthoringLimits.MAX_BUILD_REQUEST_BYTES_ENV, "4096",
                ProgramAuthoringLimits.MAX_PROGRAMS_PER_BUILD_ENV, "4"));

        assertEquals(ProgramArtifactIdentity.MAX_SOURCE_BYTES, limits.maxSourceBytes());
        assertEquals(2 * 1024 * 1024, limits.maxBuildRequestBytes());
        assertEquals(2, limits.maxProgramsPerBuild());
    }

    @Test
    void environmentOverridesDefaultsAndMalformedOrUnsafeValuesRefuse() {
        var limits = ProgramAuthoringLimits.resolve(new Properties(), Map.of(
                ProgramAuthoringLimits.MAX_SOURCE_BYTES_ENV, "128",
                ProgramAuthoringLimits.MAX_BUILD_REQUEST_BYTES_ENV, "2048",
                ProgramAuthoringLimits.MAX_PROGRAMS_PER_BUILD_ENV, "4"));
        assertEquals(new ProgramAuthoringLimits(128, 2048, 4), limits);
        assertThrows(IllegalArgumentException.class, () -> ProgramAuthoringLimits.resolve(
                new Properties(), Map.of(ProgramAuthoringLimits.MAX_SOURCE_BYTES_ENV, "wide")));
        var nonString = new Properties();
        nonString.put(ProgramAuthoringLimits.MAX_SOURCE_BYTES_PROPERTY, 128);
        assertThrows(IllegalArgumentException.class, () -> ProgramAuthoringLimits.resolve(nonString,
                Map.of(ProgramAuthoringLimits.MAX_SOURCE_BYTES_ENV, "64")));
        assertThrows(IllegalArgumentException.class, () -> new ProgramAuthoringLimits(
                ProgramArtifactIdentity.MAX_SOURCE_BYTES + 1,
                ProgramAuthoringLimits.HARD_MAX_BUILD_REQUEST_BYTES, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProgramAuthoringLimits(1024, 512, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProgramAuthoringLimits(
                1, 1, ProgramAuthoringLimits.HARD_MAX_PROGRAMS_PER_BUILD + 1));
    }

    @Test
    void utf8AndBatchLimitsRejectBeforeConsumersRun() {
        var limits = new ProgramAuthoringLimits(4, 8, 2);
        limits.requireSource("test");
        limits.requireProgramCount(2);
        assertThrows(IllegalArgumentException.class, () -> limits.requireSource("€€"));
        assertThrows(IllegalArgumentException.class, () -> limits.requireProgramCount(3));
    }
}
