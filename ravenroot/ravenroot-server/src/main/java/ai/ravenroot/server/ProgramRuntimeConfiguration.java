package ai.ravenroot.server;

import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Typed startup selection for the program runtime implementation. */
public record ProgramRuntimeConfiguration(Runtime runtime) {
    public static final String PROPERTY = "ravenroot.program.runtime";
    public static final String ENVIRONMENT = "RAVENROOT_PROGRAM_RUNTIME";
    public static final ProgramRuntimeConfiguration DEFAULTS =
            new ProgramRuntimeConfiguration(Runtime.GRAALVM);

    public ProgramRuntimeConfiguration {
        Objects.requireNonNull(runtime, "runtime");
    }

    public static ProgramRuntimeConfiguration resolve(Properties properties, Map<String, String> environment) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(environment, "environment");
        String selected;
        if (properties.containsKey(PROPERTY)) {
            Object raw = properties.get(PROPERTY);
            if (!(raw instanceof String text)) {
                throw new IllegalArgumentException(PROPERTY + " must be a string");
            }
            selected = text;
        } else {
            selected = environment.get(ENVIRONMENT);
        }
        if (selected == null) return DEFAULTS;
        if (selected.isBlank()) throw new IllegalArgumentException(PROPERTY + " cannot be blank");
        return new ProgramRuntimeConfiguration(switch (selected.trim()) {
            case "graalvm" -> Runtime.GRAALVM;
            case "disabled" -> Runtime.DISABLED;
            default -> throw new IllegalArgumentException("Unknown program runtime: " + selected);
        });
    }

    public enum Runtime { GRAALVM, DISABLED }
}
