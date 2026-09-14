package ai.ravenroot.programming.graalvm;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class GraalVmRuntimeConfigurationTest {
    @Test void defaultsAndEnvironmentOverridesReachTheTypedPolicy() {
        var defaults = resolve(new Properties(), Map.of());
        assertNull(defaults.supervisor());
        assertEquals(Duration.ofMillis(5000), defaults.timeout());
        assertEquals(64, defaults.maxHeapMegabytes());
        assertEquals(Path.of(System.getProperty("java.home"), "bin/java").toAbsolutePath().normalize(), defaults.javaExecutable());
        assertEquals(SandboxLaunchPlacement.LEGACY, defaults.placement());
        var overridden = resolve(new Properties(), Map.of("RAVENROOT_PROGRAM_TIMEOUT_MS", "1200",
                "RAVENROOT_PROGRAM_MAX_HEAP_MB", "96", "RAVENROOT_GRAAL_JAVA", "/runtime/java",
                "RAVENROOT_GRAAL_SANDBOX_SUPERVISOR", "/runtime/supervisor",
                "RAVENROOT_GRAAL_RESOURCE_CACHE_DIR", "/runtime/cache"));
        assertEquals(Duration.ofMillis(1200), overridden.timeout());
        assertEquals(96, overridden.maxHeapMegabytes());
        assertEquals(Path.of("/runtime/java"), overridden.javaExecutable());
        assertEquals(Path.of("/runtime/supervisor"), overridden.supervisor());
        assertEquals("/runtime/cache", overridden.placement().resourceCachePropertyValue());
    }

    @Test void propertiesSelectBeforeParsingAndBlankShadowsEnvironment() {
        var properties = new Properties();
        properties.setProperty("ravenroot.program.timeout-ms", " ");
        properties.setProperty("ravenroot.program.max-heap-mb", "128");
        properties.setProperty("ravenroot.graal.java", "");
        properties.setProperty("java.home", "/typed/jdk");
        properties.setProperty("ravenroot.graal.sandbox-supervisor", "");
        properties.setProperty("ravenroot.graal.resource-cache-dir", " ");
        var config = resolve(properties, Map.of("RAVENROOT_PROGRAM_TIMEOUT_MS", "invalid",
                "RAVENROOT_PROGRAM_MAX_HEAP_MB", "invalid", "RAVENROOT_GRAAL_JAVA", "/ignored/java",
                "RAVENROOT_GRAAL_SANDBOX_SUPERVISOR", "/ignored/supervisor", "RAVENROOT_GRAAL_RESOURCE_CACHE_DIR", "/ignored/cache"));
        assertEquals(Duration.ofMillis(5000), config.timeout());
        assertEquals(128, config.maxHeapMegabytes());
        assertEquals(Path.of("/typed/jdk/bin/java"), config.javaExecutable());
        assertNull(config.supervisor());
        assertFalse(config.placement().hasOverride());
    }

    @Test void standardServerPropertyHasExactPresenceSemanticsIncludingBlank() {
        for (String value : new String[]{"/standard/cache", "", "   "}) {
            var properties = new Properties();
            properties.setProperty(GraalVmRuntimeConfiguration.RESOURCE_CACHE_PROPERTY, value);
            properties.setProperty("ravenroot.graal.resource-cache-dir", "/alias/cache");
            var placement = resolve(properties, Map.of("RAVENROOT_GRAAL_RESOURCE_CACHE_DIR", "/env/cache")).placement();
            assertTrue(placement.hasOverride());
            assertEquals(value, placement.resourceCachePropertyValue());
        }
        var properties = new Properties();
        properties.setProperty("ravenroot.graal.resource-cache-dir", "/alias/a/../cache");
        assertEquals("/alias/cache", resolve(properties, Map.of("RAVENROOT_GRAAL_RESOURCE_CACHE_DIR", "/env")).placement().resourceCachePropertyValue());
    }

    @Test void invalidSelectedSettingsRefuseWithoutEchoingValues() {
        for (String value : new String[]{"99", "300001", "2147483648", "not-a-number"}) {
            var p = new Properties(); p.setProperty("ravenroot.program.timeout-ms", value);
            assertThrows(IllegalArgumentException.class, () -> resolve(p, Map.of("RAVENROOT_PROGRAM_TIMEOUT_MS", "5000")));
        }
        for (String value : new String[]{"31", "1025", "2147483648"}) {
            assertThrows(IllegalArgumentException.class, () -> resolve(new Properties(), Map.of("RAVENROOT_PROGRAM_MAX_HEAP_MB", value)));
        }
        for (String name : new String[]{"ravenroot.graal.java", "ravenroot.graal.sandbox-supervisor", "ravenroot.graal.resource-cache-dir", GraalVmRuntimeConfiguration.RESOURCE_CACHE_PROPERTY}) {
            var p = new Properties(); p.setProperty(name, "sensitive\0path");
            var error = assertThrows(IllegalArgumentException.class, () -> resolve(p, Map.of()));
            assertFalse(error.toString().contains("sensitive"));
        }
        assertThrows(IllegalArgumentException.class, () -> new SandboxLaunchPlacement("x".repeat(16385)));
    }

    @Test void directConstructionHasTheSameCapacityBoundsAndInstancesDoNotDrift() {
        var first = resolve(new Properties(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> new GraalVmRuntimeConfiguration(null, first.javaExecutable(), Duration.ofMillis(99), 64, first.placement()));
        assertThrows(IllegalArgumentException.class, () -> new GraalVmRuntimeConfiguration(null, first.javaExecutable(), Duration.ofMillis(5000).plusNanos(1), 64, first.placement()));
        assertThrows(IllegalArgumentException.class, () -> new GraalVmRuntimeConfiguration(null, first.javaExecutable(), first.timeout(), 1025, first.placement()));
        var p = new Properties(); p.setProperty("ravenroot.program.timeout-ms", "100");
        var second = resolve(p, Map.of()); p.setProperty("ravenroot.program.timeout-ms", "300000");
        assertEquals(Duration.ofMillis(100), second.timeout());
        assertEquals(Duration.ofMillis(5000), first.timeout());
        assertEquals(Duration.ofMillis(300000), resolve(p, Map.of()).timeout());
    }

    @Test void cachePlacementDoesNotChangeFingerprintButRuntimeCapacityDoes() {
        var defaults = resolve(new Properties(), Map.of());
        var legacy = GraalVmProgramRuntime.fromConfiguration(defaults);
        assertEquals(new GraalVmProgramRuntime(defaults.javaExecutable(), Duration.ofMillis(5000), 64).compatibilityFingerprint(),
                legacy.compatibilityFingerprint(), "Default policy and fingerprint must preserve the legacy constructor exactly");
        for (String cache : new String[]{"", "/cache/a", "/cache/b"}) {
            var alternate = new GraalVmRuntimeConfiguration(null, defaults.javaExecutable(), defaults.timeout(), defaults.maxHeapMegabytes(), new SandboxLaunchPlacement(cache));
            assertEquals(legacy.compatibilityFingerprint(), GraalVmProgramRuntime.fromConfiguration(alternate).compatibilityFingerprint());
        }
        assertNotEquals(legacy.compatibilityFingerprint(), GraalVmProgramRuntime.fromConfiguration(resolve(new Properties(), Map.of("RAVENROOT_PROGRAM_TIMEOUT_MS", "6000"))).compatibilityFingerprint());
        assertNotEquals(legacy.compatibilityFingerprint(), GraalVmProgramRuntime.fromConfiguration(resolve(new Properties(), Map.of("RAVENROOT_PROGRAM_MAX_HEAP_MB", "96"))).compatibilityFingerprint());
    }

    @Test void nonStringSelectedPropertiesRefuseInsteadOfFallingThroughToValidEnvironment() {
        var environment = Map.of("RAVENROOT_GRAAL_JAVA", "/java", "RAVENROOT_PROGRAM_TIMEOUT_MS", "5000",
                "RAVENROOT_PROGRAM_MAX_HEAP_MB", "64", "RAVENROOT_GRAAL_SANDBOX_SUPERVISOR", "/supervisor",
                "RAVENROOT_GRAAL_RESOURCE_CACHE_DIR", "/cache");
        for (String name : new String[]{"ravenroot.graal.java", "ravenroot.program.timeout-ms",
                "ravenroot.program.max-heap-mb", "ravenroot.graal.sandbox-supervisor",
                "ravenroot.graal.resource-cache-dir", GraalVmRuntimeConfiguration.RESOURCE_CACHE_PROPERTY}) {
            var properties = new Properties(); properties.put(name, 42);
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> GraalVmProgramRuntime.fromEnvironment(properties, environment));
            assertEquals(name + " must be a string", failure.getMessage());
        }
    }

    private static GraalVmRuntimeConfiguration resolve(Properties p, Map<String, String> e) {
        return GraalVmRuntimeConfiguration.resolve(p, e);
    }
}
