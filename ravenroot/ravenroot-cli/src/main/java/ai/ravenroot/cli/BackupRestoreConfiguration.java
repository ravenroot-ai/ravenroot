package ai.ravenroot.cli;

import ai.ravenroot.core.audit.AuditTrailDirectory;
import ai.ravenroot.persistence.sqlite.SqliteStoreLocation;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Where the two durable stores {@code backup`/`restore} act on actually live, read
 * the same {@code fromEnvironment(Map)} way every other composition-root configuration in this
 * codebase is read ({@code AuthenticationConfiguration}, {@code RateLimitConfiguration},
 * {@code TelemetryConfiguration}, {@code ReadinessConfiguration}, {@code NodePackageLoader}).
 * Decides nothing about where platform configuration lives generally: both variables here name a
 * location the codebase already
 * has a fixed, well-defined meaning for ({@link SqliteStoreLocation}, {@code FileAuditTrail}'s
 * directory), not a new configuration surface.
 *
 * <p>The server and this offline tool intentionally share the same typed audit-directory resolver
 * and the same single-host execution-store directory rule. A backup taken against an unconfigured
 * server therefore finds the same stores without a second deployment decision.</p>
 */
public record BackupRestoreConfiguration(Path auditDirectory, SqliteStoreLocation executionStoreLocation) {

    public static final String AUDIT_DIR_VARIABLE = AuditTrailDirectory.ENVIRONMENT_VARIABLE;
    public static final String EXECUTION_STORE_DIR_VARIABLE = "RAVENROOT_EXECUTION_STORE_DIR";

    /**
     * Which execution store the deployment selected, spelled the same as the server's own
     * {@code ExecutionStoreConfiguration.SELECTOR_VARIABLE} and duplicated here for the same reason
     * the two variables above are: {@code ravenroot-server} is a test-scope dependency of this
     * module, so the constant cannot be imported, and a bundle command has to read the same
     * deployment configuration the server it backs up reads. {@code SharedStoreBundleRefusalTest}
     * asserts the two spellings against each other so they cannot drift apart silently.
     */
    public static final String STORE_SELECTOR_VARIABLE = "RAVENROOT_EXECUTION_STORE";
    public static final String STORE_SELECTOR_PROPERTY = "ravenroot.execution-store";

    /** The selector value naming the shared store; likewise spelled the same as the server's. */
    public static final String SHARED_STORE_SELECTOR = "postgresql";

    public BackupRestoreConfiguration {
        Objects.requireNonNull(auditDirectory, "auditDirectory");
        Objects.requireNonNull(executionStoreLocation, "executionStoreLocation");
    }

    public static BackupRestoreConfiguration fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        Path auditDirectory = AuditTrailDirectory.resolve(environment.get(AUDIT_DIR_VARIABLE)).path();
        SqliteStoreLocation executionStoreLocation = SqliteStoreLocation.underConfiguredDirectory(
                environment.get(EXECUTION_STORE_DIR_VARIABLE));
        return new BackupRestoreConfiguration(auditDirectory, executionStoreLocation);
    }

    /**
     * Whether this deployment's execution store is the shared one.
     *
     * <p>Read rather than inferred from the directory variable, which keeps its default and therefore
     * always names a plausible-looking directory whether or not the deployment uses one.</p>
     *
     * <p>Case is folded and the value is not otherwise validated here. The server is the authority on
     * whether a selector is well formed and refuses a malformed one at startup; this command's only
     * question is whether the operator asked for the shared store, and answering "no" for a
     * misspelling is the safe direction — it lets a single-host bundle command run, which is what a
     * deployment with no valid shared selector has.</p>
     *
     * @param environment the process environment.
     * @return {@code true} when the shared store is selected.
     */
    public static boolean sharedStoreSelected(Map<String, String> environment) {
        return sharedStoreSelected(new Properties(), environment);
    }

    /** Resolves the same property-over-environment selector precedence as the server. */
    public static boolean sharedStoreSelected(Properties properties, Map<String, String> environment) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(environment, "environment");
        String raw = properties.getProperty(STORE_SELECTOR_PROPERTY);
        if (raw == null || raw.isBlank()) raw = environment.get(STORE_SELECTOR_VARIABLE);
        return raw != null && SHARED_STORE_SELECTOR.equals(raw.trim().toLowerCase(java.util.Locale.ROOT));
    }

}
