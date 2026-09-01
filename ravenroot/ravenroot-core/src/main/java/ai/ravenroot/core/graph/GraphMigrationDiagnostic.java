package ai.ravenroot.core.graph;

import java.util.Objects;

/** Stable, machine-testable diagnostic emitted by compatibility and migration analysis. */
public record GraphMigrationDiagnostic(Severity severity, String code, String message) {
    public GraphMigrationDiagnostic {
        Objects.requireNonNull(severity, "severity");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Diagnostic code must be non-blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Diagnostic message must be non-blank");
        }
    }

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }
}
