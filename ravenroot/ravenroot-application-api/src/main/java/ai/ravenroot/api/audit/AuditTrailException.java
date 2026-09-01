package ai.ravenroot.api.audit;

import java.util.Objects;

/**
 * Thrown by {@link AuditTrail} write-path operations. A {@link RuntimeException} deliberately: every
 * existing audit sink interface ({@code AuthorizationAuditSink}, {@code ArtifactLifecycleAuditSink},
 * {@code RateLimitAuditSink}) is a functional interface declaring no checked exception, and their
 * existing call sites already fail closed on an unchecked exception from the sink (see
 * {@code DefaultAuthorizationService.decide} and {@code AuthorizedRavenrootApplication}). A checked
 * exception here would force every existing call site to change; this does not.
 */
public final class AuditTrailException extends RuntimeException {
/**
 * Rejects a {@code null} failure descriptor while retaining only sanitized failure data.
 */
    private final AuditTrailFailure reason;

/**
 * Creates an exception from a safe textual refusal reason.
 * @param reason safe-to-disclose reason; {@code null} is normalized to an empty string
 */
    public AuditTrailException(AuditTrailFailure reason) {
        super(Objects.requireNonNull(reason, "reason").describe());
        this.reason = reason;
    }

/**
 * Exposes the safe reason associated with this failure.
 * @return non-null sanitized reason text
 */
    public AuditTrailFailure reason() {
        return reason;
    }
}
