package ai.ravenroot.core.audit;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The durable audit trail's deployment directory.
 *
 * <p>This type deliberately resolves one raw value rather than reading an environment or property
 * source. Composition roots remain responsible for choosing their configuration source, while the
 * server and the offline backup CLI share the same default, blank handling and path parsing.</p>
 *
 * @param path the directory handed to {@link FileAuditTrail}
 */
public record AuditTrailDirectory(Path path) {

    public static final String ENVIRONMENT_VARIABLE = "RAVENROOT_AUDIT_DIR";
    public static final String DEFAULT_DIRECTORY = "./data/audit";

    public AuditTrailDirectory {
        Objects.requireNonNull(path, "path");
    }

    /** Absent or blank selects the default; a supplied path is trimmed before parsing. */
    public static AuditTrailDirectory resolve(String raw) {
        String selected = raw == null || raw.isBlank() ? DEFAULT_DIRECTORY : raw.trim();
        try {
            return new AuditTrailDirectory(Path.of(selected));
        } catch (RuntimeException invalidPath) {
            // InvalidPathException includes the supplied value. A startup diagnostic must identify
            // the setting without copying an operator-provided path into logs.
            throw new IllegalArgumentException("Invalid audit directory configuration");
        }
    }
}
