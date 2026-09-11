package ai.ravenroot.server.persistence;

import ai.ravenroot.persistence.postgresql.PostgresStoreConfig;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the composition root needs to build a pool against the shared database, and nothing the
 * adapter is ever shown.
 *
 * <h2>Why the adapter never sees any of this</h2>
 * <p>The PostgreSQL adapter takes a {@link javax.sql.DataSource} and, by its own documented contract,
 * never inspects it for a URL or a credential. Keeping the URL, role and password in a server-side
 * record preserves that: the connection details are deployment configuration, they travel to exactly
 * one file ({@link SharedExecutionStoreDataSource}), and no persistence code can grow a dependency on
 * their shape.</p>
 *
 * <h2>Why {@link #toString()} is overridden</h2>
 * <p>A record's generated {@code toString} prints every component. This one holds a password and a
 * URL that commonly carries one, and the values a record prints end up in exception messages,
 * {@code assertEquals} failures and debugger-driven log lines that nobody reviewed as a disclosure.
 * Redacting here is cheaper than auditing every future call site; there is no legitimate reader of
 * these two values other than the pool builder, which reads the accessors directly.</p>
 *
 * @param url        the JDBC URL of the shared database.
 * @param user       the role to authenticate as, empty when the URL or the server's own
 *                   authentication method already determines it.
 * @param password   the role's password, empty when the deployment authenticates without one.
 * @param poolSize   the largest number of connections this replica opens.
 * @param poolTimeout how long a caller waits for a connection from the pool.
 */
public record SharedStoreConnection(String url, Optional<String> user, Optional<String> password,
                                    int poolSize, Duration poolTimeout) {

    /**
     * The only URL scheme this selector can mean.
     *
     * <p>Checked here rather than left to the driver. A URL for some other database reaches
     * {@code DriverManager}, fails to match any registered driver, and surfaces as a message quoting
     * the URL — which is the one string that must not reach a log. Refusing on the prefix turns that
     * into a startup refusal that names the variable and prints nothing.</p>
     */
    private static final String REQUIRED_URL_PREFIX = "jdbc:postgresql:";

    /**
     * Hikari's own default, kept rather than replaced. It is a defensible floor for a single replica's
     * concurrency, and choosing a different number here would be inventing a recommendation the
     * operator documentation deliberately does not make — it tells an operator to size the pool from
     * measured concurrency and instance count, which this process cannot know.
     */
    private static final int DEFAULT_POOL_SIZE = 10;

    /**
     * A ceiling on the typo, not on the deployment. PostgreSQL connections are server processes, so a
     * pool of ten thousand is never a deployment decision — it is a missing decimal point, and it
     * presents as the shared database refusing connections to every replica at once rather than as a
     * configuration error on the one that typed it.
     */
    private static final int MAX_POOL_SIZE = 1_000;

    /**
     * Ten seconds: long enough to ride out a brief burst against a correctly sized pool, and well
     * below the ceiling below. A caller waiting indefinitely for a connection is indistinguishable,
     * from outside, from one waiting on a row lock, and the two need opposite operator responses.
     */
    private static final Duration DEFAULT_POOL_TIMEOUT = Duration.ofSeconds(10);

    /** HikariCP refuses anything shorter, so a smaller value is rejected here where it can be explained. */
    private static final Duration MIN_POOL_TIMEOUT = Duration.ofMillis(250);

    /**
     * The adapter's own statement timeout, exclusive.
     *
     * <p>Read from {@link PostgresStoreConfig#defaults()} rather than written as a constant, because
     * that is the configuration {@code ExecutionStoreBootstrap} actually composes the shared stores
     * with, and a constant here would be a second copy that could fall behind it.</p>
     *
     * <p>Strictly below it, because a wait for a connection and a wait for a row lock are different
     * diagnoses that look identical from outside: past this point a caller blocked in the pool
     * outlives the statement bound the store publishes, so a saturated pool would present as a slow
     * database. The operator documentation states this as guidance; enforcing it turns the guidance
     * into something a deployment cannot quietly get wrong.</p>
     */
    public SharedStoreConnection {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(poolTimeout, "poolTimeout");
        if (url.isBlank()) {
            throw new IllegalArgumentException("url cannot be blank");
        }
        if (poolSize < 1 || poolSize > MAX_POOL_SIZE) {
            throw new IllegalArgumentException("poolSize must be between 1 and " + MAX_POOL_SIZE);
        }
        if (poolTimeout.compareTo(MIN_POOL_TIMEOUT) < 0) {
            throw new IllegalArgumentException("poolTimeout must be at least " + MIN_POOL_TIMEOUT);
        }
    }

    /**
     * Reads the shared store's connection settings, refusing an incomplete or malformed set.
     *
     * @param environment the process environment.
     * @return the settings the pool is built from.
     * @throws IllegalArgumentException when a required setting is absent or a supplied one is malformed.
     */
    public static SharedStoreConnection fromEnvironment(Map<String, String> environment) {
        return fromSources(Map.of(), environment, PostgresStoreConfig.defaults());
    }

    static SharedStoreConnection fromSources(Map<String, String> properties,
                                             Map<String, String> environment,
                                             PostgresStoreConfig storeConfig) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(storeConfig, "storeConfig");
        String url = trimmed(environment, ExecutionStoreConfiguration.URL_VARIABLE)
                .orElseThrow(() -> new IllegalArgumentException(
                        ExecutionStoreConfiguration.URL_VARIABLE + " is required when "
                                + ExecutionStoreConfiguration.SELECTOR_VARIABLE + " is '"
                                + ExecutionStoreConfiguration.POSTGRESQL_SELECTOR + "'"));
        if (!url.startsWith(REQUIRED_URL_PREFIX)) {
            throw new IllegalArgumentException(ExecutionStoreConfiguration.URL_VARIABLE
                    + " must be a '" + REQUIRED_URL_PREFIX + "' URL");
        }
        SharedStoreConnection connection = new SharedStoreConnection(url,
                trimmed(environment, ExecutionStoreConfiguration.USER_VARIABLE),
                // Not trimmed and not rejected when blank: a password is opaque, and a deployment
                // whose secret legitimately begins or ends with whitespace must not have it silently
                // altered on the way to the database. The other three settings are identifiers, where
                // trimming a stray newline from a mounted file is a kindness rather than a corruption.
                Optional.ofNullable(environment.get(ExecutionStoreConfiguration.PASSWORD_VARIABLE)),
                poolSize(properties, environment), poolTimeout(properties, environment,
                        storeConfig.statementTimeout()));
        if (connection.poolTimeout().compareTo(storeConfig.statementTimeout()) >= 0) {
            throw new IllegalArgumentException(ExecutionStoreConfiguration.POOL_TIMEOUT_VARIABLE + " / "
                    + ExecutionStoreConfiguration.POOL_TIMEOUT_PROPERTY
                    + " must be shorter than the configured PostgreSQL statement timeout");
        }
        return connection;
    }

    /**
     * Redacts the two secret-bearing components.
     *
     * @return a description safe to put in an exception message or a log line.
     */
    @Override
    public String toString() {
        return "SharedStoreConnection[url=<redacted>, user=" + (user.isPresent() ? "<set>" : "<unset>")
                + ", password=" + (password.isPresent() ? "<set>" : "<unset>")
                + ", poolSize=" + poolSize + ", poolTimeout=" + poolTimeout + "]";
    }

    private static Optional<String> trimmed(Map<String, String> environment, String variable) {
        String raw = environment.get(variable);
        return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(raw.trim());
    }

    private static int poolSize(Map<String, String> properties, Map<String, String> environment) {
        Optional<String> raw = selected(properties, environment,
                ExecutionStoreConfiguration.POOL_SIZE_PROPERTY,
                ExecutionStoreConfiguration.POOL_SIZE_VARIABLE);
        if (raw.isEmpty()) {
            return DEFAULT_POOL_SIZE;
        }
        try {
            int value = Integer.parseInt(raw.get());
            if (value < 1 || value > MAX_POOL_SIZE) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(ExecutionStoreConfiguration.POOL_SIZE_VARIABLE
                    + " must be an integer between 1 and " + MAX_POOL_SIZE);
        }
    }

    private static Duration poolTimeout(Map<String, String> properties, Map<String, String> environment,
                                        Duration statementTimeout) {
        Optional<String> raw = selected(properties, environment,
                ExecutionStoreConfiguration.POOL_TIMEOUT_PROPERTY,
                ExecutionStoreConfiguration.POOL_TIMEOUT_VARIABLE);
        if (raw.isEmpty()) {
            return DEFAULT_POOL_TIMEOUT;
        }
        try {
            Duration value = Duration.ofMillis(Long.parseLong(raw.get()));
            if (value.compareTo(MIN_POOL_TIMEOUT) < 0 || value.compareTo(statementTimeout) >= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(ExecutionStoreConfiguration.POOL_TIMEOUT_VARIABLE
                    + " must be a whole number of milliseconds, at least " + MIN_POOL_TIMEOUT.toMillis()
                    + " and below the store's configured statement timeout of "
                    + statementTimeout.toMillis());
        }
    }

    private static Optional<String> selected(Map<String, String> properties,
                                             Map<String, String> environment,
                                             String property, String variable) {
        String raw = properties.get(property);
        if (raw != null && !raw.isBlank()) return Optional.of(raw.trim());
        return trimmed(environment, variable);
    }
}
