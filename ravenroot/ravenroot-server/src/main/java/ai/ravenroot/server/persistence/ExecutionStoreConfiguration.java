package ai.ravenroot.server.persistence;

import ai.ravenroot.persistence.sqlite.SqliteStoreLocation;
import ai.ravenroot.persistence.postgresql.PostgresExecutionManifestStore;
import ai.ravenroot.persistence.postgresql.PostgresStoreConfig;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Which execution store this server composes, and where it lives.
 *
 * <h2>Why the server composes a store</h2>
 * <p>ADR 0010 allows PERS-04 or CORE-03 to provide persistence, and CORE-03 provides fan-in. Server
 * composition of that store remains separate from the CORE-03 fan-in mechanism.
 * Previously, <strong>the server composition root never wired a store at all</strong>, so every
 * constructor call it made ended in the overload defaulting {@code
 * executionStore} to {@code null}. That one made the other three moot — a primary path that writes
 * through the store writes through nothing when no store is composed.</p>
 *
 * <h2>Why this is a sealed interface and not a record with a flag</h2>
 * <p>It used to be {@code record (boolean enabled, SqliteStoreLocation location)}, and the record
 * component <em>was</em> the single-host type. That shape can express "on here" and "off here" and
 * nothing else: adding a second adapter to it means either a nullable location beside a nullable
 * connection — two fields of which exactly one is ever set, checked by convention — or an enum plus
 * a bag of optionals that every reader has to re-validate. A sealed hierarchy says the same thing
 * with the compiler enforcing it: each variant carries exactly the settings its own adapter needs,
 * a switch over the three is exhaustive, and a fourth adapter cannot be added without every
 * selection site being told about it.</p>
 *
 * <h2>Enabled by default, and the negative aliases mean what they always meant</h2>
 * <p>An opt-in store would leave persistence conditional: its dependency would be
 * "satisfiable by configuration" rather than satisfied. So the store is on unless explicitly
 * disabled, and the single-host variant uses the directory convention {@code ravenroot-cli}'s
 * backup/restore already established, so a server and the CLI that backs it up do not have to be
 * told the same path twice.</p>
 *
 * <p>{@link Disabled} disables execution persistence, but the configured single-host location remains
 * the process's maintenance authority. The server must still exclude backup/restore while its audit
 * trail is live; disabling one store cannot make the other safe to replace underneath it.</p>
 */
public sealed interface ExecutionStoreConfiguration {

    /**
     * Which adapter to compose: {@code sqlite} (the default) or {@code postgresql}.
     *
     * <p>Named without an adapter word of its own, deliberately. An operator selects a
     * <em>topology</em> — one host or several — and the adapter is what that topology needs; a
     * variable called {@code ..._ADAPTER} would invite a third value naming an implementation that
     * has no matching deployment shape.</p>
     */
    String SELECTOR_VARIABLE = "RAVENROOT_EXECUTION_STORE";
    String SELECTOR_PROPERTY = "ravenroot.execution-store";

    /** Set to {@code true}, or to {@code false} (or {@code off}/{@code 0}/{@code no}). */
    String ENABLED_VARIABLE = "RAVENROOT_EXECUTION_STORE_ENABLED";

    /** Shared with {@code ravenroot-cli}'s backup/restore, deliberately spelled the same. */
    String DIRECTORY_VARIABLE = "RAVENROOT_EXECUTION_STORE_DIR";

    /** The shared database's JDBC URL. Required by, and only by, the shared store. */
    String URL_VARIABLE = "RAVENROOT_EXECUTION_STORE_URL";

    /** The shared database's role, when the URL does not already carry one. */
    String USER_VARIABLE = "RAVENROOT_EXECUTION_STORE_USER";

    /** The shared database's password, when the deployment authenticates with one. */
    String PASSWORD_VARIABLE = "RAVENROOT_EXECUTION_STORE_PASSWORD";

    /** Largest number of connections this replica's pool opens against the shared database. */
    String POOL_SIZE_VARIABLE = "RAVENROOT_EXECUTION_STORE_POOL_SIZE";
    String POOL_SIZE_PROPERTY = "ravenroot.execution-store.pool-size";

    /** How long a caller waits for a pooled connection before the operation is reported unavailable. */
    String POOL_TIMEOUT_VARIABLE = "RAVENROOT_EXECUTION_STORE_POOL_TIMEOUT_MS";
    String POOL_TIMEOUT_PROPERTY = "ravenroot.execution-store.pool-timeout-ms";

