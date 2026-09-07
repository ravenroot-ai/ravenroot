package ai.ravenroot.server.persistence;

import ai.ravenroot.persistence.sqlite.SqliteStoreLocation;
import ai.ravenroot.persistence.sqlite.SqliteStoreConfig;
import ai.ravenroot.api.persistence.ExecutionStorePolicy;

import java.time.Duration;

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

    /** Set to {@code true}, or to {@code false} (or {@code off}/{@code 0}/{@code no}). */
    public static final String ENABLED_VARIABLE = "RAVENROOT_EXECUTION_STORE_ENABLED";

    /** Shared with {@code ravenroot-cli}'s backup/restore, deliberately spelled the same. */
    public static final String DIRECTORY_VARIABLE = "RAVENROOT_EXECUTION_STORE_DIR";

    public static final String MAX_LEASE_TTL_SECONDS_VARIABLE = "RAVENROOT_EXECUTION_STORE_MAX_LEASE_TTL_SECONDS";
    public static final String MAX_PAYLOAD_BYTES_VARIABLE = "RAVENROOT_EXECUTION_STORE_MAX_PAYLOAD_BYTES";
    public static final String MAX_CLOCK_SKEW_MILLIS_VARIABLE = "RAVENROOT_EXECUTION_STORE_MAX_CLOCK_SKEW_MILLIS";
    public static final String JOURNAL_RETENTION_SECONDS_VARIABLE = "RAVENROOT_EXECUTION_STORE_JOURNAL_RETENTION_SECONDS";
    public static final String MAX_INVENTORY_PAGE_SIZE_VARIABLE = "RAVENROOT_EXECUTION_STORE_MAX_INVENTORY_PAGE_SIZE";
    public static final String TERMINAL_RETENTION_SECONDS_VARIABLE = "RAVENROOT_EXECUTION_STORE_TERMINAL_RETENTION_SECONDS";
    public static final String RESULT_RETENTION_SECONDS_VARIABLE = "RAVENROOT_EXECUTION_STORE_RESULT_RETENTION_SECONDS";
    public static final String SQLITE_BUSY_TIMEOUT_MILLIS_VARIABLE = "RAVENROOT_SQLITE_BUSY_TIMEOUT_MILLIS";

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

    /**
     * Resolves every store setting before resource acquisition, including when execution persistence
     * is disabled. Absent or blank values use the typed defaults. Numeric values contain only ASCII
     * decimal digits after surrounding whitespace is stripped; signs and fractional values fail.
     *
     * @param environment explicit startup environment
     * @return location, shared policy and SQLite connection wait resolved together
     */
    public static Resolved resolveEnvironment(Map<String, String> environment) {
        ExecutionStoreConfiguration configuration = fromEnvironment(environment);
        var defaults = ExecutionStorePolicy.DEFAULTS;
        Duration lease = Duration.ofSeconds(number(environment, MAX_LEASE_TTL_SECONDS_VARIABLE,
                defaults.maxLeaseTtl().getSeconds(), 1, Long.MAX_VALUE));
        int payload = (int) number(environment, MAX_PAYLOAD_BYTES_VARIABLE,
                defaults.maxPayloadBytes(), 1, Integer.MAX_VALUE);
        Duration skew = Duration.ofMillis(number(environment, MAX_CLOCK_SKEW_MILLIS_VARIABLE,
                defaults.maxClockSkew().toMillis(), 0, Long.MAX_VALUE));
        Duration journal = Duration.ofSeconds(number(environment, JOURNAL_RETENTION_SECONDS_VARIABLE,
                defaults.journalRetention().getSeconds(), 1, Long.MAX_VALUE));
        int page = (int) number(environment, MAX_INVENTORY_PAGE_SIZE_VARIABLE,
                defaults.maxInventoryPageSize(), 1, Integer.MAX_VALUE);
        Duration terminal = Duration.ofSeconds(number(environment, TERMINAL_RETENTION_SECONDS_VARIABLE,
                defaults.terminalRetention().getSeconds(), 1, Long.MAX_VALUE));
        Duration result = Duration.ofSeconds(number(environment, RESULT_RETENTION_SECONDS_VARIABLE,
                defaults.executionResultRetention().getSeconds(), 1, Long.MAX_VALUE));
        Duration busyTimeout = Duration.ofMillis(number(environment, SQLITE_BUSY_TIMEOUT_MILLIS_VARIABLE,
                SqliteStoreConfig.defaults().busyTimeout().toMillis(), 0, Integer.MAX_VALUE));
        if (terminal.compareTo(journal) < 0) {
            throw new IllegalArgumentException(TERMINAL_RETENTION_SECONDS_VARIABLE
                    + " must be at least " + JOURNAL_RETENTION_SECONDS_VARIABLE);
        }
        if (terminal.compareTo(result) < 0) {
            throw new IllegalArgumentException(TERMINAL_RETENTION_SECONDS_VARIABLE
                    + " must be at least " + RESULT_RETENTION_SECONDS_VARIABLE);
        }
        return new Resolved(configuration, new ExecutionStorePolicy(lease, payload, skew, journal, page,
                terminal, result), busyTimeout);
    }

    /**
     * Additional resolved settings without changing this configuration record's two-component shape.
     * SQLite's busy wait is an exact nonnegative signed-32-bit millisecond value; that is its native
     * representation, not a ceiling on the adapter-neutral policy or the direct Java adapter config.
     *
     * @param configuration enabled state and maintenance location
     * @param policy shared execution-store limits
     * @param sqliteBusyTimeout SQLite's representable writer-lock wait
     */
    public record Resolved(ExecutionStoreConfiguration configuration, ExecutionStorePolicy policy,
                           Duration sqliteBusyTimeout) {
        public Resolved {
            Objects.requireNonNull(configuration, "configuration");
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(sqliteBusyTimeout, "sqliteBusyTimeout");
            if (sqliteBusyTimeout.isNegative()
                    || sqliteBusyTimeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0
                    || sqliteBusyTimeout.getNano() % 1_000_000 != 0) {
                throw invalid(SQLITE_BUSY_TIMEOUT_MILLIS_VARIABLE, 0, Integer.MAX_VALUE);
            }
        }

        /** @return the resolved execution-store config, retaining FULL synchronous durability */
        public SqliteStoreConfig sqliteStoreConfig() {
            return new SqliteStoreConfig(SqliteStoreConfig.SynchronousMode.FULL, sqliteBusyTimeout, policy);
        }
    }

    private static long number(Map<String, String> environment, String name, long fallback,
                               long minimum, long maximum) {
        String raw = environment.get(name);
        if (raw == null || raw.isBlank()) return fallback;
        String normalized = raw.strip();
        if (normalized.isEmpty()
                || normalized.chars().anyMatch(character -> character < '0' || character > '9')) {
            throw invalid(name, minimum, maximum);
        }
        final long value;
        try {
            value = Long.parseLong(normalized);
        } catch (NumberFormatException invalidNumber) {
            throw invalid(name, minimum, maximum);
        }
        if (value < minimum || value > maximum) throw invalid(name, minimum, maximum);
        return value;
    }

    private static IllegalArgumentException invalid(String name, long minimum, long maximum) {
        return new IllegalArgumentException(name + " must be a whole number from " + minimum + " through " + maximum);
    }

    /** No execution store, with the default directory retained as maintenance-lock authority. */
    public static ExecutionStoreConfiguration disabled() {
        return new ExecutionStoreConfiguration(false, SqliteStoreLocation.underDirectory(Path.of(DEFAULT_DIRECTORY)));
    }

    /**
     * Absent or blank means enabled. The canonical positive is {@code true}; the four documented
     * negative aliases disable the store. Every other nonblank value is rejected so a typo cannot
     * silently choose either durability posture.
     */
    private static boolean enabledIn(Map<String, String> environment) {
        String raw = environment.get(ENABLED_VARIABLE);
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false", "off", "0", "no" -> false;
            default -> throw new IllegalArgumentException(ENABLED_VARIABLE
                    + " must be 'true', 'false', 'off', '0', or 'no'");
        };
    }
}
