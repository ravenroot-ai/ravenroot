package ai.ravenroot.cli;

import ai.ravenroot.persistence.sqlite.SqliteStoreLocation;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

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
 * <p>Neither variable is wired into {@code RavenrootServerMain} or {@code RavenrootCliMain}'s
 * ordinary startup path today -- {@code RAVENROOT_AUDIT_DIR} is (default {@code ./data/audit},
 * {@code RavenrootServerMain.java}), reused verbatim here so a backup taken against the default
 * server deployment finds the same directory without extra configuration; the execution-store
 * directory has no existing default to match, because no {@code ExecutionStore} is composed into
 * either composition root today (PLAT-02).</p>
 */
public record BackupRestoreConfiguration(Path auditDirectory, SqliteStoreLocation executionStoreLocation) {

    public static final String AUDIT_DIR_VARIABLE = "RAVENROOT_AUDIT_DIR";
    public static final String EXECUTION_STORE_DIR_VARIABLE = "RAVENROOT_EXECUTION_STORE_DIR";

    private static final String DEFAULT_AUDIT_DIR = "./data/audit";
    private static final String DEFAULT_EXECUTION_STORE_DIR = "./data/execution-store";

    public BackupRestoreConfiguration {
        Objects.requireNonNull(auditDirectory, "auditDirectory");
        Objects.requireNonNull(executionStoreLocation, "executionStoreLocation");
    }

    public static BackupRestoreConfiguration fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        Path auditDirectory = Path.of(nonBlankOrDefault(environment, AUDIT_DIR_VARIABLE, DEFAULT_AUDIT_DIR));
        Path executionStoreDirectory = Path.of(
                nonBlankOrDefault(environment, EXECUTION_STORE_DIR_VARIABLE, DEFAULT_EXECUTION_STORE_DIR));
        return new BackupRestoreConfiguration(auditDirectory, SqliteStoreLocation.underDirectory(executionStoreDirectory));
    }

    private static String nonBlankOrDefault(Map<String, String> environment, String variable, String fallback) {
        String raw = environment.get(variable);
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }
}