    /** Lost-race repair attempts for write-once PostgreSQL execution manifests. */
    String MANIFEST_PIN_ATTEMPTS_VARIABLE = "RAVENROOT_EXECUTION_MANIFEST_PIN_ATTEMPTS";

    int DEFAULT_MANIFEST_PIN_ATTEMPTS = PostgresExecutionManifestStore.DEFAULT_MAX_PIN_ATTEMPTS;

    /** The selector value naming the single-host store; also the value assumed when unset. */
    String SQLITE_SELECTOR = "sqlite";

    /** The selector value naming the shared store. */
    String POSTGRESQL_SELECTOR = "postgresql";

    /** The single-host directory used when {@link #DIRECTORY_VARIABLE} is unset. */
    String DEFAULT_DIRECTORY = SqliteStoreLocation.DEFAULT_DIRECTORY;

    /** The enabled state selected when {@link #ENABLED_VARIABLE} is absent or blank. */
    static final String DEFAULT_ENABLED_VALUE = "true";

    /**
     * No execution store at all, with a single-host location retained as maintenance-lock authority.
     *
     * @param location the directory whose maintenance lease this process still holds.
     */
    record Disabled(SqliteStoreLocation location) implements ExecutionStoreConfiguration {
        public Disabled {
            Objects.requireNonNull(location, "location");
        }
    }

    /**
     * The single-host store: one SQLite database, on this process's own filesystem.
     *
     * @param location the directory holding the execution, definition and manifest databases.
     */
    record SingleHost(SqliteStoreLocation location) implements ExecutionStoreConfiguration {
        public SingleHost {
            Objects.requireNonNull(location, "location");
        }
    }

    /**
     * The shared store: one PostgreSQL database several replicas address.
     *
     * @param connection everything needed to build a pool against that database.
     */
    record Shared(SharedStoreConnection connection, int manifestPinAttempts, PostgresStoreConfig storeConfig)
            implements ExecutionStoreConfiguration {
        public Shared {
            Objects.requireNonNull(connection, "connection");
            Objects.requireNonNull(storeConfig, "storeConfig");
            if (manifestPinAttempts < 1) {
                throw new IllegalArgumentException("manifestPinAttempts must be positive");
            }
        }

        public Shared(SharedStoreConnection connection) {
            this(connection, DEFAULT_MANIFEST_PIN_ATTEMPTS, PostgresStoreConfig.defaults());
        }

        public Shared(SharedStoreConnection connection, int manifestPinAttempts) {
            this(connection, manifestPinAttempts, PostgresStoreConfig.defaults());
        }
    }

    /**
     * Reads the selection an operator made, refusing anything it cannot read exactly.
     *
     * @param environment the process environment.
     * @return the configuration to hand {@link ExecutionStoreBootstrap}.
     * @throws IllegalArgumentException when any setting is malformed or two settings contradict.
     */
    static ExecutionStoreConfiguration fromEnvironment(Map<String, String> environment) {
        return fromSources(Map.of(), environment);
    }

    /** Resolves system properties before environment variables; blank values delegate. */
    static ExecutionStoreConfiguration fromSystem(Properties properties, Map<String, String> environment) {
        Objects.requireNonNull(properties, "properties");
        Map<String, String> propertyValues = properties.stringPropertyNames().stream()
                .collect(java.util.stream.Collectors.toMap(name -> name, properties::getProperty));
        return fromSources(propertyValues, environment);
    }

