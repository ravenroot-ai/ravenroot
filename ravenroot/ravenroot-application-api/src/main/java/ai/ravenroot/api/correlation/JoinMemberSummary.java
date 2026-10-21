package ai.ravenroot.api.correlation;

import java.time.Instant;
import java.util.UUID;

/** Payload-free expectation/arrival metadata preserving the first accepted causal coordinates.
 * @param contributionId distinct business item identity
 * @param expected whether the authored/trusted manifest declares this item
 * @param received whether this distinct item was accepted
 * @param kind optional authored item category
 * @param processInstanceId first original contributing process, absent before arrival
 * @param traversalId first original contributing traversal, absent before arrival
 * @param invocationId first original contributing visit, absent before arrival
 * @param attemptId first original contributing delivery attempt, absent before arrival
 * @param acceptedAt first accepted contribution time, absent before arrival
 */
public record JoinMemberSummary(String contributionId, boolean expected, boolean received, String kind,
        UUID processInstanceId, UUID traversalId, UUID invocationId, UUID attemptId, Instant acceptedAt) {
    /** Validates complete original arrival coordinates without requiring retained payload content. */
    public JoinMemberSummary {
        if (contributionId == null || contributionId.isBlank() || !expected && !received)
            throw new IllegalArgumentException("invalid join member metadata");
        boolean complete = processInstanceId != null && traversalId != null && invocationId != null && attemptId != null && acceptedAt != null;
        boolean absent = processInstanceId == null && traversalId == null && invocationId == null && attemptId == null && acceptedAt == null;
        if (received ? !complete : !absent) throw new IllegalArgumentException("invalid join member causal metadata");
    }
    /** Retains only scalar original arrival facts; no contribution data is copied.
     * @param contribution original accepted contribution
     * @param expected whether its identity appears in the declared manifest
     * @return bounded metadata preserving the original causal coordinates
     */
    public static JoinMemberSummary of(JoinContribution contribution, boolean expected) {
        return new JoinMemberSummary(contribution.contributionId(), expected, true, contribution.kind(),
                contribution.processInstanceId(), contribution.traversalId(), contribution.invocationId(), contribution.attemptId(), contribution.acceptedAt());
    }
    /** Applies a metadata filter without touching retained contribution payloads.
     * @param filter explicit expected, received, missing or all selection
     * @return whether this scalar row belongs in that selection
     */
    public boolean matches(JoinMemberFilter filter) {
        return switch (filter) { case ALL -> true; case EXPECTED -> expected; case RECEIVED -> received; case MISSING -> expected && !received; };
    }
}
