package ai.ravenroot.core.persistence;
import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.history.HistoryPage;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.persistence.*;
import java.time.*;
import java.util.*;
import static ai.ravenroot.api.correlation.BusinessHistoryException.Reason.*;

/** Reference input custody under the execution store's shared transaction monitor. */
final class InMemoryDeploymentEntries {
    private final Map<ExecutionKey, DeploymentEntryIntent> entries = new HashMap<>();
    private final Map<ExecutionKey, Integer> retainedBytes = new HashMap<>();
    private final Clock clock;
    private final InMemoryBusinessHistories histories;
    InMemoryDeploymentEntries(Clock clock, InMemoryBusinessHistories histories) { this.clock = clock; this.histories = histories; }
    DeploymentEntryAdmission admit(DeploymentEntryIntent proposed, CorrelationMapping.Mode mode, BusinessHistoryPolicy policy) {
        if (proposed.checkpoint() != null) throw new BusinessHistoryException(INVALID_REQUEST);
        if (proposed.input().size() > policy.maxPendingEntryBytesPerScope()) throw new BusinessHistoryException(RESOURCE_LIMIT);
        PayloadJson.read(proposed.input().bytes(), policy.selection().input());
        var existing = entries.get(proposed.key());
        if (existing != null) {
            existing.requireSameAdmission(proposed);
            return new DeploymentEntryAdmission(DeploymentEntryAdmission.Outcome.PENDING, Optional.of(existing));
        }
        var prior = histories.traversal(proposed.scope().tenantId(), proposed.processInstanceId(), proposed.traversalId());
        if (prior.isPresent()) {
            var original = prior.orElseThrow();
            if (!original.scope().equals(proposed.scope()) || !original.context().equals(proposed.context())
                    || !original.entryNodeId().equals(proposed.entryNodeId()) || original.cause() != proposed.cause())
                throw new BusinessHistoryException(CONTEXT_CONFLICT);
            return new DeploymentEntryAdmission(original.status().terminal() ? DeploymentEntryAdmission.Outcome.SETTLED
                    : DeploymentEntryAdmission.Outcome.INPUT_UNAVAILABLE, Optional.empty());
        }
        if (proposed.cause() == CorrelatedTraversal.Cause.DERIVED_CONTEXT) {
            var cause = proposed.derivedFrom();
            var original = histories.traversal(proposed.scope().tenantId(), cause.processInstanceId(), cause.traversalId())
                    .orElseThrow(() -> new BusinessHistoryException(CONTEXT_CONFLICT));
            if (!original.scope().equals(proposed.scope()) || original.context().applicationCorrelationId().equals(proposed.context().applicationCorrelationId()))
                throw new BusinessHistoryException(CONTEXT_CONFLICT);
        }
        Instant now = clock.instant();
        var lifetime = Duration.between(proposed.acceptedAt(), proposed.expiresAt());
        if (lifetime.compareTo(policy.historyRetention()) > 0) lifetime = policy.historyRetention();
        var accepted = new DeploymentEntryIntent(proposed.scope(), proposed.processInstanceId(), proposed.traversalId(), proposed.entryNodeId(),
                proposed.context(), proposed.identity(), proposed.input(), now, now.plus(lifetime), proposed.cause(),
                proposed.derivedFrom(), proposed.derivationReason(), null);
        int encoded = DeploymentEntryCodec.encode(accepted).length + 128;
        var selected = entries.values().stream().filter(value -> value.scope().equals(proposed.scope())).toList();
        if (selected.size() >= policy.maxPendingEntriesPerScope()
                || selected.stream().mapToLong(value -> retainedBytes.get(value.key())).sum() > policy.maxPendingEntryBytesPerScope() - encoded)
            throw new BusinessHistoryException(RESOURCE_LIMIT);
        histories.admit(accepted.traversal(), mode);
        entries.put(accepted.key(), accepted); retainedBytes.put(accepted.key(), encoded);
        return new DeploymentEntryAdmission(DeploymentEntryAdmission.Outcome.ACCEPTED, Optional.of(accepted));
    }
    DeploymentEntryIntent projected(ExecutionBatch batch, ai.ravenroot.api.application.ProcessInstance folded) {
        var entry = entries.get(batch.key()); if (entry == null) return null;
        var traversal = folded.traversals().get(entry.traversalId());
        if (folded.status().terminal() || traversal != null && traversal.status().terminal()) return null;
        var checkpoint = DeploymentEntryCheckpoint.project(entry, batch, folded);
        return checkpoint == null ? entry : entry.checkpointed(checkpoint);
    }
    void publish(ExecutionKey key, DeploymentEntryIntent projected) {
        if (projected == null) { entries.remove(key); retainedBytes.remove(key); }
        else { entries.put(key, projected); retainedBytes.putIfAbsent(key, DeploymentEntryCodec.encode(projected).length + 128); }
    }
    Optional<DeploymentEntryIntent> get(ExecutionKey key) { return Optional.ofNullable(entries.get(key)); }
    HistoryPage<DeploymentEntrySummary> page(BusinessScope scope, Optional<String> cursor, int limit, BusinessHistoryPolicy policy) {
        if (limit < 1 || limit > policy.maxPageSize()) throw new BusinessHistoryException(INVALID_REQUEST);
        String query = "deployment-entries:" + limit;
        String after = cursor.map(value -> BusinessCursor.decode(scope, query, value, 1).getFirst()).orElse("");
        if (!after.isEmpty()) UUID.fromString(after);
        var selected = entries.values().stream().filter(value -> value.scope().equals(scope))
                .filter(value -> value.processInstanceId().toString().compareTo(after) > 0)
                .sorted(Comparator.comparing(value -> value.processInstanceId().toString())).limit(limit + 1L)
                .map(DeploymentEntrySummary::of).toList();
        boolean more = selected.size() > limit;
        var rows = more ? selected.subList(0, limit) : selected;
        return new HistoryPage<>(rows, more ? Optional.of(BusinessCursor.encode(scope, query,
                List.of(rows.getLast().processInstanceId().toString()))) : Optional.empty(), HistoryPage.Availability.AVAILABLE);
    }
    void clear() { entries.clear(); retainedBytes.clear(); }
}
