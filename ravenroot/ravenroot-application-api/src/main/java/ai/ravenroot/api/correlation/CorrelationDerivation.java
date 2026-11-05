package ai.ravenroot.api.correlation;
import java.util.*;
/** Durable original causal coordinates for an explicitly authored application-context derivation.
 * @param processInstanceId original process, never rewritten as the derived process
 * @param traversalId original traversal
 * @param invocationId actual original derivation visit
 * @param attemptId actual original derivation attempt
 * @param auditReason bounded authored non-secret reason for creating the new business identity
 */
public record CorrelationDerivation(UUID processInstanceId, UUID traversalId, UUID invocationId, UUID attemptId,
        String auditReason) {
    /** Requires original causal identities and an explicit bounded audit reason. */
    public CorrelationDerivation {
        Objects.requireNonNull(processInstanceId); Objects.requireNonNull(traversalId); Objects.requireNonNull(invocationId); Objects.requireNonNull(attemptId);
        if (auditReason == null || auditReason.isBlank() || auditReason.length() > 500 || auditReason.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid context derivation reason");
    }
}
