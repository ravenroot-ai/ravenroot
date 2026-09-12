package ai.ravenroot.api.programming;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Operator-owned admission limits for program authoring requests.
 *
 * @param maxSourceBytes maximum UTF-8 bytes accepted for one program source
 * @param maxBuildRequestBytes maximum aggregate bytes accepted for one build request
 * @param maxProgramsPerBuild maximum programs accepted in one build request
 */
public record ProgramAuthoringLimits(int maxSourceBytes, int maxBuildRequestBytes, int maxProgramsPerBuild) {
    public static final int HARD_MAX_BUILD_REQUEST_BYTES = 10 * 1024 * 1024;
    public static final int HARD_MAX_PROGRAMS_PER_BUILD = 256;
    public static final ProgramAuthoringLimits DEFAULTS = new ProgramAuthoringLimits(
            ProgramArtifactIdentity.MAX_SOURCE_BYTES, HARD_MAX_BUILD_REQUEST_BYTES,
            HARD_MAX_PROGRAMS_PER_BUILD);

    public static final String MAX_SOURCE_BYTES_PROPERTY =
            "ravenroot.program.authoring.max-source-bytes";
    public static final String MAX_BUILD_REQUEST_BYTES_PROPERTY =
            "ravenroot.program.authoring.max-build-request-bytes";
    public static final String MAX_PROGRAMS_PER_BUILD_PROPERTY =
            "ravenroot.program.authoring.max-programs-per-build";
    public static final String MAX_SOURCE_BYTES_ENV = "RAVENROOT_PROGRAM_AUTHORING_MAX_SOURCE_BYTES";
    public static final String MAX_BUILD_REQUEST_BYTES_ENV =
            "RAVENROOT_PROGRAM_AUTHORING_MAX_BUILD_REQUEST_BYTES";
    public static final String MAX_PROGRAMS_PER_BUILD_ENV =
            "RAVENROOT_PROGRAM_AUTHORING_MAX_PROGRAMS_PER_BUILD";

    /** Validates one complete program-authoring limit set. */
    public ProgramAuthoringLimits {
        if (maxSourceBytes < 1 || maxSourceBytes > ProgramArtifactIdentity.MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("maxSourceBytes must be between 1 and "
                    + ProgramArtifactIdentity.MAX_SOURCE_BYTES);
        }
        if (maxBuildRequestBytes < maxSourceBytes
                || maxBuildRequestBytes > HARD_MAX_BUILD_REQUEST_BYTES) {
            throw new IllegalArgumentException("maxBuildRequestBytes must be between maxSourceBytes and "
                    + HARD_MAX_BUILD_REQUEST_BYTES);
        }
        if (maxProgramsPerBuild < 1 || maxProgramsPerBuild > HARD_MAX_PROGRAMS_PER_BUILD) {
            throw new IllegalArgumentException("maxProgramsPerBuild must be between 1 and "
                    + HARD_MAX_PROGRAMS_PER_BUILD);
        }
    }

    /**
     * Resolves each setting by lexical presence: property, then environment, then default.
     *
     * @param properties Java system properties or an equivalent operator-owned property set
     * @param environment process environment variables
     * @return validated effective program-authoring limits
     */
    public static ProgramAuthoringLimits resolve(Properties properties, Map<String, String> environment) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(environment, "environment");
        return new ProgramAuthoringLimits(
                integer(properties, environment, MAX_SOURCE_BYTES_PROPERTY, MAX_SOURCE_BYTES_ENV,
                        DEFAULTS.maxSourceBytes),
                integer(properties, environment, MAX_BUILD_REQUEST_BYTES_PROPERTY,
                        MAX_BUILD_REQUEST_BYTES_ENV, DEFAULTS.maxBuildRequestBytes),
                integer(properties, environment, MAX_PROGRAMS_PER_BUILD_PROPERTY,
                        MAX_PROGRAMS_PER_BUILD_ENV, DEFAULTS.maxProgramsPerBuild));
    }

    /**
     * Rejects source whose UTF-8 representation exceeds {@link #maxSourceBytes()}.
     *
     * @param source program source to validate
     */
    public void requireSource(String source) {
        Objects.requireNonNull(source, "source");
        if (source.getBytes(StandardCharsets.UTF_8).length > maxSourceBytes) {
            throw new IllegalArgumentException("program source exceeds the configured byte limit");
        }
    }

    /**
     * Rejects a build whose program count exceeds the configured request bounds.
     *
     * @param count number of programs in the build request
     */
    public void requireProgramCount(int count) {
        if (count < 1 || count > maxProgramsPerBuild) {
            throw new IllegalArgumentException("one to " + maxProgramsPerBuild + " programs are required");
        }
    }

    private static int integer(Properties properties, Map<String, String> environment,
                               String property, String variable, int fallback) {
        String selected;
        if (properties.containsKey(property)) {
            Object raw = properties.get(property);
            if (!(raw instanceof String text)) {
                throw new IllegalArgumentException(property + " must be a string");
            }
            selected = text;
        } else {
            selected = environment.get(variable);
        }
        if (selected == null || selected.isBlank()) return fallback;
        try {
            return Integer.parseInt(selected.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(property + " must be a base-10 integer", invalid);
        }
    }
}
