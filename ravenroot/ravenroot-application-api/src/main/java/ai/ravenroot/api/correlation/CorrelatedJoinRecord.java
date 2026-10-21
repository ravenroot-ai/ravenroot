package ai.ravenroot.api.correlation;

import java.time.Instant;
import java.util.*;

/** Immutable CAS snapshot. A READY snapshot is itself the durable continuation outbox intent.
 * @param key exact scoped business join identity, including its node and round
 * @param revision monotone compare-and-set revision of the retained record
 * @param phase durable join collection or continuation-release state
 * @param expectedIds explicit expected manifest members; later announcements cannot remove members
 * @param expectedCount fixed distinct contribution count, absent when completion uses a manifest
 * @param sealed whether the producer has explicitly closed its expectation announcement
 * @param contributions accepted distinct contributions indexed by their business identity
 * @param openedBy original causal admission that owns the independent continuation identity
 * @param openedAt time this join generation was first created
 * @param deadlineAt deadline after which incomplete collection cannot release work
 * @param updatedAt time of the latest recorded change; pagination does not depend on this value
 */
public record CorrelatedJoinRecord(CorrelatedJoinKey key, long revision, Phase phase,
                                   Set<String> expectedIds, Integer expectedCount, boolean sealed,
                                   Map<String, JoinContribution> contributions, JoinCause openedBy, Instant openedAt,
                                   Instant deadlineAt, Instant updatedAt) {
    /** Durable collection and continuation-outbox lifecycle of one explicit join round. */
    public enum Phase {
        /** Collecting contributions while the explicit expectation is incomplete. */
        OPEN,
        /** Completeness and the deterministic continuation intent committed together. */
        READY,
        /** The independent continuation was durably accepted. */
        DISPATCHED,
        /** Collection failed without releasing further work. */
        FAILED,
        /** This generation was cancelled and cannot release into a retired admission. */
        CANCELLED,
        /** The collection deadline or retained payload window expired. */
        EXPIRED
    }
    /** Validates immutable causal ownership, contribution identities and explicit expectation state. */
    public CorrelatedJoinRecord {
        Objects.requireNonNull(key); Objects.requireNonNull(phase);
        expectedIds = Set.copyOf(expectedIds == null ? Set.of() : expectedIds);
        contributions = Collections.unmodifiableMap(new TreeMap<>(contributions == null ? Map.of() : contributions));
        Objects.requireNonNull(openedBy);
        if (!key.scope().tenantId().equals(openedBy.security().tenantId())) throw new IllegalArgumentException("foreign join cause");
        Objects.requireNonNull(openedAt); Objects.requireNonNull(deadlineAt); Objects.requireNonNull(updatedAt);
        if (revision < 1 || expectedIds.size() > 10000 || contributions.size() > 10000
                || expectedCount != null && (expectedCount < 0 || expectedCount > 10000)
                || expectedCount != null && !expectedIds.isEmpty() || !deadlineAt.isAfter(openedAt))
            throw new IllegalArgumentException("invalid correlated join snapshot");
        expectedIds.forEach(id -> new CorrelationLimits(4096, 16384, 256, 10000, ai.ravenroot.api.payload.PayloadLimits.DEFAULTS).requireId(id, "expected contribution"));
        contributions.forEach((id, item) -> {
            if (!id.equals(item.contributionId()) || !key.scope().tenantId().equals(item.security().tenantId()))
                throw new IllegalArgumentException("invalid contribution ownership");
        });
        if ((phase == Phase.READY || phase == Phase.DISPATCHED) && (!sealed || !satisfied(expectedIds, expectedCount, contributions)))
            throw new IllegalArgumentException("release requires a sealed satisfied expectation");
    }
    /** Exactly the distinct expected set/count matters; transport deliveries and predecessor edges do not.
 * @return whether the explicitly known expectation is complete, including an explicitly sealed zero-work manifest
 */
    public boolean satisfied() { return sealed && satisfied(expectedIds, expectedCount, contributions); }
    private static boolean satisfied(Set<String> ids, Integer count, Map<String, JoinContribution> items) {
        return count == null ? items.keySet().equals(ids) : items.size() == count;
    }
    /** Total retained execution data, independently bounded from public summaries and selection input.
 * @return total retained contribution data bytes, excluding public metadata
 */
    public long payloadBytes() { return contributions.values().stream().mapToLong(c -> c.payload().size()).sum(); }
    /** Creates a pending join; a declared zero expectation remains pending until explicitly sealed.
 * @param key exact scoped business join identity, including its node and round
 * @param expected explicit distinct manifest members already known at creation
 * @param count fixed distinct contribution count, absent when completion uses a manifest
 * @param sealed whether the producer has explicitly closed its expectation announcement
 * @param openedBy original causal admission that owns the independent continuation identity
 * @param now operation time used to validate deadlines and record the change
 * @param deadline finite deadline for this join collection generation
 * @return new immutable join generation with its original causal admission and finite deadline
 */
    public static CorrelatedJoinRecord open(CorrelatedJoinKey key, Set<String> expected, Integer count,
                                            boolean sealed, JoinCause openedBy, Instant now, Instant deadline) {
        var initial = new CorrelatedJoinRecord(key, 1, Phase.OPEN, expected, count, sealed, Map.of(), openedBy, now, deadline, now);
        return initial.satisfied() ? initial.withPhase(Phase.READY, 1, now) : initial;
    }
    /** Shared deterministic state decision, reused by all adapters and engines.
 * @param item original distinct contribution, or absent for an authorized producer control signal
 * @param announced trusted producer manifest members added monotonically to the existing expectation
 * @param count fixed distinct contribution count, absent when completion uses a manifest
 * @param seal trusted producer signal that no further manifest members will be announced
 * @param now operation time used to validate deadlines and record the change
 * @param limits operator-owned admission, contribution and retained-payload bounds
 * @return next immutable checkpoint, or the original checkpoint for an identical duplicate delivery
 */
    public CorrelatedJoinRecord contribute(JoinContribution item, Set<String> announced, Integer count,
                                           boolean seal, Instant now, BusinessHistoryPolicy limits) {
        var existing = item == null ? null : contributions.get(item.contributionId());
        if (existing != null) {
            if (!existing.payloadDigest().equals(item.payloadDigest()) || !Objects.equals(existing.kind(), item.kind()))
                throw new BusinessHistoryException(BusinessHistoryException.Reason.CONTEXT_CONFLICT);
            // Exact business duplicates retain the first causal identity and cannot create a new release.
            if (announced.isEmpty() && count == null && !seal) return this;
        }
        if (phase != Phase.OPEN) {
            if (existing != null && (count == null || Objects.equals(expectedCount, count))
                    && expectedIds.containsAll(announced)) return this;
            throw new BusinessHistoryException(BusinessHistoryException.Reason.CLOSED_HISTORY);
        }
        if (!now.isBefore(deadlineAt)) throw new BusinessHistoryException(BusinessHistoryException.Reason.EXPIRED_HISTORY);
        var expected = new TreeSet<>(expectedIds);
        Integer target = expectedCount;
        if (count != null) {
            if (!expected.isEmpty() || target != null && !target.equals(count))
                throw new BusinessHistoryException(BusinessHistoryException.Reason.CONTEXT_CONFLICT);
            target = count;
        }
        if (!announced.isEmpty()) {
            if (target != null || sealed && !expected.containsAll(announced))
                throw new BusinessHistoryException(BusinessHistoryException.Reason.CONTEXT_CONFLICT);
            expected.addAll(announced);
        }
        var items = new TreeMap<>(contributions);
        if (item != null && existing == null) {
            if (target == null && sealed && !expected.contains(item.contributionId()))
                throw new BusinessHistoryException(BusinessHistoryException.Reason.CONTEXT_CONFLICT);
            items.put(item.contributionId(), item);
        }
        if (expected.size() > limits.maxContributionsPerJoin() || items.size() > limits.maxContributionsPerJoin()
                || target != null && target > limits.maxContributionsPerJoin()
                || target != null && items.size() > target)
            throw new BusinessHistoryException(BusinessHistoryException.Reason.RESOURCE_LIMIT);
        var next = new CorrelatedJoinRecord(key, revision + 1, Phase.OPEN, expected, target, sealed || seal,
                items, openedBy, openedAt, deadlineAt, now.isBefore(updatedAt) ? updatedAt : now);
        if (next.payloadBytes() > limits.maxJoinPayloadBytes()) throw new BusinessHistoryException(BusinessHistoryException.Reason.RESOURCE_LIMIT);
        if (next.sealed && target == null && !expected.containsAll(items.keySet()))
            throw new BusinessHistoryException(BusinessHistoryException.Reason.CONTEXT_CONFLICT);
        return next.satisfied() ? next.withPhase(Phase.READY, next.revision, now) : next;
    }
    /** Acknowledges only an already durably accepted deterministic continuation.
 * @param now operation time used to validate deadlines and record the change
 * @return checkpoint recording durable continuation dispatch while retaining the original contribution identities
 */
    public CorrelatedJoinRecord dispatched(Instant now) {
        if (phase != Phase.READY) throw new BusinessHistoryException(BusinessHistoryException.Reason.INVALID_REQUEST);
        return withPhase(Phase.DISPATCHED, revision + 1, now);
    }
    /** Terminal tombstones prevent late contributions or restart from reopening a generation.
 * @param terminal explicit failed, cancelled or expired join state
 * @param now operation time used to validate deadlines and record the change
 * @return terminal join checkpoint retaining its original causal facts
 */
    public CorrelatedJoinRecord terminate(Phase terminal, Instant now) {
        if (terminal != Phase.CANCELLED && terminal != Phase.FAILED && terminal != Phase.EXPIRED
                || phase == Phase.DISPATCHED) throw new BusinessHistoryException(BusinessHistoryException.Reason.INVALID_REQUEST);
        return withPhase(terminal, revision + 1, now);
    }
    private CorrelatedJoinRecord withPhase(Phase next, long version, Instant now) {
        return new CorrelatedJoinRecord(key, version, next, expectedIds, expectedCount, sealed, contributions,
                openedBy, openedAt, deadlineAt, now.isBefore(updatedAt) ? updatedAt : now);
    }
}
