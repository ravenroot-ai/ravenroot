package ai.ravenroot.api.correlation;

import java.time.Instant;
import java.util.Optional;
import static ai.ravenroot.api.correlation.BusinessHistoryException.Reason.*;

/** Shared adapter validation: CAS cannot erase contributions, change a release or extend a deadline. */
public final class CorrelatedJoinTransitions {
    private CorrelatedJoinTransitions() { }
    /** Validates monotone join transitions against the retained checkpoint, policy and current deadline.
 * @param stored currently retained immutable join checkpoint, absent when creating the first revision
 * @param desired prospective immutable join state with the next compare-and-set revision
 * @param policy operator-owned contribution, payload, deadline and revision-retry bounds
 * @param now operation time used to validate deadlines and record the change
 */
    public static void require(Optional<CorrelatedJoinRecord> stored, CorrelatedJoinRecord desired,
                                BusinessHistoryPolicy policy, Instant now) {
        if (desired.expectedIds().size() > policy.maxContributionsPerJoin()
                || desired.contributions().size() > policy.maxContributionsPerJoin()
                || desired.expectedCount() != null && desired.expectedCount() > policy.maxContributionsPerJoin()
                || desired.payloadBytes() > policy.maxJoinPayloadBytes()
                || desired.deadlineAt().isAfter(desired.openedAt().plus(policy.joinDeadline())))
            throw new BusinessHistoryException(RESOURCE_LIMIT);
        if (stored.isEmpty()) {
            if (desired.revision() != 1) throw new BusinessHistoryException(REVISION_CONFLICT);
            if (desired.phase() != CorrelatedJoinRecord.Phase.OPEN && desired.phase() != CorrelatedJoinRecord.Phase.READY)
                throw new BusinessHistoryException(INVALID_REQUEST);
            return;
        }
        var prior = stored.orElseThrow();
        if (desired.revision() != prior.revision() + 1) throw new BusinessHistoryException(REVISION_CONFLICT);
        if (!desired.key().equals(prior.key()) || !desired.openedAt().equals(prior.openedAt())
                || !desired.openedBy().equals(prior.openedBy()) || !desired.deadlineAt().equals(prior.deadlineAt()) || desired.updatedAt().isBefore(prior.updatedAt())
                || !desired.contributions().entrySet().containsAll(prior.contributions().entrySet())
                || !desired.expectedIds().containsAll(prior.expectedIds()) || prior.sealed() && !desired.sealed()
                || prior.expectedCount() != null && !prior.expectedCount().equals(desired.expectedCount())
                || prior.sealed() && !prior.expectedIds().equals(desired.expectedIds()))
            throw new BusinessHistoryException(CONTEXT_CONFLICT);
        if (prior.phase() == CorrelatedJoinRecord.Phase.READY) {
            if (desired.phase() != CorrelatedJoinRecord.Phase.DISPATCHED
                    && desired.phase() != CorrelatedJoinRecord.Phase.CANCELLED
                    && desired.phase() != CorrelatedJoinRecord.Phase.FAILED
                    && desired.phase() != CorrelatedJoinRecord.Phase.EXPIRED
                    || !prior.contributions().equals(desired.contributions())) throw new BusinessHistoryException(CLOSED_HISTORY);
        } else if (prior.phase() != CorrelatedJoinRecord.Phase.OPEN) throw new BusinessHistoryException(CLOSED_HISTORY);
        if (!now.isBefore(prior.deadlineAt()) && desired.phase() != CorrelatedJoinRecord.Phase.EXPIRED
                && desired.phase() != CorrelatedJoinRecord.Phase.DISPATCHED)
            throw new BusinessHistoryException(EXPIRED_HISTORY);
    }
}
