package ai.ravenroot.programming.graalvm;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Immutable deployment authority for the Graal adapter; the server selects the runtime separately. */
public record GraalVmRuntimeConfiguration(Path supervisor, Path javaExecutable, Duration timeout,
                                          int maxHeapMegabytes, SandboxLaunchPlacement placement) {
    public static final int DEFAULT_TIMEOUT_MS = 5_000;
    public static final int MIN_TIMEOUT_MS = 100;
    public static final int MAX_TIMEOUT_MS = 300_000;
    public static final int DEFAULT_MAX_HEAP_MB = 64;
    public static final int MIN_MAX_HEAP_MB = 32;
    public static final int MAX_MAX_HEAP_MB = 1024;
    public static final String RESOURCE_CACHE_PROPERTY = "polyglot.engine.userResourceCache";

    public GraalVmRuntimeConfiguration {
        Objects.requireNonNull(javaExecutable, "Java executable is required");
        Objects.requireNonNull(timeout, "Timeout is required");
        Objects.requireNonNull(placement, "Launch placement is required");
        if (timeout.compareTo(Duration.ofMillis(MIN_TIMEOUT_MS)) < 0
                || timeout.compareTo(Duration.ofMillis(MAX_TIMEOUT_MS)) > 0
                || timeout.toNanos() % 1_000_000 != 0
                || maxHeapMegabytes < MIN_MAX_HEAP_MB || maxHeapMegabytes > MAX_MAX_HEAP_MB) {
            throw new IllegalArgumentException("Invalid Graal runtime capacity");
        }
        if (javaExecutable.toString().isBlank() || (supervisor != null && supervisor.toString().isBlank()))
            throw new IllegalArgumentException("Invalid Graal runtime executable path");
        supervisor = supervisor == null ? null : supervisor.toAbsolutePath().normalize();
        javaExecutable = javaExecutable.toAbsolutePath().normalize();
    }

    /** Select by property presence before parsing: a blank property shadows the environment. */
    public static GraalVmRuntimeConfiguration resolve(Properties properties, Map<String, String> environment) {
        Objects.requireNonNull(properties, "Properties are required");
        Objects.requireNonNull(environment, "Environment is required");
        String supervisor = selected(properties, environment, "ravenroot.graal.sandbox-supervisor", "RAVENROOT_GRAAL_SANDBOX_SUPERVISOR");
        String java = selected(properties, environment, "ravenroot.graal.java", "RAVENROOT_GRAAL_JAVA");
        Path javaPath = java == null || java.isBlank()
                ? path(javaHome(properties), "java.home").resolve("bin/java")
                : path(java, "ravenroot.graal.java");
        int timeout = integer(selected(properties, environment, "ravenroot.program.timeout-ms", "RAVENROOT_PROGRAM_TIMEOUT_MS"),
                "ravenroot.program.timeout-ms", DEFAULT_TIMEOUT_MS, MIN_TIMEOUT_MS, MAX_TIMEOUT_MS);
        int heap = integer(selected(properties, environment, "ravenroot.program.max-heap-mb", "RAVENROOT_PROGRAM_MAX_HEAP_MB"),
                "ravenroot.program.max-heap-mb", DEFAULT_MAX_HEAP_MB, MIN_MAX_HEAP_MB, MAX_MAX_HEAP_MB);
        String standard = property(properties, RESOURCE_CACHE_PROPERTY);
        SandboxLaunchPlacement placement;
        if (standard != null) {
            // This is the SERVER property, explicitly transported; never reinterpret its blank value as a path.
            placement = new SandboxLaunchPlacement(standard);
        } else {
            String cache = selected(properties, environment, "ravenroot.graal.resource-cache-dir", "RAVENROOT_GRAAL_RESOURCE_CACHE_DIR");
            placement = cache == null || cache.isBlank() ? SandboxLaunchPlacement.LEGACY
                    : new SandboxLaunchPlacement(path(cache, "ravenroot.graal.resource-cache-dir").toString());
        }
        return new GraalVmRuntimeConfiguration(supervisor == null || supervisor.isBlank() ? null
                : path(supervisor, "ravenroot.graal.sandbox-supervisor"), javaPath, Duration.ofMillis(timeout), heap, placement);
    }

    private static String selected(Properties properties, Map<String, String> environment, String property, String variable) {
        String value = property(properties, property);
        return value != null ? value : environment.get(variable);
    }

    private static String property(Properties properties, String name) {
        if (properties.containsKey(name) && !(properties.get(name) instanceof String))
            throw new IllegalArgumentException(name + " must be a string");
        return properties.getProperty(name);
    }

    private static String javaHome(Properties properties) {
        String home = property(properties, "java.home");
        return home == null ? System.getProperty("java.home") : home;
    }

    private static Path path(String value, String setting) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Invalid path for " + setting);
        try { return Path.of(value).toAbsolutePath().normalize(); }
        catch (InvalidPathException invalid) { throw new IllegalArgumentException("Invalid path for " + setting); }
    }

    private static int integer(String value, String setting, int fallback, int minimum, int maximum) {
        if (value == null || value.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= minimum && parsed <= maximum) return parsed;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(setting + " must be between " + minimum + " and " + maximum);
    }
}
