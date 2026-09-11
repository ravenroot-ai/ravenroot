package ai.ravenroot.server.audit;

import ai.ravenroot.core.audit.AuditTrailDirectory;

import java.util.Map;
import java.util.Objects;

/** Reads the server composition root's audit directory from its environment. */
public record AuditTrailConfiguration(AuditTrailDirectory directory) {

    public static final String DIRECTORY_VARIABLE = AuditTrailDirectory.ENVIRONMENT_VARIABLE;

    public AuditTrailConfiguration {
        Objects.requireNonNull(directory, "directory");
    }

    public static AuditTrailConfiguration fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return new AuditTrailConfiguration(AuditTrailDirectory.resolve(environment.get(DIRECTORY_VARIABLE)));
    }
}
