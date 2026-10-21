package ai.ravenroot.api.correlation;
import ai.ravenroot.api.security.SecurityContext;
import java.util.*;
/** Cross-process causal link, separate from the parent invocation set inside one traversal.
 * @param processInstanceId original owning process identity, independent from a business history
 * @param traversalId actual traversal identity within the owning process
 * @param invocationId actual node-visit identity that owns this observation
 * @param attemptId actual delivery-attempt identity within the visit
 * @param security authenticated identity retained for the original causal admission
 */
public record JoinCause(UUID processInstanceId, UUID traversalId, UUID invocationId, UUID attemptId,
                         SecurityContext security) {
    /** Validates the original causal process, traversal, visit, attempt and authenticated identity. */
    public JoinCause {
        Objects.requireNonNull(processInstanceId); Objects.requireNonNull(traversalId);
        Objects.requireNonNull(invocationId); Objects.requireNonNull(attemptId); Objects.requireNonNull(security);
    }
    /** Retains the original causal process, traversal, visit, attempt and security of a contribution.
 * @param contribution optional pointer selecting a distinct business contribution
 * @return original contribution causal coordinates and original security identity
 */
    public static JoinCause of(JoinContribution contribution) {
        return new JoinCause(contribution.processInstanceId(), contribution.traversalId(), contribution.invocationId(),
                contribution.attemptId(), contribution.security());
    }
}
