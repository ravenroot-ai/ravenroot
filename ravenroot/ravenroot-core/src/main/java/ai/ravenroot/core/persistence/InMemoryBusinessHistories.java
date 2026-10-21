package ai.ravenroot.core.persistence;

import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.application.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import static ai.ravenroot.api.correlation.BusinessHistoryException.Reason.*;

/** Reference atomic history model. Its state is deliberately lost when the owning store closes. */
final class InMemoryBusinessHistories {
    private record Key(BusinessScope scope, String id) { }
    private record TraversalKey(String tenant, UUID process, UUID traversal) { }
    private final Map<Key, BusinessHistory> histories = new HashMap<>();
    private final Map<TraversalKey, CorrelatedTraversal> traversals = new HashMap<>();
    private final Clock clock;
    private final BusinessHistoryPolicy policy;
    InMemoryBusinessHistories(Clock clock, BusinessHistoryPolicy policy) { this.clock = clock; this.policy = policy; }
    BusinessHistoryPolicy policy() { return policy; }

    synchronized BusinessHistory admit(CorrelatedTraversal proposed, CorrelationMapping.Mode mode) {
        var key = new Key(proposed.scope(), policy.selection().requireId(proposed.context().applicationCorrelationId(), "applicationCorrelationId"));
        var traversalKey = new TraversalKey(key.scope.tenantId(), proposed.processInstanceId(), proposed.traversalId());
        var prior = traversals.get(traversalKey);
        if (prior != null) {
            if (!sameAdmission(prior, proposed)) throw new BusinessHistoryException(CONTEXT_CONFLICT);
            return histories.get(key);
        }
        expire(key.scope);
        var group = histories.get(key);
        Instant now = Instant.ofEpochMilli(clock.instant().toEpochMilli());
        if (group == null) {
            if (mode == CorrelationMapping.Mode.REQUIRE_EXISTING) throw new BusinessHistoryException(UNKNOWN_HISTORY);
            if (histories.keySet().stream().filter(value -> value.scope.equals(key.scope)).count() >= policy.maxHistoriesPerScope())
                throw new BusinessHistoryException(RESOURCE_LIMIT);
            group = new BusinessHistory(key.scope, key.id, proposed.context().publicReference(), BusinessHistory.Lifecycle.OPEN,
                    1, 0, 0, 0, 0, false, now, now, now.plus(policy.historyRetention()));
        }
        if (group.lifecycle() == BusinessHistory.Lifecycle.EXPIRED) throw new BusinessHistoryException(EXPIRED_HISTORY);
        if (group.lifecycle() != BusinessHistory.Lifecycle.OPEN) throw new BusinessHistoryException(CLOSED_HISTORY);
        if (group.publicReference().isPresent() && proposed.context().publicReference().isPresent()
                && !group.publicReference().equals(proposed.context().publicReference())) throw new BusinessHistoryException(CONTEXT_CONFLICT);
        group = new BusinessHistory(group.scope(), group.applicationCorrelationId(),
                group.publicReference().or(proposed.context()::publicReference), group.lifecycle(), group.revision() + 1,
                group.runningTraversals() + 1, group.completedTraversals(), group.failedTraversals(), group.cancelledTraversals(),
                group.incompleteHistory(), group.createdAt(), now, group.expiresAt());
        histories.put(key, group);
        traversals.put(traversalKey, new CorrelatedTraversal(proposed.scope(), proposed.context(), proposed.processInstanceId(),
                proposed.traversalId(), proposed.entryNodeId(), proposed.cause(), TraversalStatus.ACCEPTED, null, now, now, null));
        return group;
    }

