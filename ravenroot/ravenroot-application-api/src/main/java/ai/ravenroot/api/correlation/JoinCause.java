package ai.ravenroot.api.correlation;
import ai.ravenroot.api.security.SecurityContext;
import java.util.*;
/** Cross-process causal link, separate from the parent invocation set inside one traversal. */
public record JoinCause(UUID processInstanceId, UUID traversalId, UUID invocationId, UUID attemptId,
                         SecurityContext security) {
    public JoinCause {
        Objects.requireNonNull(processInstanceId); Objects.requireNonNull(traversalId);
        Objects.requireNonNull(invocationId); Objects.requireNonNull(attemptId); Objects.requireNonNull(security);
    }
    public static JoinCause of(JoinContribution contribution) {
        return new JoinCause(contribution.processInstanceId(), contribution.traversalId(), contribution.invocationId(),
                contribution.attemptId(), contribution.security());
    }
}
