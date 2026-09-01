package ai.ravenroot.server.persistence;

import ai.ravenroot.persistence.sqlite.SqliteStoreLocation;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Where the server's execution store lives, and whether it exists at all.
 *
 * <h2>Why the server composes a store</h2>
 * <p>ADR 0010 allows PERS-04 or CORE-03 to provide persistence, and CORE-03 provides fan-in. Server
 * composition of that store remains separate from the CORE-03 fan-in mechanism.
 * Previously, <strong>the server composition root never wired a store at all</strong>, so every
 * constructor call it made ended in the overload defaulting {@code
 * executionStore} to {@code null}. That one made the other three moot — a primary path that writes
 * through the store writes through nothing when no store is composed.</p>
 *
 * <h2>Enabled by default</h2>
 * <p>An opt-in store would leave persistence conditional: its dependency would be
 * "satisfiable by configuration" rather than satisfied. So the store is on unless explicitly disabled,
 * and it uses the directory convention {@code ravenroot-cli}'s backup/restore already established,
 * so a server and the CLI that backs it up do not have to be told the same path twice.</p>
 *
 * <p>{@link #disabled()} disables execution persistence, but the configured location remains the
 * process's maintenance authority. The server must still exclude backup/restore while its audit
 * trail is live; disabling one store cannot make the other safe to replace underneath it.</p>
 */
public record ExecutionStoreConfiguration(boolean enabled, SqliteStoreLocation location) {

    /** Set to {@code false} (or {@code off}/{@code 0}/{@code no}) to run with no execution store. */
    public static final String ENABLED_VARIABLE = "RAVENROOT_EXECUTION_STORE_ENABLED";

    /** Shared with {@code ravenroot-cli}'s backup/restore, deliberately spelled the same. */
    public static final String DIRECTORY_VARIABLE = "RAVENROOT_EXECUTION_STORE_DIR";

    private static final String DEFAULT_DIRECTORY = "./data/execution-store";

    public ExecutionStoreConfiguration {
        Objects.requireNonNull(location, "location");
    }

    public static ExecutionStoreConfiguration fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String raw = environment.get(DIRECTORY_VARIABLE);
        Path directory;
        try {
            directory = Path.of(raw == null || raw.isBlank() ? DEFAULT_DIRECTORY : raw.trim());
        } catch (RuntimeException invalidPath) {
            // Do not retain the cause: an uncaught InvalidPathException repeats the raw environment
            // value in its message, while the only useful startup answer is that this setting is invalid.
            throw new IllegalArgumentException("Invalid execution store directory configuration");
        }
        return new ExecutionStoreConfiguration(enabledIn(environment), SqliteStoreLocation.underDirectory(directory));
    }

    /** No execution store, with the default directory retained as maintenance-lock authority. */
    public static ExecutionStoreConfiguration disabled() {
        return new ExecutionStoreConfiguration(false, SqliteStoreLocation.underDirectory(Path.of(DEFAULT_DIRECTORY)));
    }

    /**
     * Absent means enabled. Only an explicit, recognised negative disables the store — an
     * unrecognised value is not read as "off", because a typo silently turning durability off is the
     * durability failure this setting prevents.
     */
    private static boolean enabledIn(Map<String, String> environment) {
        String raw = environment.get(ENABLED_VARIABLE);
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "false", "off", "0", "no" -> false;
            default -> true;
        };
    }
}