    synchronized Optional<BusinessHistory> history(BusinessScope scope, String id) {
        expire(scope); return Optional.ofNullable(histories.get(new Key(scope, id)));
    }
    synchronized Optional<CorrelatedTraversal> traversal(String tenant, UUID process, UUID traversal) {
        return Optional.ofNullable(traversals.get(new TraversalKey(tenant, process, traversal)));
    }
    synchronized BusinessHistoryPage histories(BusinessScope scope, BusinessHistoryQuery query) {
        requirePage(query.limit()); expire(scope);
        String filter = historyFilter(query);
        String after = query.cursor().map(value -> BusinessCursor.decode(scope, filter, value, 1).getFirst()).orElse(null);
        var page = histories.values().stream().filter(group -> group.scope().equals(scope))
                .filter(group -> query.applicationCorrelationId().isEmpty() || group.applicationCorrelationId().equals(query.applicationCorrelationId().orElseThrow()))
                .filter(group -> query.publicReference().isEmpty() || group.publicReference().equals(query.publicReference()))
                .filter(group -> query.lifecycles().isEmpty() || query.lifecycles().contains(group.lifecycle()))
                .filter(group -> after == null || group.applicationCorrelationId().compareTo(after) > 0)
                .sorted(Comparator.comparing(BusinessHistory::applicationCorrelationId)).limit(query.limit() + 1L).toList();
        boolean more = page.size() > query.limit(); var items = more ? page.subList(0, query.limit()) : page;
        return new BusinessHistoryPage(items, more ? Optional.of(BusinessCursor.encode(scope, filter,
                List.of(items.getLast().applicationCorrelationId()))) : Optional.empty());
    }
    synchronized CorrelatedTraversalPage traversals(BusinessScope scope, String id, Optional<String> cursor, int limit) {
        requirePage(limit);
        String filter = "traversals:" + id;
        var ordering = Comparator.comparing(CorrelatedTraversal::startedAt)
                .thenComparing(value -> value.processInstanceId().toString()).thenComparing(value -> value.traversalId().toString());
        var after = cursor.map(value -> BusinessCursor.decode(scope, filter, value, 3)).orElse(List.of());
        final Instant time;
        try { time = after.isEmpty() ? null : Instant.ofEpochMilli(Long.parseLong(after.getFirst())); }
        catch (RuntimeException invalid) { throw new BusinessHistoryException(INVALID_REQUEST); }
        var page = traversals.values().stream().filter(value -> value.scope().equals(scope)
                        && value.context().applicationCorrelationId().equals(id))
                .filter(value -> after.isEmpty() || value.startedAt().isAfter(time) || (value.startedAt().equals(time)
                        && (value.processInstanceId().toString().compareTo(after.get(1)) > 0
                        || (value.processInstanceId().toString().equals(after.get(1))
                        && value.traversalId().toString().compareTo(after.get(2)) > 0))))
                .sorted(ordering).limit(limit + 1L).toList();
        boolean more = page.size() > limit; var items = more ? page.subList(0, limit) : page;
        Optional<String> next = Optional.empty();
        if (more) { var last = items.getLast(); next = Optional.of(BusinessCursor.encode(scope, filter,
                List.of(Long.toString(last.startedAt().toEpochMilli()), last.processInstanceId().toString(), last.traversalId().toString()))); }
        return new CorrelatedTraversalPage(items, next);
    }
    synchronized void settle(String tenant, UUID process, UUID traversal, TraversalStatus status, ExecutionTerminationReason reason) {
        if (!status.terminal()) throw new BusinessHistoryException(INVALID_REQUEST);
        var key = new TraversalKey(tenant, process, traversal); var prior = traversals.get(key);
        if (prior == null) return;
        if (prior.status().terminal()) {
            if (prior.status() != status || prior.terminationReason() != reason) throw new BusinessHistoryException(CONTEXT_CONFLICT);
            return;
        }
        Instant now = Instant.ofEpochMilli(clock.instant().toEpochMilli());
        traversals.put(key, new CorrelatedTraversal(prior.scope(), prior.context(), process, traversal,
                prior.entryNodeId(), prior.cause(), status, reason, prior.startedAt(), now, now));
        var groupKey = new Key(prior.scope(), prior.context().applicationCorrelationId()); var group = histories.get(groupKey);
        boolean cancelled = reason == ExecutionTerminationReason.CANCELLED;
        histories.put(groupKey, new BusinessHistory(group.scope(), group.applicationCorrelationId(), group.publicReference(),
                group.lifecycle(), group.revision() + 1, group.runningTraversals() - 1,
                group.completedTraversals() + (!cancelled && status == TraversalStatus.COMPLETED ? 1 : 0),
                group.failedTraversals() + (!cancelled && status == TraversalStatus.FAILED ? 1 : 0),
                group.cancelledTraversals() + (cancelled ? 1 : 0), group.incompleteHistory(), group.createdAt(), now, group.expiresAt()));
    }
    synchronized BusinessHistory close(BusinessScope scope, String id, long revision, BusinessHistory.Lifecycle closure) {
        if (closure == null || closure == BusinessHistory.Lifecycle.OPEN || closure == BusinessHistory.Lifecycle.EXPIRED)
            throw new BusinessHistoryException(INVALID_REQUEST);
        var key = new Key(scope, id); var group = history(scope, id).orElseThrow(() -> new BusinessHistoryException(UNKNOWN_HISTORY));
        if (group.lifecycle() != BusinessHistory.Lifecycle.OPEN || group.revision() != revision
                || (closure == BusinessHistory.Lifecycle.COMPLETED && (group.runningTraversals() > 0
                || group.failedTraversals() > 0 || group.cancelledTraversals() > 0))) throw new BusinessHistoryException(REVISION_CONFLICT);
        var closed = new BusinessHistory(scope, id, group.publicReference(), closure, revision + 1,
                group.runningTraversals(), group.completedTraversals(), group.failedTraversals(), group.cancelledTraversals(),
                group.incompleteHistory(), group.createdAt(), Instant.ofEpochMilli(clock.instant().toEpochMilli()), group.expiresAt());
        histories.put(key, closed); cancelPending(scope, id, clock.instant()); return closed;
    }
    private final Map<CorrelatedJoinKey, CorrelatedJoinRecord> joins = new HashMap<>();
    private final Map<CorrelatedJoinKey, NavigableMap<String, JoinMemberSummary>> joinMembers = new HashMap<>();
    private final Map<CorrelatedJoinKey, CorrelatedJoinSummary> expiredJoins = new HashMap<>();
    synchronized Optional<CorrelatedJoinRecord> join(CorrelatedJoinKey key) {
        expire(key.scope());
        if (expiredJoins.containsKey(key)) throw new BusinessHistoryException(EXPIRED_HISTORY);
        return Optional.ofNullable(joins.get(key));
    }
    synchronized CorrelatedJoinRecord writeJoin(CorrelatedJoinRecord desired) {
        var key = desired.key();
        expire(key.scope());
        if (expiredJoins.containsKey(key)) throw new BusinessHistoryException(EXPIRED_HISTORY);
        var prior = Optional.ofNullable(joins.get(key));
        Instant now = clock.instant();
        CorrelatedJoinTransitions.require(prior, desired, policy, now);
        var group = history(key.scope(), key.applicationCorrelationId()).orElseThrow(() -> new BusinessHistoryException(UNKNOWN_HISTORY));
        if ((desired.phase() == CorrelatedJoinRecord.Phase.OPEN || desired.phase() == CorrelatedJoinRecord.Phase.READY)
                && group.lifecycle() != BusinessHistory.Lifecycle.OPEN)
            throw new BusinessHistoryException(group.lifecycle() == BusinessHistory.Lifecycle.EXPIRED ? EXPIRED_HISTORY : CLOSED_HISTORY);
        if (prior.isEmpty()) {
            var existing = joins.values().stream().filter(j -> j.key().scope().equals(key.scope())
                    && j.key().applicationCorrelationId().equals(key.applicationCorrelationId())).toList();
            if (existing.size() + expiredJoins.keySet().stream().filter(j -> j.scope().equals(key.scope())
                    && j.applicationCorrelationId().equals(key.applicationCorrelationId())).count() >= policy.maxRetainedJoinsPerHistory() || existing.stream().filter(j ->
                    j.phase() == CorrelatedJoinRecord.Phase.OPEN || j.phase() == CorrelatedJoinRecord.Phase.READY).count()
                    >= policy.maxOpenJoinsPerHistory()) throw new BusinessHistoryException(RESOURCE_LIMIT);
        }
        for (var c : desired.contributions().values()) {
            if (prior.isPresent() && prior.orElseThrow().contributions().containsKey(c.contributionId())) continue;
            var relation = traversal(key.scope().tenantId(), c.processInstanceId(), c.traversalId())
                    .orElseThrow(() -> new BusinessHistoryException(CONTEXT_CONFLICT));
            if (!relation.scope().equals(key.scope()) || !relation.context().applicationCorrelationId().equals(key.applicationCorrelationId())
                    || !relation.context().contributionId().equals(Optional.of(c.contributionId()))
                    || !relation.context().roundId().equals(key.roundId())) throw new BusinessHistoryException(CONTEXT_CONFLICT);
        }
        CorrelatedJoinCodec.encode(desired);
        joins.put(key, desired);
        var metadata = new TreeMap<String, JoinMemberSummary>();
        desired.expectedIds().forEach(id -> metadata.put(id, new JoinMemberSummary(id, true, false, null, null, null, null, null, null)));
        desired.contributions().forEach((id, value) -> metadata.put(id, JoinMemberSummary.of(value, desired.expectedIds().contains(id))));
        joinMembers.put(key, Collections.unmodifiableNavigableMap(metadata));
        if (desired.phase() == CorrelatedJoinRecord.Phase.FAILED || desired.phase() == CorrelatedJoinRecord.Phase.EXPIRED) {
            var groupKey = new Key(key.scope(), key.applicationCorrelationId());
            var current = histories.get(groupKey);
            if (current.lifecycle() == BusinessHistory.Lifecycle.OPEN) {
                var lifecycle = desired.phase() == CorrelatedJoinRecord.Phase.CANCELLED
                        ? BusinessHistory.Lifecycle.CANCELLED : BusinessHistory.Lifecycle.FAILED;
                histories.put(groupKey, new BusinessHistory(current.scope(), current.applicationCorrelationId(), current.publicReference(),
                        lifecycle, current.revision() + 1, current.runningTraversals(), current.completedTraversals(),
                        current.failedTraversals(), current.cancelledTraversals(), desired.phase() == CorrelatedJoinRecord.Phase.EXPIRED,
                        current.createdAt(), now, current.expiresAt()));
            }
            cancelPending(key.scope(), key.applicationCorrelationId(), now);
        }
        return desired;
    }
    synchronized CorrelatedJoinPage joins(BusinessScope scope, String applicationId,
            Set<CorrelatedJoinRecord.Phase> phases, Optional<String> cursor, int limit) {
        requirePage(limit); expire(scope);
        String filter = "joins:" + Objects.toString(applicationId, "") + '\0'
                + phases.stream().map(Enum::name).sorted().toList() + '\0' + limit;
        var after = cursor.map(value -> BusinessCursor.decode(scope, filter, value, 3)).orElse(List.of());
        var ordering = Comparator.comparing((CorrelatedJoinSummary j) -> j.key().applicationCorrelationId())
                .thenComparing(j -> j.key().nodeId()).thenComparing(j -> j.key().roundId());
        var items = java.util.stream.Stream.concat(joins.values().stream().map(CorrelatedJoinSummary::of), expiredJoins.values().stream())
                .filter(j -> j.key().scope().equals(scope))
                .filter(j -> applicationId == null || j.key().applicationCorrelationId().equals(applicationId))
                .filter(j -> phases.isEmpty() || phases.contains(j.phase()))
                .filter(j -> after.isEmpty() || compareKey(j.key(), after) > 0).sorted(ordering).limit(limit + 1L).toList();
        boolean more = items.size() > limit; var selected = more ? items.subList(0, limit) : items;
        Optional<String> next = Optional.empty();
        if (more) { var last = selected.getLast().key(); next = Optional.of(BusinessCursor.encode(scope, filter,
                List.of(last.applicationCorrelationId(), last.nodeId(), last.roundId()))); }
        return new CorrelatedJoinPage(selected, next);
    }
    synchronized ai.ravenroot.api.history.HistoryPage<JoinMemberSummary> members(CorrelatedJoinKey key,
            JoinMemberFilter filter, Optional<String> cursor, int limit) {
        requirePage(limit); expire(key.scope()); Objects.requireNonNull(filter);
        String binding = "members:" + key.applicationCorrelationId() + '\0' + key.nodeId() + '\0' + key.roundId() + '\0' + filter + ':' + limit;
        String after = cursor.map(value -> BusinessCursor.decode(key.scope(), binding, value, 1).getFirst()).orElse(null);
        var metadata = joinMembers.get(key);
        if (metadata == null) return ai.ravenroot.api.history.HistoryPage.missing();
        var page = (after == null ? metadata : metadata.tailMap(after, false)).values().stream()
                .filter(value -> value.matches(filter)).limit(limit + 1L).toList();
        boolean more = page.size() > limit; var selected = more ? page.subList(0, limit) : page;
        return new ai.ravenroot.api.history.HistoryPage<>(selected,
                more ? Optional.of(BusinessCursor.encode(key.scope(), binding, List.of(selected.getLast().contributionId()))) : Optional.empty(),
                ai.ravenroot.api.history.HistoryPage.Availability.AVAILABLE);
    }
    private void cancelPending(BusinessScope scope, String applicationId, Instant now) {
        joins.replaceAll((key, value) -> key.scope().equals(scope) && key.applicationCorrelationId().equals(applicationId)
                && (value.phase() == CorrelatedJoinRecord.Phase.OPEN || value.phase() == CorrelatedJoinRecord.Phase.READY)
                ? value.terminate(CorrelatedJoinRecord.Phase.CANCELLED, now) : value);
    }
    private static int compareKey(CorrelatedJoinKey key, List<String> after) {
        int compared = key.applicationCorrelationId().compareTo(after.get(0));
        if (compared == 0) compared = key.nodeId().compareTo(after.get(1));
        if (compared == 0) compared = key.roundId().compareTo(after.get(2));
        return compared;
    }
    synchronized void clear() { histories.clear(); traversals.clear(); joins.clear(); expiredJoins.clear(); joinMembers.clear(); }
    private void expire(BusinessScope scope) {
        Instant now = Instant.ofEpochMilli(clock.instant().toEpochMilli());
        histories.replaceAll((key, group) -> key.scope.equals(scope) && group.lifecycle() == BusinessHistory.Lifecycle.OPEN
                && !now.isBefore(group.expiresAt()) ? new BusinessHistory(scope, key.id, group.publicReference(),
                BusinessHistory.Lifecycle.EXPIRED, group.revision() + 1, group.runningTraversals(), group.completedTraversals(),
                group.failedTraversals(), group.cancelledTraversals(), true, group.createdAt(), now, group.expiresAt()) : group);
        var iterator = joins.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next(); var key = entry.getKey();
            var group = histories.get(new Key(key.scope(), key.applicationCorrelationId()));
            if (!key.scope().equals(scope) || group == null || now.isBefore(group.expiresAt())) continue;
            var summary = CorrelatedJoinSummary.of(entry.getValue());
            var phase = summary.phase() == CorrelatedJoinRecord.Phase.OPEN || summary.phase() == CorrelatedJoinRecord.Phase.READY
                    ? CorrelatedJoinRecord.Phase.EXPIRED : summary.phase();
            expiredJoins.put(key, new CorrelatedJoinSummary(key, summary.revision() + 1, phase,
                    summary.expectedContributions(), summary.receivedContributions(), summary.sealed(), 0,
                    summary.openedAt(), summary.deadlineAt(), now, summary.expectation()));
            iterator.remove();
        }
    }
    private void requirePage(int limit) { if (limit < 1 || limit > policy.maxPageSize()) throw new BusinessHistoryException(INVALID_REQUEST); }
    private static String historyFilter(BusinessHistoryQuery query) {
        return "histories:" + query.applicationCorrelationId().orElse("") + '\0' + query.publicReference().orElse("")
                + '\0' + query.lifecycles().stream().map(Enum::name).sorted().toList() + '\0' + query.limit();
    }
    private static boolean sameAdmission(CorrelatedTraversal left, CorrelatedTraversal right) {
        return left.scope().equals(right.scope()) && left.context().equals(right.context())
                && left.processInstanceId().equals(right.processInstanceId()) && left.traversalId().equals(right.traversalId())
                && left.entryNodeId().equals(right.entryNodeId()) && left.cause() == right.cause();
    }
}
