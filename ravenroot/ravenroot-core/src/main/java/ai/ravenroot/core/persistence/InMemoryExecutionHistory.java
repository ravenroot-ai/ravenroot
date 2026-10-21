package ai.ravenroot.core.persistence;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.correlation.CorrelatedTraversal;
import ai.ravenroot.api.history.*;
import ai.ravenroot.api.persistence.*;
import java.util.*;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicLong;

/** Reference metadata paging over already-resident immutable aggregates; no copied payload history. */
final class InMemoryExecutionHistory {
    private InMemoryExecutionHistory() { }
    static HistoryPage<TraversalSummary> traversals(ExecutionKey key, ProcessInstance state,
            Function<UUID, CorrelatedTraversal> context, Optional<String> cursor, int limit, int maximum) {
        requireLimit(limit, maximum); String query = "traversals:" + limit;
        long after = position(key, null, query, cursor);
        if (state == null) return HistoryPage.missing();
        var index = new AtomicLong();
        var selected = state.traversals().values().stream().map(value -> new IndexedTraversal(index.getAndIncrement(), value))
                .filter(value -> value.position > after).limit(limit + 1L).toList();
        boolean more = selected.size() > limit; var rows = more ? selected.subList(0, limit) : selected;
        return new HistoryPage<>(rows.stream().map(value -> traversal(key, value.value, context)).toList(),
                more ? Optional.of(ExecutionHistoryCursor.encode(key, null, query, Long.toString(rows.getLast().position))) : Optional.empty(),
                HistoryPage.Availability.AVAILABLE);
    }
    static Optional<TraversalSummary> traversal(ExecutionKey key, ProcessInstance state, UUID id,
                                                Function<UUID, CorrelatedTraversal> context) {
        return state == null ? Optional.empty() : Optional.ofNullable(state.traversals().get(id)).map(value -> traversal(key, value, context));
    }
    private static TraversalSummary traversal(ExecutionKey key, Traversal value, Function<UUID, CorrelatedTraversal> context) {
        var relation = context.apply(value.traversalId());
        return new TraversalSummary(key.processInstanceId(), value.traversalId(), value.ingressNodeId(), value.status(),
                value.terminationReason(), value.invocations().size(), relation == null ? null : relation.context(),
                relation == null ? null : relation.cause());
    }
    static HistoryPage<NodeVisitSummary> visits(ExecutionKey key, ProcessInstance state, UUID traversal,
            List<JournalRecord> journal, Optional<String> node, Optional<String> cursor, int limit, int maximum) {
        requireLimit(limit, maximum);
        node.ifPresent(value -> { if (value.isBlank() || value.length() > 4096 || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid node filter"); });
        String query = "visits:" + node.orElse("") + ':' + limit;
        long after = position(key, traversal, query, cursor);
        if (state == null || !state.traversals().containsKey(traversal)) return HistoryPage.missing();
        var index = new AtomicLong();
        var selected = state.traversals().get(traversal).invocations().values().stream()
                .map(value -> new IndexedVisit(index.getAndIncrement(), value)).filter(value -> value.position > after)
                .filter(value -> node.isEmpty() || node.orElseThrow().equals(value.value.nodeId())).limit(limit + 1L).toList();
        boolean more = selected.size() > limit; var rows = more ? selected.subList(0, limit) : selected;
        var items = rows.stream().map(value -> {
            var visit = value.value;
            var first = visit.attempts().isEmpty() ? null : visit.attempts().getFirst();
            var last = visit.attempts().isEmpty() || !visit.status().terminal() ? null : visit.attempts().getLast();
            var started = first == null ? Optional.<EventEnvelope>empty() : observation(key, visit.invocationId(), first.attemptId(), false, journal);
            var ended = last == null ? Optional.<EventEnvelope>empty() : observation(key, visit.invocationId(), last.attemptId(), true, journal);
            return new NodeVisitSummary(key.processInstanceId(), traversal, visit.invocationId(), visit.nodeId(), value.position,
                    visit.status(), visit.command().name(), visit.attempts().size(), visit.parentInvocationIds().size(),
                    started.map(EventEnvelope::occurredAt).orElse(null), ended.map(EventEnvelope::occurredAt).orElse(null),
                    ended.flatMap(event -> NodeOutcomeEventData.outcome(event.payload())).orElse(null), started.isPresent() || ended.isPresent());
        }).toList();
        return new HistoryPage<>(items, more ? Optional.of(ExecutionHistoryCursor.encode(key, traversal, query,
                Long.toString(rows.getLast().position))) : Optional.empty(), HistoryPage.Availability.AVAILABLE);
    }
    static HistoryPage<NodeAttemptSummary> attempts(ExecutionKey key, ProcessInstance state, UUID traversal, UUID invocation,
            List<JournalRecord> journal, Optional<String> cursor, int limit, int maximum) {
        requireLimit(limit, maximum); String query = "attempts:" + invocation + ':' + limit;
        long after = position(key, traversal, query, cursor);
        var visit = visit(state, traversal, invocation); if (visit == null) return HistoryPage.missing();
        var selected = visit.attempts().stream().filter(value -> value.ordinal() > after).limit(limit + 1L).toList();
        boolean more = selected.size() > limit; var rows = more ? selected.subList(0, limit) : selected;
        var items = rows.stream().map(value -> {
            var started = observation(key, invocation, value.attemptId(), false, journal);
            var ended = observation(key, invocation, value.attemptId(), true, journal);
            return new NodeAttemptSummary(value.attemptId(), value.ordinal(), value.status(), value.completion(),
                    started.map(EventEnvelope::occurredAt).orElse(null), ended.map(EventEnvelope::occurredAt).orElse(null),
                    started.isPresent() || ended.isPresent());
        }).toList();
        return new HistoryPage<>(items, more ? Optional.of(ExecutionHistoryCursor.encode(key, traversal, query,
                Integer.toString(rows.getLast().ordinal()))) : Optional.empty(), HistoryPage.Availability.AVAILABLE);
    }
    static HistoryPage<VisitParent> parents(ExecutionKey key, ProcessInstance state, UUID traversal, UUID invocation,
            Optional<String> cursor, int limit, int maximum) {
        requireLimit(limit, maximum); String query = "parents:" + invocation + ':' + limit;
        String after = cursor.map(value -> UUID.fromString(ExecutionHistoryCursor.decode(key, traversal, query, value)).toString()).orElse(null);
        var visit = visit(state, traversal, invocation); if (visit == null) return HistoryPage.missing();
        var selected = visit.parentInvocationIds().stream().sorted(Comparator.comparing(UUID::toString))
                .filter(value -> after == null || value.toString().compareTo(after) > 0).limit(limit + 1L).toList();
        boolean more = selected.size() > limit; var rows = more ? selected.subList(0, limit) : selected;
        var wanted = Set.copyOf(rows); var found = new HashMap<UUID, VisitParent>();
        for (var owner : state.traversals().values()) for (var parent : owner.invocations().values())
            if (wanted.contains(parent.invocationId())) found.put(parent.invocationId(), new VisitParent(key.processInstanceId(),
                    owner.traversalId(), parent.invocationId(), parent.nodeId()));
        return new HistoryPage<>(rows.stream().map(found::get).filter(Objects::nonNull).toList(),
                more ? Optional.of(ExecutionHistoryCursor.encode(key, traversal, query, rows.getLast().toString())) : Optional.empty(),
                HistoryPage.Availability.AVAILABLE);
    }
    private static NodeInvocation visit(ProcessInstance state, UUID traversal, UUID invocation) {
        return state == null || !state.traversals().containsKey(traversal) ? null : state.traversals().get(traversal).invocations().get(invocation);
    }
    private static Optional<EventEnvelope> observation(ExecutionKey key, UUID invocation, UUID attempt, boolean terminal, List<JournalRecord> journal) {
        var types = terminal ? Set.of("NODE_COMPLETED", "NODE_FAILED", "NODE_BYPASSED", "SOURCE_BOUNDARY") : Set.of("NODE_STARTED");
        var order = Comparator.comparingLong(JournalRecord::streamSequence);
        return journal.stream().filter(row -> row.key().equals(key)).filter(row -> invocation.equals(row.envelope().invocationId())
                && attempt.equals(row.envelope().attemptId()) && types.contains(row.envelope().eventType()))
                .min(terminal ? order.reversed() : order).map(JournalRecord::envelope);
    }
    private static long position(ExecutionKey key, UUID traversal, String query, Optional<String> cursor) {
        long value = cursor.map(text -> Long.parseLong(ExecutionHistoryCursor.decode(key, traversal, query, text))).orElse(-1L);
        if (value < -1) throw new IllegalArgumentException("invalid history position"); return value;
    }
    private static void requireLimit(int limit, int maximum) {
        if (limit < 1 || limit > maximum) throw new IllegalArgumentException("history page size exceeds configured bound");
    }
    private record IndexedTraversal(long position, Traversal value) { }
    private record IndexedVisit(long position, NodeInvocation value) { }
}