    private static ExecutionStoreConfiguration fromSources(Map<String, String> properties,
                                                           Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String selector = selectorIn(properties, environment);
        if (!POSTGRESQL_SELECTOR.equals(selector)
                && postgresqlOnlyPolicyConfigured(properties, environment)) {
            throw new IllegalArgumentException("PostgreSQL policy requires " + SELECTOR_VARIABLE
                    + "=" + POSTGRESQL_SELECTOR);
        }
        if (!POSTGRESQL_SELECTOR.equals(selector)
                && isConfigured(environment, MANIFEST_PIN_ATTEMPTS_VARIABLE)) {
            throw new IllegalArgumentException(MANIFEST_PIN_ATTEMPTS_VARIABLE + " requires "
                    + SELECTOR_VARIABLE + "=" + POSTGRESQL_SELECTOR);
        }
        if (!enabledIn(environment)) {
            if (POSTGRESQL_SELECTOR.equals(selector)) {
                // Refused rather than resolved in favour of one of the two. "Off" is not simply
                // "no store": it is "no store, and the single-host directory is still this process's
                // maintenance authority", which is a file lock in a directory an operator who
                // selected the shared store never configured and does not expect to be excluded on.
                // Silently ignoring the selector would hand them exactly that; silently ignoring the
                // disable would compose a store they asked not to have. Neither is a decision this
                // parser is entitled to make.
                throw new IllegalArgumentException(SELECTOR_VARIABLE + " is '" + POSTGRESQL_SELECTOR
                        + "' while " + ENABLED_VARIABLE + " disables the store; the shared store has "
                        + "no disabled form, so choose one: unset " + SELECTOR_VARIABLE
                        + " to keep the single-host maintenance authority, or remove "
                        + ENABLED_VARIABLE);
            }
            return new Disabled(singleHostLocation(environment));
        }
        return switch (selector) {
            case SQLITE_SELECTOR -> new SingleHost(singleHostLocation(environment));
            case POSTGRESQL_SELECTOR -> {
                PostgresStoreConfig config = PostgresStoreConfiguration.fromSources(properties, environment);
                yield new Shared(SharedStoreConnection.fromSources(properties, environment, config),
                    positiveInt(environment, MANIFEST_PIN_ATTEMPTS_VARIABLE,
                            DEFAULT_MANIFEST_PIN_ATTEMPTS), config);
            }
            // Unreachable: selectorIn rejects everything else. Present because the switch is over a
            // String and a future third selector must fail here rather than fall through to null.
            default -> throw new IllegalArgumentException(SELECTOR_VARIABLE + " is not supported");
        };
    }

    /** No execution store, with the default directory retained as maintenance-lock authority. */
    static ExecutionStoreConfiguration disabled() {
        return new Disabled(SqliteStoreLocation.underDirectory(Path.of(DEFAULT_DIRECTORY)));
    }

    /**
     * Absent or blank selects the single-host store, so a deployment that never heard of this
     * variable keeps exactly the store it had. Every other value is rejected, in the spirit of
     * {@code ReplicaCount}: a deployment that mistyped which store it runs is a deployment whose
     * durability posture is unverified, and guessing the safest-looking one is how that stops being
     * checked. Case is folded because {@code Postgresql} and {@code POSTGRESQL} are the same
     * intention, and refusing them would be pedantry rather than caution.
     */
    private static String selectorIn(Map<String, String> properties, Map<String, String> environment) {
        String raw = properties.get(SELECTOR_PROPERTY);
        if (raw == null || raw.isBlank()) raw = environment.get(SELECTOR_VARIABLE);
        if (raw == null || raw.isBlank()) {
            return SQLITE_SELECTOR;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!SQLITE_SELECTOR.equals(value) && !POSTGRESQL_SELECTOR.equals(value)) {
            // The operator's own value is not echoed: it reaches stderr and a log aggregator from
            // here, and the useful part is the set of values that work.
            throw new IllegalArgumentException(SELECTOR_VARIABLE + " must be '" + SQLITE_SELECTOR
                    + "' or '" + POSTGRESQL_SELECTOR + "'");
        }
        return value;
    }

    private static SqliteStoreLocation singleHostLocation(Map<String, String> environment) {
        return SqliteStoreLocation.underConfiguredDirectory(environment.get(DIRECTORY_VARIABLE));
    }

    /**
     * Absent or blank means enabled. The canonical positive is {@code true}; the four documented
     * negative aliases disable the store. Every other nonblank value is rejected so a typo cannot
     * silently choose either durability posture.
     */
    private static boolean enabledIn(Map<String, String> environment) {
        String raw = environment.get(ENABLED_VARIABLE);
        String selected = raw == null || raw.isBlank()
                ? DEFAULT_ENABLED_VALUE
                : raw.trim().toLowerCase(Locale.ROOT);
        return switch (selected) {
            case "true" -> true;
            case "false", "off", "0", "no" -> false;
            default -> throw new IllegalArgumentException(ENABLED_VARIABLE
                    + " must be 'true', 'false', 'off', '0', or 'no'");
        };
    }

    /** A blank value has the same meaning as an absent optional environment setting. */
    private static boolean isConfigured(Map<String, String> environment, String variable) {
        String raw = environment.get(variable);
        return raw != null && !raw.isBlank();
    }

    private static boolean postgresqlOnlyPolicyConfigured(Map<String, String> properties,
                                                          Map<String, String> environment) {
        return PostgresStoreConfiguration.anyConfigured(properties, environment)
                || isConfigured(properties, POOL_SIZE_PROPERTY)
                || isConfigured(properties, POOL_TIMEOUT_PROPERTY);
    }

    private static int positiveInt(Map<String, String> environment, String variable, int fallback) {
        String raw = environment.get(variable);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(variable + " must be a positive integer");
        }
    }
}
